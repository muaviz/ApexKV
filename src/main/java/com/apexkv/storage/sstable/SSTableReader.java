package com.apexkv.storage.sstable;

import com.apexkv.core.DataRecord;
import com.apexkv.core.RecordType;
import com.apexkv.exception.StorageEngineException;
import com.apexkv.storage.filter.BloomFilter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-performance binary reader for an on-disk SSTable.
 * Uses an in-memory Bloom filter for O(1) negative lookup pruning,
 * binary-searches the sparse index, and performs bounded block scans via {@link FileChannel}.
 */
public class SSTableReader implements SSTable {
    private final Path dataPath;
    private final Path indexPath;
    private final long id;
    private int level;

    private final long createdAt;
    private final int recordCount;
    private final String smallestKey;
    private final String largestKey;
    private final BloomFilter bloomFilter;
    private final SparseIndex sparseIndex;

    private final FileChannel dbChannel;
    private final long dbFileSize;
    private final AtomicBoolean isClosed;

    public SSTableReader(Path dataPath, Path indexPath, long id, int level) {
        this.dataPath = Objects.requireNonNull(dataPath, "dataPath cannot be null");
        this.indexPath = Objects.requireNonNull(indexPath, "indexPath cannot be null");
        this.id = id;
        this.level = level;
        this.isClosed = new AtomicBoolean(false);

        if (!Files.exists(dataPath) || !Files.exists(indexPath)) {
            throw new StorageEngineException("SSTable files missing: " + dataPath + " or " + indexPath);
        }

        try {
            this.dbChannel = FileChannel.open(dataPath, StandardOpenOption.READ);
            this.dbFileSize = dbChannel.size();

            // Load index metadata
            try (FileChannel idxChannel = FileChannel.open(indexPath, StandardOpenOption.READ)) {
                ByteBuffer idxBuf = ByteBuffer.allocate((int) idxChannel.size());
                while (idxBuf.hasRemaining()) {
                    idxChannel.read(idxBuf);
                }
                idxBuf.flip();

                int magic = idxBuf.getInt();
                if (magic != SSTableWriter.MAGIC_HEADER) {
                    throw new StorageEngineException(String.format(
                            "Invalid SSTable index magic header: 0x%08X in %s", magic, indexPath));
                }

                short version = idxBuf.getShort();
                if (version != SSTableWriter.SSTABLE_VERSION) {
                    throw new StorageEngineException("Unsupported SSTable version: " + version);
                }

                this.createdAt = idxBuf.getLong();
                this.recordCount = idxBuf.getInt();

                int smallestKeyLen = idxBuf.getInt();
                byte[] smallestKeyBytes = new byte[smallestKeyLen];
                idxBuf.get(smallestKeyBytes);
                this.smallestKey = new String(smallestKeyBytes, StandardCharsets.UTF_8);

                int largestKeyLen = idxBuf.getInt();
                byte[] largestKeyBytes = new byte[largestKeyLen];
                idxBuf.get(largestKeyBytes);
                this.largestKey = new String(largestKeyBytes, StandardCharsets.UTF_8);

                // Deserialize Bloom Filter
                this.bloomFilter = BloomFilter.readFrom(idxBuf);

                // Deserialize Sparse Index
                int entryCount = idxBuf.getInt();
                this.sparseIndex = new SparseIndex();
                for (int i = 0; i < entryCount; i++) {
                    int kLen = idxBuf.getInt();
                    byte[] kBytes = new byte[kLen];
                    idxBuf.get(kBytes);
                    String key = new String(kBytes, StandardCharsets.UTF_8);
                    long offset = idxBuf.getLong();
                    int size = idxBuf.getInt();
                    this.sparseIndex.addEntry(key, offset, size);
                }
            }
        } catch (IOException e) {
            throw new StorageEngineException("Failed to open SSTable: " + dataPath, e);
        }
    }

