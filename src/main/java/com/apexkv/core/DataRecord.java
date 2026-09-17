package com.apexkv.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable fundamental representation of a key-value mutation within ApexKV.
 * Encapsulates the user key, value payload, timestamp for versioning, and operation type.
 */
public final class DataRecord implements Comparable<DataRecord> {
    private static final byte[] EMPTY_BYTES = new byte[0];

    private final String key;
    private final byte[] value;
    private final long timestamp;
    private final RecordType type;

    /**
     * Primary constructor.
     *
     * @param key       the non-null, non-empty key
     * @param value     the value payload (may be null or empty for tombstones)
     * @param timestamp epoch millisecond timestamp of mutation
     * @param type      the operation type (PUT or DELETE)
     */
    public DataRecord(String key, byte[] value, long timestamp, RecordType type) {
        this.key = Objects.requireNonNull(key, "DataRecord key cannot be null");
        if (key.isEmpty()) {
            throw new IllegalArgumentException("DataRecord key cannot be empty");
        }
        this.type = Objects.requireNonNull(type, "DataRecord type cannot be null");
        this.timestamp = timestamp;
        if (value == null || type == RecordType.DELETE) {
            this.value = EMPTY_BYTES;
        } else {
            this.value = Arrays.copyOf(value, value.length);
        }
    }

    /**
     * Factory method for creating an active PUT mutation with an explicit timestamp.
     */
    public static DataRecord put(String key, byte[] value, long timestamp) {
        return new DataRecord(key, value, timestamp, RecordType.PUT);
    }

    /**
     * Factory method for creating an active PUT mutation with the current system time.
     */
    public static DataRecord put(String key, byte[] value) {
        return new DataRecord(key, value, System.currentTimeMillis(), RecordType.PUT);
    }

    /**
     * Factory method for creating an active PUT mutation from a String value.
     */
    public static DataRecord put(String key, String value) {
        byte[] bytes = value != null ? value.getBytes(StandardCharsets.UTF_8) : EMPTY_BYTES;
        return new DataRecord(key, bytes, System.currentTimeMillis(), RecordType.PUT);
    }

    /**
     * Factory method for creating a DELETE tombstone record with an explicit timestamp.
     */
    public static DataRecord delete(String key, long timestamp) {
        return new DataRecord(key, EMPTY_BYTES, timestamp, RecordType.DELETE);
    }

    /**
     * Factory method for creating a DELETE tombstone record with the current system time.
     */
    public static DataRecord delete(String key) {
        return new DataRecord(key, EMPTY_BYTES, System.currentTimeMillis(), RecordType.DELETE);
    }

    public String getKey() {
        return key;
    }

    /**
     * Returns a defensive copy of the value byte array.
     */
    public byte[] getValue() {
        return Arrays.copyOf(value, value.length);
    }

    /**
     * Decodes the raw value bytes as a UTF-8 String.
     */
    public String getValueAsString() {
        if (isTombstone()) {
            return null;
        }
        return new String(value, StandardCharsets.UTF_8);
    }

    public long getTimestamp() {
        return timestamp;
    }

    public RecordType getType() {
        return type;
    }

    /**
     * Returns true if this record represents a deletion tombstone.
     */
    public boolean isTombstone() {
        return type == RecordType.DELETE;
    }

    /**
     * Estimates in-memory heap footprint in bytes for MemTable threshold monitoring.
     * Includes object headers, string byte overhead, reference pointers, and raw payload.
     */
    public int estimatedByteSize() {
        // Base object header (16B) + 4 references/primitives (key ref 8B, val ref 8B, timestamp 8B, type ref 8B) = ~40B
        // String overhead (~24B + characters * 2B or UTF-8 bytes)
        // Byte array overhead (~24B + payload length)
        int keyBytes = key.getBytes(StandardCharsets.UTF_8).length;
        int valueBytes = value.length;
        return 64 + keyBytes + valueBytes;
    }

    /**
     * Compares two DataRecords.
     * Records are sorted lexicographically by key in ascending order.
     * When keys are identical, records are sorted by timestamp in descending order
     * so that the freshest mutation appears first.
     */
    @Override
    public int compareTo(DataRecord other) {
        int keyComparison = this.key.compareTo(other.key);
        if (keyComparison != 0) {
            return keyComparison;
        }
        // Newer timestamps first
        return Long.compare(other.timestamp, this.timestamp);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DataRecord that)) return false;
        return timestamp == that.timestamp &&
                Objects.equals(key, that.key) &&
                Arrays.equals(value, that.value) &&
                type == that.type;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(key, timestamp, type);
        result = 31 * result + Arrays.hashCode(value);
        return result;
    }

    @Override
    public String toString() {
        return "DataRecord{" +
                "key='" + key + '\'' +
                ", type=" + type +
                ", timestamp=" + timestamp +
                ", valueLength=" + value.length +
                (type == RecordType.DELETE ? " [TOMBSTONE]" : "") +
                '}';
    }
}
