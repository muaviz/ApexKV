package com.apexkv.storage.sstable;

import com.apexkv.core.DataRecord;
import com.apexkv.storage.filter.BloomFilter;

import java.nio.file.Path;
import java.util.Iterator;

/**
 * Interface contract representing an immutable on-disk Sorted String Table (SSTable).
 */
public interface SSTable extends Iterable<DataRecord>, AutoCloseable {

    /**
     * Searches for a record by key in this SSTable.
     * Returns null if key does not exist or if key was deleted (tombstone).
     */
    DataRecord get(String key);

    /**
     * Performs a range scan over keys in [startKey, endKey).
     */
    Iterator<DataRecord> rangeScan(String startKey, String endKey);

    /**
     * Sequential iterator over all records in this SSTable from smallest to largest key.
     */
    @Override
    Iterator<DataRecord> iterator();

    BloomFilter getBloomFilter();

    SparseIndex getSparseIndex();

    long getRecordCount();

    long getCreatedAt();

    String getSmallestKey();

    String getLargestKey();

    Path getDataFilePath();

    Path getIndexFilePath();

    long getDiskSizeBytes();

    int getLevel();

    void setLevel(int level);

    long getId();

    boolean isClosed();

    @Override
    void close();
}
