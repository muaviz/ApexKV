package com.apexkv.storage.memtable;

import com.apexkv.core.DataRecord;

import java.util.Collections;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable point-in-time snapshot of a MemTable.
 * Used to freeze memory tables awaiting asynchronous background flush to disk (SSTable)
 * while still serving concurrent point reads and range scans.
 */
public class MemTableSnapshot implements MemTable {
    private final NavigableMap<String, DataRecord> snapshotMap;
    private final long byteSize;
    private final long snapshotTimestamp;

    /**
     * Creates an immutable snapshot from an existing MemTable.
     */
    public MemTableSnapshot(MemTable source) {
        Objects.requireNonNull(source, "source MemTable cannot be null");
        this.snapshotMap = Collections.unmodifiableNavigableMap(new TreeMap<>(source.asMap()));
        this.byteSize = source.byteSize();
        this.snapshotTimestamp = System.currentTimeMillis();
    }

    public long getSnapshotTimestamp() {
        return snapshotTimestamp;
    }

    @Override
    public void put(DataRecord record) {
        throw new UnsupportedOperationException("Cannot mutate an immutable MemTableSnapshot");
    }

    @Override
    public DataRecord get(String key) {
        Objects.requireNonNull(key, "key cannot be null");
        return snapshotMap.get(key);
    }

    @Override
    public Iterator<DataRecord> iterator() {
        return snapshotMap.values().iterator();
    }

    @Override
    public Iterator<DataRecord> rangeScan(String startKey, String endKey) {
        if (startKey == null && endKey == null) {
            return snapshotMap.values().iterator();
        } else if (startKey != null && endKey == null) {
            return snapshotMap.tailMap(startKey, true).values().iterator();
        } else if (startKey == null) {
            return snapshotMap.headMap(endKey, false).values().iterator();
        } else {
            if (startKey.compareTo(endKey) > 0) {
                return Collections.emptyIterator();
            }
            return snapshotMap.subMap(startKey, true, endKey, false).values().iterator();
        }
    }

    @Override
    public int count() {
        return snapshotMap.size();
    }

    @Override
    public long byteSize() {
        return byteSize;
    }

    @Override
    public boolean isEmpty() {
        return snapshotMap.isEmpty();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("Cannot clear an immutable MemTableSnapshot");
    }

    @Override
    public String getSmallestKey() {
        return snapshotMap.isEmpty() ? null : snapshotMap.firstKey();
    }

    @Override
    public String getLargestKey() {
        return snapshotMap.isEmpty() ? null : snapshotMap.lastKey();
    }

    @Override
    public NavigableMap<String, DataRecord> asMap() {
        return snapshotMap;
    }
}
