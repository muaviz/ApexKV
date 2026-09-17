package com.apexkv.storage.sstable;

import com.apexkv.core.DataRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SSTable Persistence & Sparse Index Tests")
class SSTableTest {

    @Test
    @DisplayName("Should write and read SSTable data blocks and sparse index correctly")
    void testWriteAndReadSSTable(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("test_001.db");
        Path idxPath = tempDir.resolve("test_001.idx");

        List<DataRecord> records = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            records.add(DataRecord.put(String.format("k%04d", i), ("value_" + i).getBytes()));
        }

        SSTableWriter writer = new SSTableWriter(dbPath, idxPath, 8, 0.01);
        long bytesWritten = writer.write(records.iterator(), records.size());
        assertTrue(bytesWritten > 0);

        try (SSTableReader reader = new SSTableReader(dbPath, idxPath, 1, 0)) {
            assertEquals(100, reader.getRecordCount());
            assertEquals("k0000", reader.getSmallestKey());
            assertEquals("k0099", reader.getLargestKey());

            // Point lookups
            DataRecord rec0 = reader.get("k0000");
            assertNotNull(rec0);
            assertEquals("value_0", rec0.getValueAsString());

            DataRecord rec55 = reader.get("k0055");
            assertNotNull(rec55);
            assertEquals("value_55", rec55.getValueAsString());

            DataRecord rec99 = reader.get("k0099");
            assertNotNull(rec99);
            assertEquals("value_99", rec99.getValueAsString());

            // Non-existent keys
            assertNull(reader.get("k9999"));
            assertNull(reader.get("a_before_smallest"));
            assertNull(reader.get("z_after_largest"));
        }
    }

    @Test
    @DisplayName("Should handle tombstone markers in SSTable")
    void testTombstoneInSSTable(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("tomb_001.db");
        Path idxPath = tempDir.resolve("tomb_001.idx");

        List<DataRecord> records = List.of(
                DataRecord.put("item1", "val1".getBytes()),
                DataRecord.delete("item2"),
                DataRecord.put("item3", "val3".getBytes())
        );

        SSTableWriter writer = new SSTableWriter(dbPath, idxPath, 2, 0.01);
        writer.write(records.iterator(), records.size());

        try (SSTableReader reader = new SSTableReader(dbPath, idxPath, 1, 0)) {
            assertEquals(3, reader.getRecordCount());

            DataRecord item1 = reader.get("item1");
            assertNotNull(item1);
            assertFalse(item1.isTombstone());

            DataRecord item2 = reader.get("item2");
            assertNotNull(item2);
            assertTrue(item2.isTombstone()); // Tombstone identified
        }
    }

    @Test
    @DisplayName("Should accurately perform range scans over SSTable records")
    void testSSTableRangeScan(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("scan_001.db");
        Path idxPath = tempDir.resolve("scan_001.idx");

        List<DataRecord> records = new ArrayList<>();
        for (int i = 10; i < 50; i++) {
            records.add(DataRecord.put(String.format("row:%02d", i), ("v" + i).getBytes()));
        }

        SSTableWriter writer = new SSTableWriter(dbPath, idxPath, 4, 0.01);
        writer.write(records.iterator(), records.size());

        try (SSTableReader reader = new SSTableReader(dbPath, idxPath, 1, 0)) {
            Iterator<DataRecord> it = reader.rangeScan("row:20", "row:30");
            List<String> keys = new ArrayList<>();
            while (it.hasNext()) {
                keys.add(it.next().getKey());
            }

            assertEquals(10, keys.size());
            assertEquals("row:20", keys.get(0));
            assertEquals("row:29", keys.get(keys.size() - 1));
        }
    }
}
