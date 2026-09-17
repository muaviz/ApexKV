package com.apexkv.storage.filter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Probabilistic bit-vector Bloom filter for fast negative-lookup pruning in SSTables.
 * Uses Kirsch-Mitzenmacher double-hashing technique with MurmurHash3 to generate
 * k hash locations from 2 base hash values.
 */
public class BloomFilter {
    private static final int SEED_1 = 0x9747b28c;
    private static final int SEED_2 = 0x1b873593;

    private final int k;           // Number of hash functions
    private final int m;           // Total bit array capacity
    private final long[] bitSet;   // 64-bit words backing the bit vector

    /**
     * Constructs an empty BloomFilter dimensioned for expected items and target false-positive rate.
     *
     * @param expectedInsertions estimated number of distinct keys
     * @param fpp                target false positive probability (e.g. 0.01 for 1%)
     */
    public BloomFilter(int expectedInsertions, double fpp) {
        if (expectedInsertions <= 0) {
            expectedInsertions = 1;
        }
        if (fpp <= 0.0 || fpp >= 1.0) {
            fpp = 0.01;
        }

        // m = ceil(-n * ln(p) / (ln 2)^2)
        double ln2Squared = 0.4804530139182014;
        double numBits = (-expectedInsertions * Math.log(fpp)) / ln2Squared;
        int bits = (int) Math.max(64, Math.ceil(numBits));

        // Align bits to multiples of 64
        int words = (bits + 63) / 64;
        this.m = words * 64;
        this.bitSet = new long[words];

        // k = round((m / n) * ln 2)
        int calculatedK = (int) Math.round(((double) this.m / expectedInsertions) * 0.6931471805599453);
        this.k = Math.max(1, Math.min(calculatedK, 30));
    }

    /**
     * Internal constructor for deserialization.
     */
    public BloomFilter(int k, int m, long[] bitSet) {
        this.k = k;
        this.m = m;
        this.bitSet = bitSet;
    }

    /**
     * Adds a key to the filter.
     */
    public void add(String key) {
        Objects.requireNonNull(key, "key cannot be null");
        add(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Adds raw key bytes to the filter.
     */
    public void add(byte[] keyBytes) {
        Objects.requireNonNull(keyBytes, "keyBytes cannot be null");
        int h1 = MurmurHash3.hash32(keyBytes, 0, keyBytes.length, SEED_1);
        int h2 = MurmurHash3.hash32(keyBytes, 0, keyBytes.length, SEED_2);

        for (int i = 0; i < k; i++) {
            int combinedHash = h1 + i * h2;
            int bitIndex = Math.abs(combinedHash % m);
            setBit(bitIndex);
        }
    }

    /**
     * Tests whether a key might exist in the filter.
     * Guaranteed: If this method returns false, the key was definitely NOT inserted (zero false negatives).
     *
     * @param key target key
     * @return true if the key might be present, false if definitely absent
     */
    public boolean mightContain(String key) {
        if (key == null) return false;
        return mightContain(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Tests whether raw key bytes might exist in the filter.
     */
    public boolean mightContain(byte[] keyBytes) {
        if (keyBytes == null) return false;
        int h1 = MurmurHash3.hash32(keyBytes, 0, keyBytes.length, SEED_1);
        int h2 = MurmurHash3.hash32(keyBytes, 0, keyBytes.length, SEED_2);

        for (int i = 0; i < k; i++) {
            int combinedHash = h1 + i * h2;
            int bitIndex = Math.abs(combinedHash % m);
            if (!getBit(bitIndex)) {
                return false;
            }
        }
        return true;
    }

    private void setBit(int index) {
        int wordIndex = index / 64;
        long bitMask = 1L << (index % 64);
        bitSet[wordIndex] |= bitMask;
    }

    private boolean getBit(int index) {
        int wordIndex = index / 64;
        long bitMask = 1L << (index % 64);
        return (bitSet[wordIndex] & bitMask) != 0L;
    }

    public int getK() {
        return k;
    }

    public int getM() {
        return m;
    }

    public long[] getBitSet() {
        return Arrays.copyOf(bitSet, bitSet.length);
    }

    /**
     * Serializes this Bloom filter into the destination buffer:
     * [Hash functions k (4B) | Bit count m (4B) | Word count w (4B) | Raw bitSet longs (w * 8B)].
     */
    public void writeTo(ByteBuffer buffer) {
        buffer.putInt(k);
        buffer.putInt(m);
        buffer.putInt(bitSet.length);
        for (long word : bitSet) {
            buffer.putLong(word);
        }
    }

    /**
     * Calculates serialized size in bytes.
     */
    public int getSerializedSizeBytes() {
        return 4 + 4 + 4 + (bitSet.length * 8);
    }

    /**
     * Deserializes a Bloom filter from the current position in a buffer.
     */
    public static BloomFilter readFrom(ByteBuffer buffer) {
        int k = buffer.getInt();
        int m = buffer.getInt();
        int wordCount = buffer.getInt();
        long[] words = new long[wordCount];
        for (int i = 0; i < wordCount; i++) {
            words[i] = buffer.getLong();
        }
        return new BloomFilter(k, m, words);
    }
}
