package com.apexkv.storage.memtable;

import com.apexkv.core.DataRecord;

import java.util.Iterator;
import java.util.NavigableMap;

/**
 * Interface contract representing the in-memory write buffer for the LSM-Tree.
 * Manages lexicographically sorted key-value records prior to disk flushing.
 */
public interface MemTable extends Iterable<DataRecord> {

    /**
     * Inserts or replaces a record in the in-memory table.
     *
     * @param record non-null DataRecord (PUT or DELETE)
     */
    void put(DataRecord record);

    /**
     * Retrieves the record matching the specified key from the table.
     *
     * @param key target key
     * @return the DataRecord, or {@code null} if not found in this table
     */
    DataRecord get(String key);

    /**
     * Returns an iterator over all records in the table, sorted lexicographically by key.
     */
    @Override
    Iterator<DataRecord> iterator();

    /**
     * Performs a range scan over keys in the half-open interval [startKey, endKey).
     *
     * @param startKey inclusive start key (or null for beginning)
     * @param endKey   exclusive end key (or null for end)
     * @return an iterator over sorted records within the range
     */
    Iterator<DataRecord> rangeScan(String startKey, String endKey);

    /**
     * Returns the total number of distinct keys stored in this MemTable.
     */
    int count();

    /**
     * Returns the estimated byte size consumed by all records currently buffered in memory.
     */
    long byteSize();

    /**
     * Checks whether the MemTable contains zero records.
     */
    boolean isEmpty();

    /**
     * Clears all records and resets byte counters.
     */
    void clear();

    /**
     * Returns the lexicographically smallest key in this table, or null if empty.
     */
    String getSmallestKey();

    /**
     * Returns the lexicographically largest key in this table, or null if empty.
     */
    String getLargestKey();

    /**
     * Returns an unmodifiable NavigableMap view of the current table contents.
     */
    NavigableMap<String, DataRecord> asMap();
}
