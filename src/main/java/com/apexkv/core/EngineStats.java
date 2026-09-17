package com.apexkv.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe observability metrics and operational statistics container for ApexKV.
 */
public final class EngineStats {
    private final AtomicLong getRequests = new AtomicLong(0);
    private final AtomicLong putRequests = new AtomicLong(0);
    private final AtomicLong deleteRequests = new AtomicLong(0);
    private final AtomicLong memTableHits = new AtomicLong(0);
    private final AtomicLong sstableHits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong bloomFilterPrunes = new AtomicLong(0);
    private final AtomicLong bytesWritten = new AtomicLong(0);
    private final AtomicLong bytesRead = new AtomicLong(0);
    private final AtomicLong compactionRuns = new AtomicLong(0);
    private final AtomicLong flushRuns = new AtomicLong(0);
    private final AtomicInteger activeSSTableCount = new AtomicInteger(0);
    private final AtomicLong diskSizeBytes = new AtomicLong(0);

    public void incrementGets() {
        getRequests.incrementAndGet();
    }

    public void incrementPuts() {
        putRequests.incrementAndGet();
    }

    public void incrementDeletes() {
        deleteRequests.incrementAndGet();
    }

    public void incrementMemTableHits() {
        memTableHits.incrementAndGet();
    }

    public void incrementSstableHits() {
        sstableHits.incrementAndGet();
    }

    public void incrementMisses() {
        misses.incrementAndGet();
    }

    public void incrementBloomFilterPrunes() {
        bloomFilterPrunes.incrementAndGet();
    }

    public void addBytesWritten(long bytes) {
        if (bytes > 0) {
            bytesWritten.addAndGet(bytes);
        }
    }

    public void addBytesRead(long bytes) {
        if (bytes > 0) {
            bytesRead.addAndGet(bytes);
        }
    }

    public void incrementCompactionRuns() {
        compactionRuns.incrementAndGet();
    }

    public void incrementFlushRuns() {
        flushRuns.incrementAndGet();
    }

    public void setActiveSSTableCount(int count) {
        activeSSTableCount.set(count);
    }

    public void setDiskSizeBytes(long bytes) {
        diskSizeBytes.set(bytes);
    }

    // Getters
    public long getGetRequests() {
        return getRequests.get();
    }

    public long getPutRequests() {
        return putRequests.get();
    }

    public long getDeleteRequests() {
        return deleteRequests.get();
    }

    public long getTotalOperations() {
        return getRequests.get() + putRequests.get() + deleteRequests.get();
    }

    public long getMemTableHits() {
        return memTableHits.get();
    }

    public long getSstableHits() {
        return sstableHits.get();
    }

    public long getMisses() {
        return misses.get();
    }

    public long getBloomFilterPrunes() {
        return bloomFilterPrunes.get();
    }

    public long getBytesWritten() {
        return bytesWritten.get();
    }

    public long getBytesRead() {
        return bytesRead.get();
    }

    public long getCompactionRuns() {
        return compactionRuns.get();
    }

    public long getFlushRuns() {
        return flushRuns.get();
    }

    public int getActiveSSTableCount() {
        return activeSSTableCount.get();
    }

    public long getDiskSizeBytes() {
        return diskSizeBytes.get();
    }

    /**
     * Calculates the cache hit ratio for point reads served from the in-memory MemTable.
     */
    public double getMemTableHitRatio() {
        long totalReads = getRequests.get();
        if (totalReads == 0) return 0.0;
        return (double) memTableHits.get() / totalReads;
    }

    /**
     * Resets all metric counters to zero.
     */
    public void reset() {
        getRequests.set(0);
        putRequests.set(0);
        deleteRequests.set(0);
        memTableHits.set(0);
        sstableHits.set(0);
        misses.set(0);
        bloomFilterPrunes.set(0);
        bytesWritten.set(0);
        bytesRead.set(0);
        compactionRuns.set(0);
        flushRuns.set(0);
    }

    @Override
    public String toString() {
        return String.format(
                "EngineStats[Gets=%d (MemHits=%d, SstHits=%d, Miss=%d, BFPruned=%d), Puts=%d, Dels=%d, " +
                "Flushes=%d, Compactions=%d, SSTables=%d, DiskBytes=%d, Written=%d, Read=%d]",
                getRequests.get(), memTableHits.get(), sstableHits.get(), misses.get(),
                bloomFilterPrunes.get(), putRequests.get(), deleteRequests.get(),
                flushRuns.get(), compactionRuns.get(), activeSSTableCount.get(),
                diskSizeBytes.get(), bytesWritten.get(), bytesRead.get());
    }
}
