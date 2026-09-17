package com.apexkv.exception;

/**
 * Exception thrown when CRC32 checksum validation fails during Write-Ahead Log (WAL)
 * replay or file integrity verification, indicating torn writes or data corruption.
 */
public class CorruptedWalException extends ApexKVException {
    private static final long serialVersionUID = 1L;

    private final long expectedCrc;
    private final long computedCrc;
    private final long fileOffset;

    public CorruptedWalException(String message) {
        super(message);
        this.expectedCrc = -1L;
        this.computedCrc = -1L;
        this.fileOffset = -1L;
    }

    public CorruptedWalException(String message, long expectedCrc, long computedCrc, long fileOffset) {
        super(String.format("%s [Offset: %d, Expected CRC: 0x%08X, Computed CRC: 0x%08X]",
                message, fileOffset, expectedCrc, computedCrc));
        this.expectedCrc = expectedCrc;
        this.computedCrc = computedCrc;
        this.fileOffset = fileOffset;
    }

    public long getExpectedCrc() {
        return expectedCrc;
    }

    public long getComputedCrc() {
        return computedCrc;
    }

    public long getFileOffset() {
        return fileOffset;
    }
}
