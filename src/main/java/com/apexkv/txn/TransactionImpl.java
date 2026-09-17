package com.apexkv.txn;

import com.apexkv.core.ApexKVEngine;
import com.apexkv.core.DataRecord;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Concrete transaction context managing private read/write buffers
 * for snapshot isolation and optimistic concurrency control.
 */
public class TransactionImpl implements Transaction {
    private final long transactionId;
    private final long startTimestamp;
    private final IsolationLevel isolationLevel;
    private final ApexKVEngine engine;
    private final TransactionManager manager;

    private final Map<String, DataRecord> writeBuffer;
    private final Set<String> readSet;
    private final AtomicBoolean isActive;

    public TransactionImpl(long transactionId, long startTimestamp, IsolationLevel isolationLevel,
                           ApexKVEngine engine, TransactionManager manager) {
        this.transactionId = transactionId;
        this.startTimestamp = startTimestamp;
        this.isolationLevel = Objects.requireNonNull(isolationLevel, "isolationLevel cannot be null");
        this.engine = Objects.requireNonNull(engine, "engine cannot be null");
        this.manager = Objects.requireNonNull(manager, "manager cannot be null");

        this.writeBuffer = new LinkedHashMap<>();
        this.readSet = new HashSet<>();
        this.isActive = new AtomicBoolean(true);
    }

    @Override
    public void put(String key, byte[] value) {
        ensureActive();
        Objects.requireNonNull(key, "key cannot be null");
        writeBuffer.put(key, DataRecord.put(key, value, System.currentTimeMillis()));
    }

    @Override
    public byte[] get(String key) {
        ensureActive();
        Objects.requireNonNull(key, "key cannot be null");

        // 1. Read-your-own-writes from transaction local write buffer
        DataRecord local = writeBuffer.get(key);
        if (local != null) {
            return local.isTombstone() ? null : local.getValue();
        }

        // 2. Track read key for OCC conflict detection
        readSet.add(key);

        // 3. Read from storage engine snapshot
        return engine.get(key);
    }

    @Override
    public void delete(String key) {
        ensureActive();
        Objects.requireNonNull(key, "key cannot be null");
        writeBuffer.put(key, DataRecord.delete(key, System.currentTimeMillis()));
    }

    @Override
    public void commit() {
        manager.commit(this);
    }

    @Override
    public void rollback() {
        manager.rollback(this);
    }

    @Override
    public long getTransactionId() {
        return transactionId;
    }

    @Override
    public boolean isActive() {
        return isActive.get();
    }

    @Override
    public IsolationLevel getIsolationLevel() {
        return isolationLevel;
    }

    public long getStartTimestamp() {
        return startTimestamp;
    }

    public Map<String, DataRecord> getWriteBuffer() {
        return Collections.unmodifiableMap(writeBuffer);
    }

    public Set<String> getReadSet() {
        return Collections.unmodifiableSet(readSet);
    }

    void markCommitted() {
        isActive.set(false);
        writeBuffer.clear();
    }

    void markAborted() {
        isActive.set(false);
        writeBuffer.clear();
    }

    private void ensureActive() {
        if (!isActive.get()) {
            throw new IllegalStateException("Transaction " + transactionId + " is no longer active");
        }
    }
}
