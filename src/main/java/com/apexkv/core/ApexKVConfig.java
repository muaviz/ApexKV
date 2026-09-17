package com.apexkv.core;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Immutable configuration settings for the ApexKV storage engine.
 * Instances are constructed using the fluent {@link Builder}.
 */
public final class ApexKVConfig {
    public static final Path DEFAULT_DATA_DIR = Paths.get("./data/apexkv");
    public static final long DEFAULT_MEMTABLE_THRESHOLD = 4 * 1024 * 1024L; // 4 MB
    public static final int DEFAULT_SPARSE_INDEX_INTERVAL = 16;
    public static final double DEFAULT_BLOOM_FILTER_FPP = 0.01; // 1% false positive probability
    public static final int DEFAULT_COMPACTION_THRESHOLD = 4;
    public static final boolean DEFAULT_SYNC_WAL_ON_WRITE = true;
    public static final long DEFAULT_COMPACTION_INTERVAL_MS = 5_000L;
    public static final int DEFAULT_MAX_IMMUTABLE_MEMTABLES = 4;

    private final Path dataDirectory;
    private final long memTableThresholdBytes;
    private final int sparseIndexInterval;
    private final double bloomFilterFpp;
    private final int compactionThreshold;
    private final boolean syncWalOnWrite;
    private final long compactionIntervalMs;
    private final int maxImmutableMemTables;

    private ApexKVConfig(Builder builder) {
        this.dataDirectory = builder.dataDirectory;
        this.memTableThresholdBytes = builder.memTableThresholdBytes;
        this.sparseIndexInterval = builder.sparseIndexInterval;
        this.bloomFilterFpp = builder.bloomFilterFpp;
        this.compactionThreshold = builder.compactionThreshold;
        this.syncWalOnWrite = builder.syncWalOnWrite;
        this.compactionIntervalMs = builder.compactionIntervalMs;
        this.maxImmutableMemTables = builder.maxImmutableMemTables;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ApexKVConfig defaultConfig() {
        return new Builder().build();
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public Path getWalDirectory() {
        return dataDirectory.resolve("wal");
    }

    public Path getSstableDirectory() {
        return dataDirectory.resolve("sstable");
    }

    public long getMemTableThresholdBytes() {
        return memTableThresholdBytes;
    }

    public int getSparseIndexInterval() {
        return sparseIndexInterval;
    }

    public double getBloomFilterFpp() {
        return bloomFilterFpp;
    }

    public int getCompactionThreshold() {
        return compactionThreshold;
    }

    public boolean isSyncWalOnWrite() {
        return syncWalOnWrite;
    }

    public long getCompactionIntervalMs() {
        return compactionIntervalMs;
    }

    public int getMaxImmutableMemTables() {
        return maxImmutableMemTables;
    }

    public static final class Builder {
        private Path dataDirectory = DEFAULT_DATA_DIR;
        private long memTableThresholdBytes = DEFAULT_MEMTABLE_THRESHOLD;
        private int sparseIndexInterval = DEFAULT_SPARSE_INDEX_INTERVAL;
        private double bloomFilterFpp = DEFAULT_BLOOM_FILTER_FPP;
        private int compactionThreshold = DEFAULT_COMPACTION_THRESHOLD;
        private boolean syncWalOnWrite = DEFAULT_SYNC_WAL_ON_WRITE;
        private long compactionIntervalMs = DEFAULT_COMPACTION_INTERVAL_MS;
        private int maxImmutableMemTables = DEFAULT_MAX_IMMUTABLE_MEMTABLES;

        public Builder dataDirectory(Path dataDirectory) {
            this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory cannot be null");
            return this;
        }

        public Builder dataDirectory(String pathString) {
            return dataDirectory(Paths.get(pathString));
        }

        public Builder memTableThresholdBytes(long bytes) {
            if (bytes <= 0) {
                throw new IllegalArgumentException("memTableThresholdBytes must be positive: " + bytes);
            }
            this.memTableThresholdBytes = bytes;
            return this;
        }

        public Builder sparseIndexInterval(int interval) {
            if (interval <= 0) {
                throw new IllegalArgumentException("sparseIndexInterval must be positive: " + interval);
            }
            this.sparseIndexInterval = interval;
            return this;
        }

        public Builder bloomFilterFpp(double fpp) {
            if (fpp <= 0.0 || fpp >= 1.0) {
                throw new IllegalArgumentException("bloomFilterFpp must be strictly between 0 and 1: " + fpp);
            }
            this.bloomFilterFpp = fpp;
            return this;
        }

        public Builder compactionThreshold(int threshold) {
            if (threshold < 2) {
                throw new IllegalArgumentException("compactionThreshold must be at least 2: " + threshold);
            }
            this.compactionThreshold = threshold;
            return this;
        }

        public Builder syncWalOnWrite(boolean sync) {
            this.syncWalOnWrite = sync;
            return this;
        }

        public Builder compactionIntervalMs(long ms) {
            if (ms <= 0) {
                throw new IllegalArgumentException("compactionIntervalMs must be positive: " + ms);
            }
            this.compactionIntervalMs = ms;
            return this;
        }

        public Builder maxImmutableMemTables(int max) {
            if (max <= 0) {
                throw new IllegalArgumentException("maxImmutableMemTables must be positive: " + max);
            }
            this.maxImmutableMemTables = max;
            return this;
        }

        public ApexKVConfig build() {
            return new ApexKVConfig(this);
        }
    }
}
