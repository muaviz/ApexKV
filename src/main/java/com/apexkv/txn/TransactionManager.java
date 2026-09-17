package com.apexkv.txn;

import com.apexkv.core.ApexKVEngine;
import com.apexkv.core.DataRecord;
import com.apexkv.exception.TransactionConflictException;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optimistic Concurrency Control (OCC) Transaction Manager for ApexKV.
 * Tracks active transaction lifecycles and coordinates conflict detection
 * before atomic writes are committed to the underlying engine.
 */
public class TransactionManager {
    private final ApexKVEngine engine;
    private final AtomicLong nextTxnId;
    private final AtomicLong logicalSequence;
    private final ConcurrentHashMap<String, Long> lastCommittedTimestamps;

    public TransactionManager(ApexKVEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine cannot be null");
        this.nextTxnId = new AtomicLong(1);
        this.logicalSequence = new AtomicLong(1);
        this.lastCommittedTimestamps = new ConcurrentHashMap<>();
    }

    /**
     * Begins a new transaction under Snapshot Isolation.
     */
    public Transaction beginTransaction() {
        return beginTransaction(IsolationLevel.SNAPSHOT_ISOLATION);
    }

    /**
     * Begins a new transaction with an explicit isolation level.
     */
    public Transaction beginTransaction(IsolationLevel isolationLevel) {
        long txnId = nextTxnId.getAndIncrement();
        long startTimestamp = logicalSequence.get();
        return new TransactionImpl(txnId, startTimestamp, isolationLevel, engine, this);
    }

    /**
     * Atomically validates and commits transaction write buffer.
     */
    public synchronized void commit(TransactionImpl txn) {
        if (!txn.isActive()) {
            throw new IllegalStateException("Transaction " + txn.getTransactionId() + " is already closed");
        }

        Map<String, DataRecord> writeBuffer = txn.getWriteBuffer();
        if (writeBuffer.isEmpty()) {
            txn.markCommitted();
            return;
        }

        // Validate Optimistic Concurrency:
        // Ensure no committed write has occurred to any read or written key since txn start
        Set<String> readKeys = txn.getReadSet();
        long startTimestamp = txn.getStartTimestamp();

        if (txn.getIsolationLevel() == IsolationLevel.SNAPSHOT_ISOLATION) {
            for (String readKey : readKeys) {
                Long lastCommit = lastCommittedTimestamps.get(readKey);
                if (lastCommit != null && lastCommit > startTimestamp) {
                    txn.markAborted();
                    throw new TransactionConflictException(txn.getTransactionId(), readKey);
                }
            }
        }

        // Validate write set against concurrent modifications
        for (String writeKey : writeBuffer.keySet()) {
            Long lastCommit = lastCommittedTimestamps.get(writeKey);
            if (lastCommit != null && lastCommit > startTimestamp) {
                txn.markAborted();
                throw new TransactionConflictException(txn.getTransactionId(), writeKey);
            }
        }

        // Apply mutations atomically to engine with monotonic commit sequence
        long commitTimestamp = logicalSequence.incrementAndGet();
        for (DataRecord record : writeBuffer.values()) {
            if (record.isTombstone()) {
                engine.delete(record.getKey());
            } else {
                engine.put(record.getKey(), record.getValue());
            }
            lastCommittedTimestamps.put(record.getKey(), commitTimestamp);
        }

        txn.markCommitted();
    }

    /**
     * Rolls back a transaction, discarding its private write buffer.
     */
    public void rollback(TransactionImpl txn) {
        txn.markAborted();
    }

    public ApexKVEngine getEngine() {
        return engine;
    }
}
