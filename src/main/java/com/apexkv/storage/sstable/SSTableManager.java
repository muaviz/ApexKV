package com.apexkv.storage.sstable;

import com.apexkv.core.DataRecord;
import com.apexkv.exception.StorageEngineException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Thread-safe manager governing on-disk SSTables across levels (Level 0 and Level 1).
 * Coordinates table discovery, point lookups from newest to oldest, and atomic file rotation.
 */
public class SSTableManager implements AutoCloseable {
    // Filename convention: sst_<id>_L<level>.db and sst_<id>_L<level>.idx
    private static final Pattern SST_FILE_PATTERN = Pattern.compile("^sst_(\\d+)_L(\\d+)\\.db$");

    private final Path sstableDir;
    private final List<SSTableReader> level0;
    private final List<SSTableReader> level1;
    private final AtomicLong nextSSTableId;

    public SSTableManager(Path sstableDir) {
        this.sstableDir = sstableDir;
        this.level0 = new CopyOnWriteArrayList<>();
        this.level1 = new CopyOnWriteArrayList<>();
        this.nextSSTableId = new AtomicLong(1);

        try {
            Files.createDirectories(sstableDir);
            recoverExistingSSTables();
        } catch (IOException e) {
            throw new StorageEngineException("Failed to initialize SSTableManager at: " + sstableDir, e);
        }
    }

    private void recoverExistingSSTables() throws IOException {
        long maxId = 0;
        try (Stream<Path> stream = Files.list(sstableDir)) {
            List<Path> dbFiles = stream
                    .filter(p -> p.getFileName().toString().endsWith(".db"))
                    .sorted()
                    .toList();

            for (Path dbPath : dbFiles) {
                String fileName = dbPath.getFileName().toString();
                Matcher matcher = SST_FILE_PATTERN.matcher(fileName);
                if (matcher.matches()) {
                    long id = Long.parseLong(matcher.group(1));
                    int level = Integer.parseInt(matcher.group(2));
                    maxId = Math.max(maxId, id);

                    String idxFileName = fileName.replace(".db", ".idx");
                    Path idxPath = sstableDir.resolve(idxFileName);
                    if (Files.exists(idxPath)) {
                        SSTableReader reader = new SSTableReader(dbPath, idxPath, id, level);
                        if (level == 0) {
                            level0.add(reader);
                        } else {
                            level1.add(reader);
                        }
                    }
                }
            }
        }
        nextSSTableId.set(maxId + 1);

        // Sort Level 0 newest first (higher ID first)
        level0.sort((a, b) -> Long.compare(b.getId(), a.getId()));
        // Sort Level 1 by smallest key ascending
        level1.sort(Comparator.comparing(SSTableReader::getSmallestKey));
    }

    /**
     * Point lookup traversing SSTables in newest-to-oldest order.
     * Returns the DataRecord if found, or null if definitely absent.
     * Note: If a tombstone record is found, it is returned so the caller knows the key was deleted!
     */
    public DataRecord get(String key) {
        // 1. Search Level 0 newest to oldest
        for (SSTableReader reader : level0) {
            DataRecord record = reader.get(key);
            if (record != null) {
                return record;
            }
        }

        // 2. Search Level 1
        for (SSTableReader reader : level1) {
            DataRecord record = reader.get(key);
            if (record != null) {
                return record;
            }
        }

        return null;
    }

    /**
     * Allocates paths for a new SSTable.
     */
    public synchronized Path[] allocateNewSSTablePaths(int level) {
        long id = nextSSTableId.getAndIncrement();
        String prefix = String.format("sst_%06d_L%d", id, level);
        Path dbPath = sstableDir.resolve(prefix + ".db");
        Path idxPath = sstableDir.resolve(prefix + ".idx");
        return new Path[]{dbPath, idxPath};
    }

    public synchronized long getNextId() {
        return nextSSTableId.getAndIncrement();
    }

    /**
     * Registers a freshly written SSTable.
     */
    public synchronized void registerNewSSTable(SSTableReader reader) {
        if (reader.getLevel() == 0) {
            // Newest at index 0
            level0.add(0, reader);
        } else {
            level1.add(reader);
            level1.sort(Comparator.comparing(SSTableReader::getSmallestKey));
        }
    }

    /**
     * Safely closes and removes obsolete SSTables after compaction.
     */
    public synchronized void removeSSTables(List<SSTableReader> tablesToRemove) {
        for (SSTableReader table : tablesToRemove) {
            level0.remove(table);
            level1.remove(table);
            table.close();

            try {
                Files.deleteIfExists(table.getDataFilePath());
                Files.deleteIfExists(table.getIndexFilePath());
            } catch (IOException ignored) {}
        }
    }

    public List<SSTableReader> getLevelSSTables(int level) {
        if (level == 0) {
            return Collections.unmodifiableList(new ArrayList<>(level0));
        } else if (level == 1) {
            return Collections.unmodifiableList(new ArrayList<>(level1));
        }
        return Collections.emptyList();
    }

    public List<SSTableReader> getAllSSTables() {
        List<SSTableReader> all = new ArrayList<>(level0);
        all.addAll(level1);
        return Collections.unmodifiableList(all);
    }

    public int getTotalSSTableCount() {
        return level0.size() + level1.size();
    }

    public long getTotalDiskSizeBytes() {
        long total = 0;
        for (SSTableReader reader : level0) {
            total += reader.getDiskSizeBytes();
        }
        for (SSTableReader reader : level1) {
            total += reader.getDiskSizeBytes();
        }
        return total;
    }

    @Override
    public synchronized void close() {
        for (SSTableReader reader : level0) {
            reader.close();
        }
        for (SSTableReader reader : level1) {
            reader.close();
        }
        level0.clear();
        level1.clear();
    }
}
