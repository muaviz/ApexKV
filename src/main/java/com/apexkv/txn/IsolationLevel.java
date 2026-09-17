package com.apexkv.txn;

/**
 * Supported transaction isolation levels in ApexKV.
 */
public enum IsolationLevel {
    /**
     * Snapshot Isolation: Transactions observe a consistent point-in-time snapshot
     * of committed data and detect write-write conflicts at commit time.
     */
    SNAPSHOT_ISOLATION,

    /**
     * Read Committed: Transactions observe only committed data but allow non-repeatable reads.
     */
    READ_COMMITTED
}