    @Override
    public DataRecord get(String key) {
        ensureOpen();
        if (key == null || recordCount == 0) {
            return null;
        }

        // 1. Boundary check
        if (key.compareTo(smallestKey) < 0 || key.compareTo(largestKey) > 0) {
            return null;
        }

        // 2. Bloom filter negative test (O(1))
        if (!bloomFilter.mightContain(key)) {
            return null;
        }

        // 3. Binary search sparse index to locate candidate block
        IndexEntry entry = sparseIndex.search(key);
        if (entry == null) {
            return null;
        }

        // 4. Seek and scan block
        try {
            long blockOffset = entry.getFileOffset();
            long readLength = entry.getBlockSize() > 0
                    ? entry.getBlockSize()
                    : Math.min(65536, dbFileSize - blockOffset);

            if (blockOffset + readLength > dbFileSize) {
                readLength = dbFileSize - blockOffset;
            }
            if (readLength <= 0) {
                return null;
            }

            ByteBuffer blockBuf = ByteBuffer.allocate((int) readLength);
            dbChannel.read(blockBuf, blockOffset);
            blockBuf.flip();

            while (blockBuf.hasRemaining()) {
                if (blockBuf.remaining() < 17) { // 8 + 1 + 4 + 4
                    break;
                }
                long timestamp = blockBuf.getLong();
                byte typeCode = blockBuf.get();
                RecordType type = RecordType.fromCode(typeCode);

                int kLen = blockBuf.getInt();
                if (blockBuf.remaining() < kLen) break;
                byte[] kBytes = new byte[kLen];
                blockBuf.get(kBytes);
                String recKey = new String(kBytes, StandardCharsets.UTF_8);

                int vLen = blockBuf.getInt();
                byte[] vBytes;
                if (vLen > 0) {
                    if (blockBuf.remaining() < vLen) break;
                    vBytes = new byte[vLen];
                    blockBuf.get(vBytes);
                } else {
                    vBytes = new byte[0];
                }

                int cmp = recKey.compareTo(key);
                if (cmp == 0) {
                    return (type == RecordType.DELETE)
                            ? DataRecord.delete(recKey, timestamp)
                            : DataRecord.put(recKey, vBytes, timestamp);
                } else if (cmp > 0) {
                    // Passed alphabetical target; stop scanning
                    return null;
                }
            }
            return null;
        } catch (IOException e) {
            throw new StorageEngineException("Error reading data block from SSTable: " + dataPath, e);
        }
    }

    @Override
    public Iterator<DataRecord> iterator() {
        ensureOpen();
        return new SSTableRecordIterator(0L, null);
    }

    @Override
    public Iterator<DataRecord> rangeScan(String startKey, String endKey) {
        ensureOpen();
        long startOffset = 0L;
        if (startKey != null && !sparseIndex.isEmpty()) {
            IndexEntry entry = sparseIndex.search(startKey);
            if (entry != null) {
                startOffset = entry.getFileOffset();
            }
        }
        return new SSTableRecordIterator(startOffset, endKey, startKey);
    }

    @Override
    public BloomFilter getBloomFilter() {
        return bloomFilter;
    }

    @Override
    public SparseIndex getSparseIndex() {
        return sparseIndex;
    }

    @Override
    public long getRecordCount() {
        return recordCount;
    }

    @Override
    public long getCreatedAt() {
        return createdAt;
    }

    @Override
    public String getSmallestKey() {
        return smallestKey;
    }

    @Override
    public String getLargestKey() {
        return largestKey;
    }

    @Override
    public Path getDataFilePath() {
        return dataPath;
    }

    @Override
    public Path getIndexFilePath() {
        return indexPath;
    }

    @Override
    public long getDiskSizeBytes() {
        try {
            return dbFileSize + Files.size(indexPath);
        } catch (IOException e) {
            return dbFileSize;
        }
    }

    @Override
    public int getLevel() {
        return level;
    }

