package com.apexkv.storage.compaction;

import com.apexkv.core.ApexKVConfig;
import com.apexkv.core.DataRecord;
import com.apexkv.core.EngineStats;
import com.apexkv.exception.StorageEngineException;
import com.apexkv.storage.sstable.SSTableManager;
import com.apexkv.storage.sstable.SSTableReader;
import com.apexkv.storage.sstable.SSTableWriter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background compaction daemon engine.
 * Periodically or on-demand merges fragmented Level 0 SSTables into consolidated Level 1
 * SSTables using K-Way merge-sort, deduplicating keys and purging tombstones to reclaim disk space.
 */
public class CompactionEngine implements AutoCloseable {
    private final ApexKVConfig config;
    private final SSTableManager sstableManager;
    private final EngineStats stats;
    private final CompactionStrategy strategy;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean isCompacting;
    private final AtomicBoolean isClosed;

    public CompactionEngine(ApexKVConfig config, SSTableManager sstableManager, EngineStats stats) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.sstableManager = Objects.requireNonNull(sstableManager, "sstableManager cannot be null");
        this.stats = Objects.requireNonNull(stats, "stats cannot be null");
        this.strategy = new SizeTieredCompactionStrategy();
        this.isCompacting = new AtomicBoolean(false);
        this.isClosed = new AtomicBoolean(false);

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "apexkv-compaction-daemon");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the periodic background compaction daemon.
     */
    public void start() {
        scheduler.scheduleWithFixedDelay(
                this::runCompactionQuietly,
                config.getCompactionIntervalMs(),
                config.getCompactionIntervalMs(),
                TimeUnit.MILLISECONDS
        );
    }

    private void runCompactionQuietly() {
        try {
            compact();
        } catch (Throwable t) {
            // Log / keep background thread alive
            System.err.println("[CompactionEngine] Background compaction error: " + t.getMessage());
        }
    }

    /**
     * Executes a single compaction pass if candidates meet the threshold, or forces consolidation.
     *
     * @return true if a compaction pass was executed and files were merged
     */
    public boolean compact() {
        return compact(false);
    }

    /**
     * Executes a compaction pass. If force is true, compacts even if below threshold.
     */
    public boolean compact(boolean force) {
        if (isClosed.get()) {
            return false;
        }

        if (!isCompacting.compareAndSet(false, true)) {
            // Compaction already in progress
            return false;
        }

        try {
            List<SSTableReader> l0Tables = sstableManager.getLevelSSTables(0);
            List<SSTableReader> candidates;

            if (force) {
                candidates = new ArrayList<>(l0Tables);
                // If Level 1 tables exist, merge them as well during forced compaction
                candidates.addAll(sstableManager.getLevelSSTables(1));
            } else {
                if (!strategy.shouldCompact(l0Tables, config.getCompactionThreshold())) {
                    return false;
                }
                candidates = strategy.selectCandidates(l0Tables, config.getCompactionThreshold());
            }

            if (candidates.size() < 2 && !force) {
                return false;
            }
            if (candidates.isEmpty()) {
                return false;
            }

            // Prepare K-way iterators
            List<Iterator<DataRecord>> iterators = new ArrayList<>();
            int totalEstimatedRecords = 0;
            for (SSTableReader reader : candidates) {
                iterators.add(reader.iterator());
                totalEstimatedRecords += reader.getRecordCount();
            }

            // If we are merging all active tables, we can safely purge tombstones
            boolean isConsolidatingAll = (candidates.size() == sstableManager.getTotalSSTableCount());
            MergeIterator mergeIterator = new MergeIterator(iterators, isConsolidatingAll);

            if (!mergeIterator.hasNext()) {
                // All records were deleted tombstones and purged
                sstableManager.removeSSTables(candidates);
                stats.incrementCompactionRuns();
                stats.setActiveSSTableCount(sstableManager.getTotalSSTableCount());
                stats.setDiskSizeBytes(sstableManager.getTotalDiskSizeBytes());
                return true;
            }

            // Write consolidated SSTable into Level 1
            Path[] paths = sstableManager.allocateNewSSTablePaths(1);
            SSTableWriter writer = new SSTableWriter(
                    paths[0], paths[1],
                    config.getSparseIndexInterval(),
                    config.getBloomFilterFpp()
            );

            writer.write(mergeIterator, Math.max(1, totalEstimatedRecords));

            // Register new compacted SSTable
            long newId = sstableManager.getNextId();
            SSTableReader newTableReader = new SSTableReader(paths[0], paths[1], newId, 1);
            sstableManager.registerNewSSTable(newTableReader);

            // Remove and delete obsolete candidate SSTables
            sstableManager.removeSSTables(candidates);

            stats.incrementCompactionRuns();
            stats.setActiveSSTableCount(sstableManager.getTotalSSTableCount());
            stats.setDiskSizeBytes(sstableManager.getTotalDiskSizeBytes());

            return true;
        } catch (Exception e) {
            throw new StorageEngineException("Compaction pass failed", e);
        } finally {
            isCompacting.set(false);
        }
    }

    public boolean isCompacting() {
        return isCompacting.get();
    }

    @Override
    public void close() {
        if (isClosed.compareAndSet(false, true)) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
