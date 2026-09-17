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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Serializes sorted DataRecords into immutable SSTable pair files (.db and .idx).
 * Employs atomic rename on completion to ensure crash durability and reader isolation.
 */
public class SSTableWriter {
    public static final int MAGIC_HEADER = 0x41504558; // "APEX"
    public static final short SSTABLE_VERSION = 1;

    private final Path dataPath;
    private final Path indexPath;
    private final int sparseInterval;
    private final double bloomFpp;

    public SSTableWriter(Path dataPath, Path indexPath, int sparseInterval, double bloomFpp) {
        this.dataPath = dataPath;
        this.indexPath = indexPath;
        this.sparseInterval = sparseInterval > 0 ? sparseInterval : 16;
        this.bloomFpp = bloomFpp > 0 ? bloomFpp : 0.01;
    }

    /**
     * Writes an ordered collection of records into the SSTable.
     *
     * @param records       lexicographically ordered records
     * @param expectedCount estimated count for Bloom filter sizing
     * @return total bytes written to the data file
     */
    public long write(Iterator<DataRecord> records, int expectedCount) {
        if (!records.hasNext()) {
            throw new IllegalArgumentException("Cannot create an empty SSTable");
        }

        Path tempDbPath = dataPath.resolveSibling(dataPath.getFileName().toString() + ".tmp");
        Path tempIdxPath = indexPath.resolveSibling(indexPath.getFileName().toString() + ".tmp");

        try {
            if (tempDbPath.getParent() != null) {
                Files.createDirectories(tempDbPath.getParent());
            }

            BloomFilter bloomFilter = new BloomFilter(Math.max(expectedCount, 16), bloomFpp);
            List<IndexEntryBuilder> indexBuilders = new ArrayList<>();

            long totalDataBytes = 0;
            int recordCount = 0;
            String smallestKey = null;
            String largestKey = null;
            long createdAt = System.currentTimeMillis();

            // 1. Write Data File (.db)
            try (FileChannel dbChannel = FileChannel.open(
                    tempDbPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {

                IndexEntryBuilder currentBlockBuilder = null;

                while (records.hasNext()) {
                    DataRecord record = records.next();
                    String key = record.getKey();
                    if (smallestKey == null) {
                        smallestKey = key;
                    }
                    largestKey = key;
                    bloomFilter.add(key);

                    // Sparse index interval checkpoint
                    if (recordCount % sparseInterval == 0) {
                        if (currentBlockBuilder != null) {
                            currentBlockBuilder.blockSize = (int) (totalDataBytes - currentBlockBuilder.fileOffset);
                        }
                        currentBlockBuilder = new IndexEntryBuilder(key, totalDataBytes);
                        indexBuilders.add(currentBlockBuilder);
                    }

                    // Serialize record to dbChannel:
                    // [Timestamp: 8B][RecordType: 1B][KeyLen: 4B][KeyBytes][ValLen: 4B][ValBytes]
                    byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                    byte[] valBytes = record.isTombstone() ? new byte[0] : record.getValue();
                    int valLength = record.isTombstone() ? -1 : valBytes.length;

                    int recordBytes = 8 + 1 + 4 + keyBytes.length + 4 + (valLength > 0 ? valLength : 0);
                    ByteBuffer recBuf = ByteBuffer.allocate(recordBytes);
                    recBuf.putLong(record.getTimestamp());
                    recBuf.put(record.getType().getCode());
                    recBuf.putInt(keyBytes.length);
                    recBuf.put(keyBytes);
                    recBuf.putInt(valLength);
                    if (valLength > 0) {
                        recBuf.put(valBytes);
                    }
                    recBuf.flip();

                    while (recBuf.hasRemaining()) {
                        totalDataBytes += dbChannel.write(recBuf);
                    }

                    recordCount++;
                }

                // Finalize the last block builder
                if (currentBlockBuilder != null) {
                    currentBlockBuilder.blockSize = (int) (totalDataBytes - currentBlockBuilder.fileOffset);
                }

                dbChannel.force(true);
            }

            // 2. Write Index File (.idx)
            try (FileChannel idxChannel = FileChannel.open(
                    tempIdxPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {

                byte[] smallestKeyBytes = smallestKey.getBytes(StandardCharsets.UTF_8);
                byte[] largestKeyBytes = largestKey.getBytes(StandardCharsets.UTF_8);

                // Calculate required index file buffer size
                int bloomSizeBytes = bloomFilter.getSerializedSizeBytes();
                int headerSize = 4 + 2 + 8 + 4 + (4 + smallestKeyBytes.length) + (4 + largestKeyBytes.length);
                int sparseIndexSize = 4;
                for (IndexEntryBuilder b : indexBuilders) {
                    sparseIndexSize += 4 + b.key.getBytes(StandardCharsets.UTF_8).length + 8 + 4;
                }

                ByteBuffer idxBuf = ByteBuffer.allocate(headerSize + bloomSizeBytes + sparseIndexSize);

                // Header
                idxBuf.putInt(MAGIC_HEADER);
                idxBuf.putShort(SSTABLE_VERSION);
                idxBuf.putLong(createdAt);
                idxBuf.putInt(recordCount);

                // Smallest and largest keys
                idxBuf.putInt(smallestKeyBytes.length);
                idxBuf.put(smallestKeyBytes);
                idxBuf.putInt(largestKeyBytes.length);
                idxBuf.put(largestKeyBytes);

                // Bloom Filter
                bloomFilter.writeTo(idxBuf);

                // Sparse Index
                idxBuf.putInt(indexBuilders.size());
                for (IndexEntryBuilder b : indexBuilders) {
                    byte[] entryKeyBytes = b.key.getBytes(StandardCharsets.UTF_8);
                    idxBuf.putInt(entryKeyBytes.length);
                    idxBuf.put(entryKeyBytes);
                    idxBuf.putLong(b.fileOffset);
                    idxBuf.putInt(b.blockSize);
                }

                idxBuf.flip();
                while (idxBuf.hasRemaining()) {
                    idxChannel.write(idxBuf);
                }
                idxChannel.force(true);
            }

            // 3. Atomic rename .tmp files to production filenames
            Files.move(tempDbPath, dataPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tempIdxPath, indexPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

            return totalDataBytes;
        } catch (IOException e) {
            // Clean up temporary files if failed
            try {
                Files.deleteIfExists(tempDbPath);
                Files.deleteIfExists(tempIdxPath);
            } catch (IOException ignored) {}
            throw new StorageEngineException("Failed to write SSTable files: " + dataPath, e);
        }
    }

    private static class IndexEntryBuilder {
        final String key;
        final long fileOffset;
        int blockSize;

        IndexEntryBuilder(String key, long fileOffset) {
            this.key = key;
            this.fileOffset = fileOffset;
            this.blockSize = 0;
        }
    }
}
