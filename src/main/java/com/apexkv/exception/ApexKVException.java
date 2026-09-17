package com.apexkv.exception;

/**
 * Root unchecked exception for all runtime errors emitted by the ApexKV storage engine.
 */
public class ApexKVException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ApexKVException(String message) {
        super(message);
    }

    public ApexKVException(String message, Throwable cause) {
        super(message, cause);
    }

    public ApexKVException(Throwable cause) {
        super(cause);
    }
}
