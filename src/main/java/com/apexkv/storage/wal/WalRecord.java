package com.apexkv.storage.wal;

import com.apexkv.core.DataRecord;
import com.apexkv.core.RecordType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Structured Write-Ahead Log (WAL) entry conforming to the binary specification:
 * [CRC-32 (4B) | Timestamp (8B) | Record Type (1B) | Key Length (4B) | Key Bytes | Value Length (4B) | Value Bytes].
 */
public final class WalRecord {
    public static final int HEADER_SIZE_EXCLUDING_PAYLOAD = 4 + 8 + 1 + 4 + 4; // 21 bytes minimum header

    private final long crc;
    private final long timestamp;
    private final RecordType recordType;
    private final String key;
    private final byte[] value;

    public WalRecord(long crc, long timestamp, RecordType recordType, String key, byte[] value) {
        this.crc = crc & 0xFFFFFFFFL;
        this.timestamp = timestamp;
        this.recordType = Objects.requireNonNull(recordType, "recordType cannot be null");
        this.key = Objects.requireNonNull(key, "key cannot be null");
        this.value = (value == null || recordType == RecordType.DELETE) ? new byte[0] : Arrays.copyOf(value, value.length);
    }

    /**
     * Constructs a WalRecord from a DataRecord, automatically computing its CRC32 checksum.
     */
    public static WalRecord fromDataRecord(DataRecord record) {
        byte[] keyBytes = record.getKey().getBytes(StandardCharsets.UTF_8);
        byte[] valBytes = record.isTombstone() ? new byte[0] : record.getValue();
        int valLength = record.isTombstone() ? -1 : valBytes.length;

        int payloadSize = 8 + 1 + 4 + keyBytes.length + 4 + (valLength > 0 ? valLength : 0);
        ByteBuffer payloadBuf = ByteBuffer.allocate(payloadSize);
        payloadBuf.putLong(record.getTimestamp());
        payloadBuf.put(record.getType().getCode());
        payloadBuf.putInt(keyBytes.length);
        payloadBuf.put(keyBytes);
        payloadBuf.putInt(valLength);
        if (valLength > 0) {
            payloadBuf.put(valBytes);
        }
        payloadBuf.flip();

        long crc = Crc32Checksum.compute(payloadBuf);
        return new WalRecord(crc, record.getTimestamp(), record.getType(), record.getKey(), valBytes);
    }

    /**
     * Serializes this WAL record into a binary ByteBuffer ready for writing to disk.
     */
    public ByteBuffer serialize() {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int valLength = (recordType == RecordType.DELETE) ? -1 : value.length;
        int totalSize = 4 + 8 + 1 + 4 + keyBytes.length + 4 + (valLength > 0 ? valLength : 0);

        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.putInt((int) (crc & 0xFFFFFFFFL));
        buf.putLong(timestamp);
        buf.put(recordType.getCode());
        buf.putInt(keyBytes.length);
        buf.put(keyBytes);
        buf.putInt(valLength);
        if (valLength > 0) {
            buf.put(value);
        }
        buf.flip();
        return buf;
    }

    /**
     * Deserializes a WalRecord from the provided ByteBuffer.
     * Expects buffer position to be at the start of a valid record.
     */
    public static WalRecord deserialize(ByteBuffer buffer) {
        if (buffer.remaining() < 21) {
            throw new IllegalArgumentException("Insufficient bytes for WAL header");
        }

        long recordedCrc = buffer.getInt() & 0xFFFFFFFFL;
        int payloadStart = buffer.position();

        long timestamp = buffer.getLong();
        byte typeCode = buffer.get();
        RecordType type = RecordType.fromCode(typeCode);

        int keyLen = buffer.getInt();
        if (keyLen < 0 || buffer.remaining() < keyLen) {
            throw new IllegalArgumentException("Corrupted key length in WAL record: " + keyLen);
        }
        byte[] keyBytes = new byte[keyLen];
        buffer.get(keyBytes);
        String key = new String(keyBytes, StandardCharsets.UTF_8);

        int valLen = buffer.getInt();
        byte[] valBytes;
        if (valLen > 0) {
            if (buffer.remaining() < valLen) {
                throw new IllegalArgumentException("Corrupted value length in WAL record: " + valLen);
            }
            valBytes = new byte[valLen];
            buffer.get(valBytes);
        } else {
            valBytes = new byte[0];
        }

        int payloadEnd = buffer.position();

        // Validate CRC
        ByteBuffer payloadSlice = buffer.duplicate();
        payloadSlice.position(payloadStart);
        payloadSlice.limit(payloadEnd);
        long computedCrc = Crc32Checksum.compute(payloadSlice);

        if (recordedCrc != computedCrc) {
            throw new IllegalArgumentException(String.format(
                    "WAL record CRC mismatch: recorded=0x%08X, computed=0x%08X for key '%s'",
                    recordedCrc, computedCrc, key));
        }

        return new WalRecord(recordedCrc, timestamp, type, key, valBytes);
    }

    public DataRecord toDataRecord() {
        if (recordType == RecordType.DELETE) {
            return DataRecord.delete(key, timestamp);
        } else {
            return DataRecord.put(key, value, timestamp);
        }
    }

    public long getCrc() {
        return crc;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public RecordType getRecordType() {
        return recordType;
    }

    public String getKey() {
        return key;
    }

    public byte[] getValue() {
        return Arrays.copyOf(value, value.length);
    }

    public int getSerializedSize() {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int valLength = (recordType == RecordType.DELETE) ? -1 : value.length;
        return 4 + 8 + 1 + 4 + keyBytes.length + 4 + (valLength > 0 ? valLength : 0);
    }
}
