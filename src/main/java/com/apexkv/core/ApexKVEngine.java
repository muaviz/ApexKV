package com.apexkv.core;

import com.apexkv.exception.StorageEngineException;
import com.apexkv.storage.compaction.CompactionEngine;
import com.apexkv.storage.compaction.MergeIterator;
import com.apexkv.storage.memtable.ConcurrentSkipListMemTable;
import com.apexkv.storage.memtable.MemTable;
import com.apexkv.storage.memtable.MemTableSnapshot;
import com.apexkv.storage.sstable.SSTableManager;
import com.apexkv.storage.sstable.SSTableReader;
import com.apexkv.storage.sstable.SSTableWriter;
import com.apexkv.storage.wal.WalReader;
import com.apexkv.storage.wal.WriteAheadLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * Central orchestrator and primary implementation of {@link StorageEngine}.
 * Coordinates in-memory skip lists (MemTable), append-only write logs (WAL),
 * immutable sorted disk tables (SSTables), background flush execution,
 * multi-way merge compactions, and crash recovery.
 */
public class ApexKVEngine implements StorageEngine {
    private final ApexKVConfig config;
    private final EngineStats stats;
    private final ReentrantReadWriteLock rwLock;
    private final AtomicBoolean isRunning;

    private ConcurrentSkipListMemTable activeMemTable;
    private volatile MemTableSnapshot immutableMemTable;
    private WriteAheadLog activeWal;
    private Path activeWalPath;

    private final SSTableManager sstableManager;
    private final CompactionEngine compactionEngine;
    private final ExecutorService flushExecutor;
    private final Thread shutdownHook;

    /**
     * Bootstraps the ApexKV engine with default configuration.
     */
    public ApexKVEngine() {
        this(ApexKVConfig.defaultConfig());
    }