    @Override
    public void setLevel(int level) {
        this.level = level;
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public boolean isClosed() {
        return isClosed.get();
    }

    private void ensureOpen() {
        if (isClosed.get()) {
            throw new IllegalStateException("SSTable is closed: " + dataPath);
        }
    }

    @Override
    public void close() {
        if (isClosed.compareAndSet(false, true)) {
            try {
                if (dbChannel.isOpen()) {
                    dbChannel.close();
                }
            } catch (IOException e) {
                throw new StorageEngineException("Failed to close SSTable channel: " + dataPath, e);
            }
        }
    }

    /**
     * Inner sequential iterator reading directly from the .db file channel.
     */
    private class SSTableRecordIterator implements Iterator<DataRecord> {
        private final String endKey;
        private final String startKeyFilter;
        private long currentOffset;
        private DataRecord nextRecord;
        private final ByteBuffer readBuffer;

        SSTableRecordIterator(long startOffset, String endKey) {
            this(startOffset, endKey, null);
        }

        SSTableRecordIterator(long startOffset, String endKey, String startKeyFilter) {
            this.currentOffset = startOffset;
            this.endKey = endKey;
            this.startKeyFilter = startKeyFilter;
            this.readBuffer = ByteBuffer.allocate(65536);
            this.readBuffer.flip(); // initially empty
            advance();
        }

        private void advance() {
            nextRecord = null;
            while (nextRecord == null) {
                DataRecord record = readNextRecord();
                if (record == null) {
                    return;
                }
                // Check bounds
                if (startKeyFilter != null && record.getKey().compareTo(startKeyFilter) < 0) {
                    continue;
                }
                if (endKey != null && record.getKey().compareTo(endKey) >= 0) {
                    return;
                }
                nextRecord = record;
            }
        }

        private DataRecord readNextRecord() {
            try {
                // Ensure at least header is buffered (8 + 1 + 4 + 4 = 17 bytes)
                if (readBuffer.remaining() < 17) {
                    refillBuffer();
                    if (readBuffer.remaining() < 17) {
                        return null; // EOF
                    }
                }

                readBuffer.mark();
                long timestamp = readBuffer.getLong();
                byte typeCode = readBuffer.get();
                RecordType type = RecordType.fromCode(typeCode);
                int kLen = readBuffer.getInt();

                // If buffer does not contain full key, reset and refill
                if (readBuffer.remaining() < kLen + 4) {
                    readBuffer.reset();
                    refillBuffer();
                    if (readBuffer.remaining() < 17 + kLen) return null;
                    timestamp = readBuffer.getLong();
                    typeCode = readBuffer.get();
                    type = RecordType.fromCode(typeCode);
                    kLen = readBuffer.getInt();
                }

                byte[] kBytes = new byte[kLen];
                readBuffer.get(kBytes);
                String key = new String(kBytes, StandardCharsets.UTF_8);

                int vLen = readBuffer.getInt();
                if (vLen > 0) {
                    if (readBuffer.remaining() < vLen) {
                        readBuffer.reset();
                        refillBuffer();
                        // re-read all
                        readBuffer.getLong(); // ts
                        readBuffer.get(); // type
                        readBuffer.getInt(); // kLen
                        readBuffer.get(new byte[kLen]); // key
                        readBuffer.getInt(); // vLen
                    }
                    byte[] vBytes = new byte[vLen];
                    readBuffer.get(vBytes);
                    return (type == RecordType.DELETE)
                            ? DataRecord.delete(key, timestamp)
                            : DataRecord.put(key, vBytes, timestamp);
                } else {
                    return (type == RecordType.DELETE)
                            ? DataRecord.delete(key, timestamp)
                            : DataRecord.put(key, new byte[0], timestamp);
                }
            } catch (IOException e) {
                throw new StorageEngineException("Error iterating SSTable data: " + dataPath, e);
            }
        }

        private void refillBuffer() throws IOException {
            readBuffer.compact();
            int bytesRead = dbChannel.read(readBuffer, currentOffset);
            if (bytesRead > 0) {
                currentOffset += bytesRead;
            }
            readBuffer.flip();
        }

        @Override
        public boolean hasNext() {
            return nextRecord != null;
        }

        @Override
        public DataRecord next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            DataRecord res = nextRecord;
            advance();
            return res;
        }
    }
}
