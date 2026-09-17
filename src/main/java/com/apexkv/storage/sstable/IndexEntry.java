package com.apexkv.storage.sstable;

import java.util.Objects;

/**
 * Immutable index pointer mapping a key to its data block physical file offset
 * and block size within an SSTable data file.
 */
public final class IndexEntry implements Comparable<IndexEntry> {
    private final String key;
    private final long fileOffset;
    private final int blockSize;

    public IndexEntry(String key, long fileOffset, int blockSize) {
        this.key = Objects.requireNonNull(key, "key cannot be null");
        this.fileOffset = fileOffset;
        this.blockSize = blockSize;
    }

    public String getKey() {
        return key;
    }

    public long getFileOffset() {
        return fileOffset;
    }

    public int getBlockSize() {
        return blockSize;
    }

    @Override
    public int compareTo(IndexEntry o) {
        return this.key.compareTo(o.key);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IndexEntry that)) return false;
        return fileOffset == that.fileOffset &&
                blockSize == that.blockSize &&
                Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, fileOffset, blockSize);
    }

    @Override
    public String toString() {
        return "IndexEntry{" +
                "key='" + key + '\'' +
                ", offset=" + fileOffset +
                ", size=" + blockSize +
                '}';
    }
}