    /**
     * Bootstraps the ApexKV engine with custom configuration.
     */
    public ApexKVEngine(ApexKVConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.stats = new EngineStats();
        this.rwLock = new ReentrantReadWriteLock(true); // Fair locking
        this.isRunning = new AtomicBoolean(true);

        this.flushExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "apexkv-flush-worker");
            t.setDaemon(true);
            return t;
        });

        try {
            Files.createDirectories(config.getDataDirectory());
            Files.createDirectories(config.getWalDirectory());
            Files.createDirectories(config.getSstableDirectory());

            this.sstableManager = new SSTableManager(config.getSstableDirectory());
            this.activeMemTable = new ConcurrentSkipListMemTable();

            // Perform crash recovery over existing WAL logs
            recoverFromWal();

            // Initialize active WAL segment
            openNewWalSegment();

            // Initialize and start background compaction daemon
            this.compactionEngine = new CompactionEngine(config, sstableManager, stats);
            this.compactionEngine.start();

            // Register JVM shutdown hook for clean termination
            this.shutdownHook = new Thread(this::shutdownGracefully, "apexkv-shutdown-hook");
            Runtime.getRuntime().addShutdownHook(shutdownHook);

            // Update stats
            this.stats.setActiveSSTableCount(sstableManager.getTotalSSTableCount());
            this.stats.setDiskSizeBytes(sstableManager.getTotalDiskSizeBytes());

        } catch (IOException e) {
            throw new StorageEngineException("Failed to initialize ApexKVEngine", e);
        }
    }

    /**
     * Replays uncommitted WAL logs on boot into the active MemTable.
     */
    private void recoverFromWal() throws IOException {
        try (Stream<Path> walFiles = Files.list(config.getWalDirectory())) {
            List<Path> sortedWalPaths = walFiles
                    .filter(p -> p.getFileName().toString().endsWith(".wal"))
                    .sorted()
                    .toList();

            for (Path walPath : sortedWalPaths) {
                try (WalReader reader = new WalReader(walPath, true)) {
                    List<DataRecord> records = reader.readAll();
                    for (DataRecord rec : records) {
                        activeMemTable.put(rec);
                    }
                }
            }
        }
    }

    private synchronized void openNewWalSegment() {
        long segmentId = System.currentTimeMillis();
        this.activeWalPath = config.getWalDirectory().resolve(String.format("wal_%d.wal", segmentId));
        this.activeWal = new WriteAheadLog(activeWalPath, config.isSyncWalOnWrite());
    }

    @Override
    public void put(String key, byte[] value) {
        ensureRunning();
        Objects.requireNonNull(key, "key cannot be null");
        stats.incrementPuts();

        DataRecord record = DataRecord.put(key, value);
        boolean needFlush = false;

        rwLock.readLock().lock();
        try {
            int written = activeWal.append(record);
            stats.addBytesWritten(written);
            activeMemTable.put(record);

            if (activeMemTable.byteSize() >= config.getMemTableThresholdBytes()) {
                needFlush = true;
            }
        } finally {
            rwLock.readLock().unlock();
        }

        if (needFlush) {
            triggerRotation();
        }
    }

    @Override
    public byte[] get(String key) {
        ensureRunning();
        Objects.requireNonNull(key, "key cannot be null");
        stats.incrementGets();

        // 1. Search Active MemTable
        DataRecord record = activeMemTable.get(key);
        if (record != null) {
            stats.incrementMemTableHits();
            return record.isTombstone() ? null : record.getValue();
        }

        // 2. Search Immutable MemTable (if flush in progress)
        MemTableSnapshot imm = immutableMemTable;
        if (imm != null) {
            record = imm.get(key);
            if (record != null) {
                stats.incrementMemTableHits();
                return record.isTombstone() ? null : record.getValue();
            }
        }

        // 3. Search SSTables from newest to oldest
        record = sstableManager.get(key);
        if (record != null) {
            stats.incrementSstableHits();
            return record.isTombstone() ? null : record.getValue();
        }

        // 4. Record not found
        stats.incrementMisses();
        return null;
    }

    @Override
    public void delete(String key) {
        ensureRunning();
        Objects.requireNonNull(key, "key cannot be null");
        stats.incrementDeletes();

        DataRecord record = DataRecord.delete(key);
        boolean needFlush = false;

        rwLock.readLock().lock();
        try {
            int written = activeWal.append(record);
            stats.addBytesWritten(written);
            activeMemTable.put(record);

            if (activeMemTable.byteSize() >= config.getMemTableThresholdBytes()) {
                needFlush = true;
            }
        } finally {
            rwLock.readLock().unlock();
        }

        if (needFlush) {
            triggerRotation();
        }
    }

    @Override
    public Iterator<DataRecord> scan(String startKey, String endKey) {
        ensureRunning();
        List<Iterator<DataRecord>> iterators = new ArrayList<>();

        // 1. Active MemTable iterator
        iterators.add(activeMemTable.rangeScan(startKey, endKey));

        // 2. Immutable MemTable iterator (if present)
        MemTableSnapshot imm = immutableMemTable;
        if (imm != null) {
            iterators.add(imm.rangeScan(startKey, endKey));
        }

        // 3. All SSTable iterators
        for (SSTableReader reader : sstableManager.getAllSSTables()) {
            iterators.add(reader.rangeScan(startKey, endKey));
        }

        // Merge iterators with tombstone purging
        return new MergeIterator(iterators, true);
    }

    /**
     * Rotates the active MemTable into an immutable MemTable and schedules background disk flush.
     */
    private void triggerRotation() {
        Path oldWalPath;
        MemTableSnapshot tableToFlush;

        rwLock.writeLock().lock();
        try {
            if (activeMemTable.isEmpty()) {
                return;
            }

            // Wait if previous flush is still ongoing
            while (immutableMemTable != null) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            tableToFlush = new MemTableSnapshot(activeMemTable);
            immutableMemTable = tableToFlush;
            activeMemTable = new ConcurrentSkipListMemTable();

            oldWalPath = activeWalPath;
            activeWal.close();
            openNewWalSegment();

        } finally {
            rwLock.writeLock().unlock();
        }

        flushExecutor.submit(() -> doFlush(tableToFlush, oldWalPath));
    }

    private void doFlush(MemTableSnapshot snapshot, Path oldWal) {
        try {
            if (snapshot.isEmpty()) {
                immutableMemTable = null;
                return;
            }

            Path[] paths = sstableManager.allocateNewSSTablePaths(0);
            SSTableWriter writer = new SSTableWriter(
                    paths[0], paths[1],
                    config.getSparseIndexInterval(),
                    config.getBloomFilterFpp()
            );

            writer.write(snapshot.iterator(), snapshot.count());

            long newId = sstableManager.getNextId();
            SSTableReader reader = new SSTableReader(paths[0], paths[1], newId, 0);
            sstableManager.registerNewSSTable(reader);

            // Safely delete flushed WAL segment
            if (oldWal != null) {
                try {
                    Files.deleteIfExists(oldWal);
                } catch (IOException ignored) {}
            }

            stats.incrementFlushRuns();
            stats.setActiveSSTableCount(sstableManager.getTotalSSTableCount());
            stats.setDiskSizeBytes(sstableManager.getTotalDiskSizeBytes());

        } catch (Exception e) {
            System.err.println("[ApexKVEngine] Flush error: " + e.getMessage());
        } finally {
            immutableMemTable = null;
        }
    }

    @Override
    public void flush() {
        ensureRunning();
        Future<?> future = null;

        rwLock.writeLock().lock();
        try {
            if (activeMemTable.isEmpty() && immutableMemTable == null) {
                return;
            }

            if (!activeMemTable.isEmpty()) {
                MemTableSnapshot toFlush = new MemTableSnapshot(activeMemTable);
                immutableMemTable = toFlush;
                activeMemTable = new ConcurrentSkipListMemTable();

                Path oldWal = activeWalPath;
                activeWal.close();
                openNewWalSegment();

                future = flushExecutor.submit(() -> doFlush(toFlush, oldWal));
            }
        } finally {
            rwLock.writeLock().unlock();
        }

        if (future != null) {
            try {
                future.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new StorageEngineException("Synchronous flush failed", e);
            }
        }
    }

    @Override
    public void compact() {
        ensureRunning();
        compactionEngine.compact(true);
    }

    @Override
    public EngineStats getStats() {
        stats.setActiveSSTableCount(sstableManager.getTotalSSTableCount());
        stats.setDiskSizeBytes(sstableManager.getTotalDiskSizeBytes());
        return stats;
    }

    @Override
    public ApexKVConfig getConfig() {
        return config;
    }

    public SSTableManager getSstableManager() {
        return sstableManager;
    }

    public MemTable getActiveMemTable() {
        return activeMemTable;
    }

    @Override
    public boolean isClosed() {
        return !isRunning.get();
    }

    private void ensureRunning() {
        if (!isRunning.get()) {
            throw new IllegalStateException("ApexKVEngine is closed");
        }
    }

    private void shutdownGracefully() {
        close();
    }

    @Override
    public synchronized void close() {
        if (isRunning.compareAndSet(true, false)) {
            try {
                // Remove shutdown hook if invoked programmatically
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {}

                compactionEngine.close();

                // Synchronously flush any remaining in-memory data
                if (!activeMemTable.isEmpty()) {
                    Path[] paths = sstableManager.allocateNewSSTablePaths(0);
                    SSTableWriter writer = new SSTableWriter(
                            paths[0], paths[1],
                            config.getSparseIndexInterval(),
                            config.getBloomFilterFpp()
                    );
                    writer.write(activeMemTable.iterator(), activeMemTable.count());
                    long newId = sstableManager.getNextId();
                    SSTableReader reader = new SSTableReader(paths[0], paths[1], newId, 0);
                    sstableManager.registerNewSSTable(reader);
                }

                flushExecutor.shutdown();
                try {
                    if (!flushExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        flushExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    flushExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }

                if (activeWal != null) {
                    activeWal.close();
                }

                sstableManager.close();

            } catch (Exception e) {
                throw new StorageEngineException("Error during engine shutdown", e);
            }
        }
    }
}
