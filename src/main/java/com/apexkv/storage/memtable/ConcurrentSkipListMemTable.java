package com.apexkv.storage.memtable;

import com.apexkv.core.DataRecord;

import java.util.Collections;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe lock-free MemTable implementation backed by {@link ConcurrentSkipListMap}.
 * Provides O(log N) concurrent point lookups, insertions, and range scans.
 * Tracks estimated heap consumption atomically to trigger flushes when threshold is reached.
 */
public class ConcurrentSkipListMemTable implements MemTable {
    private final ConcurrentSkipListMap<String, DataRecord> map;
    private final AtomicLong estimatedByteSize;

    public ConcurrentSkipListMemTable() {
        this.map = new ConcurrentSkipListMap<>();
        this.estimatedByteSize = new AtomicLong(0);
    }

    @Override
    public void put(DataRecord record) {
        Objects.requireNonNull(record, "record cannot be null");
        int newRecordBytes = record.estimatedByteSize();

        DataRecord previous = map.put(record.getKey(), record);
        if (previous != null) {
            int byteDelta = newRecordBytes - previous.estimatedByteSize();
            estimatedByteSize.addAndGet(byteDelta);
        } else {
            estimatedByteSize.addAndGet(newRecordBytes);
        }
    }

    @Override
    public DataRecord get(String key) {
        Objects.requireNonNull(key, "key cannot be null");
        return map.get(key);
    }

    @Override
    public Iterator<DataRecord> iterator() {
        return map.values().iterator();
    }

    @Override
    public Iterator<DataRecord> rangeScan(String startKey, String endKey) {
        if (startKey == null && endKey == null) {
            return map.values().iterator();
        } else if (startKey != null && endKey == null) {
            return map.tailMap(startKey, true).values().iterator();
        } else if (startKey == null) {
            return map.headMap(endKey, false).values().iterator();
        } else {
            if (startKey.compareTo(endKey) > 0) {
                return Collections.emptyIterator();
            }
            return map.subMap(startKey, true, endKey, false).values().iterator();
        }
    }

    @Override
    public int count() {
        return map.size();
    }

    @Override
    public long byteSize() {
        return estimatedByteSize.get();
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public void clear() {
        map.clear();
        estimatedByteSize.set(0);
    }

    @Override
    public String getSmallestKey() {
        return map.isEmpty() ? null : map.firstKey();
    }

    @Override
    public String getLargestKey() {
        return map.isEmpty() ? null : map.lastKey();
    }

    @Override
    public NavigableMap<String, DataRecord> asMap() {
        return Collections.unmodifiableNavigableMap(map);
    }
}
