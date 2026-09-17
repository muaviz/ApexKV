package com.apexkv.txn;

import java.nio.charset.StandardCharsets;

/**
 * Client interface for atomic, isolated transactions in ApexKV.
 */
public interface Transaction extends AutoCloseable {

    /**
     * Buffers a write mutation within this transaction.
     */
    void put(String key, byte[] value);

    /**
     * Convenience method to buffer a string value.
     */
    default void put(String key, String value) {
        byte[] bytes = value != null ? value.getBytes(StandardCharsets.UTF_8) : new byte[0];
        put(key, bytes);
    }

    /**
     * Retrieves the value for a key, prioritizing uncommitted writes within this transaction,
     * then falling back to the engine snapshot.
     */
    byte[] get(String key);

    /**
     * Convenience method to retrieve a UTF-8 string value.
     */
    default String getAsString(String key) {
        byte[] bytes = get(key);
        return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
    }

    /**
     * Buffers a deletion tombstone for the specified key within this transaction.
     */
    void delete(String key);

    /**
     * Atomically validates and commits all buffered writes to the storage engine.
     * Throws {@link com.apexkv.exception.TransactionConflictException} if concurrent conflict occurred.
     */
    void commit();

    /**
     * Discards all buffered writes and aborts this transaction.
     */
    void rollback();

    long getTransactionId();

    boolean isActive();

    IsolationLevel getIsolationLevel();

    @Override
    default void close() {
        if (isActive()) {
            rollback();
        }
    }
}
