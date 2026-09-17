package com.apexkv.exception;

/**
 * Exception thrown when an Optimistic Concurrency Control (OCC) conflict is detected
 * during transaction commit due to concurrent modifications of keys in the transaction's
 * read or write set.
 */
public class TransactionConflictException extends ApexKVException {
    private static final long serialVersionUID = 1L;

    private final long transactionId;
    private final String conflictingKey;

    public TransactionConflictException(long transactionId, String conflictingKey) {
        super(String.format("Transaction %d aborted due to write conflict on key: '%s'",
                transactionId, conflictingKey));
        this.transactionId = transactionId;
        this.conflictingKey = conflictingKey;
    }

    public TransactionConflictException(String message) {
        super(message);
        this.transactionId = -1L;
        this.conflictingKey = null;
    }

    public long getTransactionId() {
        return transactionId;
    }

    public String getConflictingKey() {
        return conflictingKey;
    }
}
