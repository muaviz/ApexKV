package com.apexkv.storage.sstable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * In-memory sorted index storing sparse key checkpoints from an SSTable.
 * Provides O(log K) binary search to bound disk block seeks.
 */
public class SparseIndex {
    private final List<IndexEntry> entries;

    public SparseIndex() {
        this.entries = new ArrayList<>();
    }

    public SparseIndex(List<IndexEntry> entries) {
        this.entries = new ArrayList<>(Objects.requireNonNull(entries, "entries cannot be null"));
    }

    /**
     * Appends a new sparse index checkpoint. Keys must be added in lexicographical order.
     */
    public void addEntry(String key, long fileOffset, int blockSize) {
        entries.add(new IndexEntry(key, fileOffset, blockSize));
    }

    /**
     * Performs binary search to locate the index entry corresponding to the disk block
     * that would contain the specified key.
     *
     * @param key the target search key
     * @return the candidate {@link IndexEntry}, or null if the index is empty
     */
    public IndexEntry search(String key) {
        if (entries.isEmpty()) {
            return null;
        }

        // Dummy IndexEntry for binary search comparison
        IndexEntry target = new IndexEntry(key, 0L, 0);
        int index = Collections.binarySearch(entries, target);

        if (index >= 0) {
            // Exact match
            return entries.get(index);
        }

        int insertionPoint = -(index + 1);
        if (insertionPoint == 0) {
            // Key is smaller than the first index entry; check first block
            return entries.get(0);
        } else {
            // Key is bounded between entries[insertionPoint - 1] and entries[insertionPoint]
            return entries.get(insertionPoint - 1);
        }
    }

    public List<IndexEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public int size() {
        return entries.size();
    }

    public IndexEntry get(int index) {
        return entries.get(index);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
