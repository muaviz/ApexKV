package com.apexkv.storage.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BloomFilter & MurmurHash3 Unit Tests")
class BloomFilterTest {

    @Test
    @DisplayName("Must guarantee zero false negatives for all inserted keys")
    void testNoFalseNegatives() {
        int count = 10_000;
        BloomFilter filter = new BloomFilter(count, 0.01);

        for (int i = 0; i < count; i++) {
            filter.add("test_key_" + i);
        }

        for (int i = 0; i < count; i++) {
            assertTrue(filter.mightContain("test_key_" + i),
                    "Bloom filter yielded false negative for key: test_key_" + i);
        }
    }

    @Test
    @DisplayName("Should maintain empirical false positive rate close to theoretical target (~1%)")
    void testEmpiricalFalsePositiveRate() {
        int count = 5_000;
        double targetFpp = 0.01;
        BloomFilter filter = new BloomFilter(count, targetFpp);

        for (int i = 0; i < count; i++) {
            filter.add("inserted_key_" + i);
        }

        int testQueries = 10_000;
        int falsePositives = 0;

        for (int i = 0; i < testQueries; i++) {
            String nonExistentKey = "non_existent_key_" + i;
            if (filter.mightContain(nonExistentKey)) {
                falsePositives++;
            }
        }

        double empiricalFpp = (double) falsePositives / testQueries;
        // Should be bounded reasonably near the target (allowing small statistical variance <= 2.5%)
        assertTrue(empiricalFpp < 0.025,
                String.format("Empirical FPP %.4f exceeded acceptable bound", empiricalFpp));
    }

    @Test
    @DisplayName("Should accurately serialize and deserialize bit arrays")
    void testSerializationRoundTrip() {
        BloomFilter original = new BloomFilter(1_000, 0.01);
        for (int i = 0; i < 500; i++) {
            original.add("item:" + i);
        }

        int serializedSize = original.getSerializedSizeBytes();
        ByteBuffer buffer = ByteBuffer.allocate(serializedSize);
        original.writeTo(buffer);
        buffer.flip();

        BloomFilter restored = BloomFilter.readFrom(buffer);
        assertEquals(original.getK(), restored.getK());
        assertEquals(original.getM(), restored.getM());

        for (int i = 0; i < 500; i++) {
            assertTrue(restored.mightContain("item:" + i));
        }
        assertFalse(restored.mightContain("definitely_not_inserted_key_xyz"));
    }
}
