package com.apexkv.storage.wal;

import com.apexkv.core.DataRecord;
import com.apexkv.core.RecordType;
import com.apexkv.exception.CorruptedWalException;
import com.apexkv.exception.StorageEngineException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Sequential reader for Write-Ahead Log files. Used during database boot and crash recovery
 * to replay un-flushed mutations and verify cryptographic/checksum integrity.
 */
public class WalReader implements AutoCloseable {
    private final Path logPath;
    private final FileChannel channel;
    private final boolean allowTornTail;

    public WalReader(Path logPath) {
        this(logPath, true);
    }

    public WalReader(Path logPath, boolean allowTornTail) {
        this.logPath = Objects.requireNonNull(logPath, "logPath cannot be null");
        this.allowTornTail = allowTornTail;

        try {
            if (!Files.exists(logPath)) {
                throw new StorageEngineException("WAL file not found: " + logPath);
            }
            this.channel = FileChannel.open(logPath, StandardOpenOption.READ);
        } catch (IOException e) {
            throw new StorageEngineException("Failed to open WAL reader for: " + logPath, e);
        }
    }

    /**
     * Reads and replays all records from the WAL in sequential order.
     *
     * @return an ordered list of recovered DataRecords
     * @throws CorruptedWalException if CRC verification fails on a complete record
     */
    public List<DataRecord> readAll() {
        List<DataRecord> records = new ArrayList<>();
        try {
            long fileSize = channel.size();
            if (fileSize == 0) {
                return Collections.emptyList();
            }

            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(fileSize, 64 * 1024 * 1024)); // up to 64MB buffer
            channel.position(0);

            long currentOffset = 0;
            while (currentOffset < fileSize) {
                // Minimum header is 4B(CRC) + 8B(time) + 1B(type) + 4B(keyLen) + 4B(valLen) = 21B
                if (fileSize - currentOffset < 21) {
                    if (allowTornTail) {
                        break; // Torn write at EOF
                    } else {
                        throw new CorruptedWalException("Incomplete WAL header at offset " + currentOffset, -1, -1, currentOffset);
                    }
                }

                // Read header to determine record size
                ByteBuffer headerBuf = ByteBuffer.allocate(21);
                channel.read(headerBuf, currentOffset);
                headerBuf.flip();

                long expectedCrc = headerBuf.getInt() & 0xFFFFFFFFL;
                long timestamp = headerBuf.getLong();
                byte typeCode = headerBuf.get();
                int keyLen = headerBuf.getInt();
                int valLen = headerBuf.getInt();

                if (keyLen <= 0 || keyLen > 65536 || valLen > 64 * 1024 * 1024) {
                    if (allowTornTail && currentOffset + 21 >= fileSize) {
                        break;
                    }
                    throw new CorruptedWalException(
                            String.format("Invalid key/value length in WAL: keyLen=%d, valLen=%d", keyLen, valLen),
                            expectedCrc, -1, currentOffset);
                }

                int effectiveValLen = Math.max(0, valLen);
                int payloadSize = 8 + 1 + 4 + keyLen + 4 + effectiveValLen;
                int totalRecordSize = 4 + payloadSize;

                if (currentOffset + totalRecordSize > fileSize) {
                    if (allowTornTail) {
                        break; // Incomplete torn record at EOF
                    } else {
                        throw new CorruptedWalException(
                                "Torn record payload detected at EOF offset " + currentOffset,
                                expectedCrc, -1, currentOffset);
                    }
                }

                // Read entire record payload for CRC verification and extraction
                ByteBuffer recordBuf = ByteBuffer.allocate(totalRecordSize);
                channel.read(recordBuf, currentOffset);
                recordBuf.flip();

                // Advance past CRC to measure payload CRC
                recordBuf.getInt(); // skip CRC int
                int payloadStart = recordBuf.position();
                long computedCrc = Crc32Checksum.compute(recordBuf);

                if (expectedCrc != computedCrc) {
                    throw new CorruptedWalException(
                            "CRC32 mismatch on WAL record at offset " + currentOffset,
                            expectedCrc, computedCrc, currentOffset);
                }

                // Parse fields
                recordBuf.position(payloadStart);
                long recordTs = recordBuf.getLong();
                byte recordTypeByte = recordBuf.get();
                RecordType recordType = RecordType.fromCode(recordTypeByte);

                int kLen = recordBuf.getInt();
                byte[] kBytes = new byte[kLen];
                recordBuf.get(kBytes);
                String key = new String(kBytes, StandardCharsets.UTF_8);

                int vLen = recordBuf.getInt();
                byte[] vBytes = new byte[Math.max(0, vLen)];
                if (vLen > 0) {
                    recordBuf.get(vBytes);
                }

                DataRecord record = (recordType == RecordType.DELETE)
                        ? DataRecord.delete(key, recordTs)
                        : DataRecord.put(key, vBytes, recordTs);

                records.add(record);
                currentOffset += totalRecordSize;
            }

            return records;
        } catch (IOException e) {
            throw new StorageEngineException("Failed to read WAL records from: " + logPath, e);
        }
    }

    @Override
    public void close() {
        try {
            if (channel.isOpen()) {
                channel.close();
            }
        } catch (IOException e) {
            throw new StorageEngineException("Failed to close WAL reader channel: " + logPath, e);
        }
    }
}
