package com.apexkv.storage.filter;

import java.nio.charset.StandardCharsets;

/**
 * 32-bit MurmurHash3 non-cryptographic hash function implementation in pure Java.
 * Provides uniform distribution and high avalanche characteristics for the Bloom filter.
 */
public final class MurmurHash3 {

    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;
    private static final int R1 = 15;
    private static final int R2 = 13;
    private static final int M = 5;
    private static final int N = 0xe6546b64;

    private MurmurHash3() {
        // Utility class
    }

    /**
     * Hashes a slice of a byte array using 32-bit Murmur3 algorithm.
     *
     * @param data   input byte array
     * @param offset start offset
     * @param length number of bytes
     * @param seed   randomization seed
     * @return 32-bit integer hash code
     */
    public static int hash32(byte[] data, int offset, int length, int seed) {
        int hash = seed;
        final int nblocks = length >> 2;

        // Body: Process 4-byte chunks
        for (int i = 0; i < nblocks; i++) {
            int index = offset + (i << 2);
            int k = (data[index] & 0xFF)
                    | ((data[index + 1] & 0xFF) << 8)
                    | ((data[index + 2] & 0xFF) << 16)
                    | ((data[index + 3] & 0xFF) << 24);

            k *= C1;
            k = Integer.rotateLeft(k, R1);
            k *= C2;

            hash ^= k;
            hash = Integer.rotateLeft(hash, R2);
            hash = hash * M + N;
        }

        // Tail: Process remaining 1..3 bytes
        int tailIndex = offset + (nblocks << 2);
        int k1 = 0;
        switch (length & 3) {
            case 3:
                k1 ^= (data[tailIndex + 2] & 0xFF) << 16;
                // fall through
            case 2:
                k1 ^= (data[tailIndex + 1] & 0xFF) << 8;
                // fall through
            case 1:
                k1 ^= (data[tailIndex] & 0xFF);
                k1 *= C1;
                k1 = Integer.rotateLeft(k1, R1);
                k1 *= C2;
                hash ^= k1;
                break;
            default:
                break;
        }

        // Finalization mix
        hash ^= length;
        hash ^= (hash >>> 16);
        hash *= 0x85ebca6b;
        hash ^= (hash >>> 13);
        hash *= 0xc2b2ae35;
        hash ^= (hash >>> 16);

        return hash;
    }

    /**
     * Hashes a string using standard UTF-8 encoding and default seed 0.
     */
    public static int hash32(String text) {
        if (text == null) return 0;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return hash32(bytes, 0, bytes.length, 0);
    }

    /**
     * Hashes a byte array with an explicit seed.
     */
    public static int hash32(byte[] data, int seed) {
        return hash32(data, 0, data.length, seed);
    }
}
