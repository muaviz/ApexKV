package com.apexkv.storage.wal;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/**
 * Utility wrapper around {@link CRC32} providing fast bitwise data integrity hashing
 * for WAL records and SSTable binary framing.
 */
public final class Crc32Checksum {

    private Crc32Checksum() {
        // Utility class
    }

    /**
     * Computes the CRC32 checksum of the given byte array slice.
     *
     * @param bytes  the byte array
     * @param offset starting offset
     * @param length number of bytes
     * @return 32-bit unsigned CRC value as a long
     */
    public static long compute(byte[] bytes, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(bytes, offset, length);
        return crc.getValue();
    }

    /**
     * Computes the CRC32 checksum of an entire byte array.
     */
    public static long compute(byte[] bytes) {
        return compute(bytes, 0, bytes.length);
    }

    /**
     * Computes the CRC32 checksum of the remaining bytes in a ByteBuffer without modifying its position.
     */
    public static long compute(ByteBuffer buffer) {
        CRC32 crc = new CRC32();
        ByteBuffer duplicate = buffer.duplicate();
        crc.update(duplicate);
        return crc.getValue();
    }
}
