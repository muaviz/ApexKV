package com.apexkv.core;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * Primary interface defining the core API contracts for the ApexKV storage engine.
 * Extends {@link AutoCloseable} for safe lifecycle management and deterministic resource cleanup.
 */
public interface StorageEngine extends AutoCloseable {

    /**
     * Inserts or updates the value associated with the specified key.
     *
     * @param key   non-null, non-empty key string
     * @param value byte array payload
     */
    void put(String key, byte[] value);

    /**
     * Convenience method to insert or update a key with a UTF-8 string value.
     *
     * @param key   non-null, non-empty key string
     * @param value string payload (converted to UTF-8 bytes)
     */
    default void put(String key, String value) {
        byte[] bytes = value != null ? value.getBytes(StandardCharsets.UTF_8) : new byte[0];
        put(key, bytes);
    }

    /**
     * Retrieves the value associated with the specified key.
     *
     * @param key non-null key string
     * @return the raw byte array value, or {@code null} if the key does not exist or was deleted
     */
    byte[] get(String key);

    /**
     * Convenience method to retrieve the value associated with the key as a UTF-8 String.
     *
     * @param key non-null key string
     * @return the string value, or {@code null} if the key does not exist or was deleted
     */
    default String getAsString(String key) {
        byte[] bytes = get(key);
        return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
    }

    /**
     * Logically deletes the record associated with the specified key by writing a tombstone.
     *
     * @param key non-null key string
     */
    void delete(String key);

    /**
     * Performs a range scan over keys in the half-open interval [startKey, endKey).
     * If startKey is null, scan starts from the smallest key.
     * If endKey is null, scan continues until the largest key.
     *
     * @param startKey inclusive start boundary (or null)
     * @param endKey   exclusive end boundary (or null)
     * @return an iterator over sorted, non-tombstone records
     */
    Iterator<DataRecord> scan(String startKey, String endKey);

    /**
     * Synchronously flushes the active in-memory MemTable to an immutable Level 0 SSTable on disk.
     */
    void flush();

    /**
     * Manually triggers a background compaction pass across SSTables.
     */
    void compact();

    /**
     * Returns the live observability and performance metrics.
     */
    EngineStats getStats();

    /**
     * Returns the engine configuration parameters.
     */
    ApexKVConfig getConfig();

    /**
     * Checks if the storage engine has been shutdown.
     */
    boolean isClosed();

    /**
     * Gracefully shuts down the storage engine, flushing active buffers, closing
     * open file channels, and halting background compaction daemons.
     */
    @Override
    void close();
}
