package com.apexkv.core;

/**
 * Enumeration representing the mutation operation types stored within the LSM-Tree
 * and Write-Ahead Log records.
 */
public enum RecordType {
    /**
     * An insert or update mutation storing a key and its associated value payload.
     */
    PUT((byte) 0x01),

    /**
     * A deletion marker (tombstone) signifying that the key has been invalidated
     * and should be masked across all historical SSTables.
     */
    DELETE((byte) 0x02);

    private final byte code;

    RecordType(byte code) {
        this.code = code;
    }

    /**
     * Returns the 1-byte wire protocol representation for this record type.
     *
     * @return the byte identifier
     */
    public byte getCode() {
        return code;
    }

    /**
     * Decodes a byte code into its corresponding RecordType enum constant.
     *
     * @param code the byte code read from binary storage
     * @return the corresponding RecordType
     * @throws IllegalArgumentException if the code is unrecognized
     */
    public static RecordType fromCode(byte code) {
        return switch (code) {
            case 0x01 -> PUT;
            case 0x02 -> DELETE;
            default -> throw new IllegalArgumentException(
                    String.format("Unrecognized RecordType byte code: 0x%02X", code));
        };
    }
}
