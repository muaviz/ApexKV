package com.apexkv.exception;

/**
 * Exception thrown when low-level storage I/O, file channel operations,
 * or filesystem mutations fail.
 */
public class StorageEngineException extends ApexKVException {
    private static final long serialVersionUID = 1L;

    public StorageEngineException(String message) {
        super(message);
    }

    public StorageEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
