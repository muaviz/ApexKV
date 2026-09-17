package com.apexkv.storage.compaction;

import com.apexkv.core.ApexKVConfig;
import com.apexkv.core.DataRecord;
import com.apexkv.core.EngineStats;
import com.apexkv.storage.sstable.SSTableManager;
import com.apexkv.storage.sstable.SSTableReader;
import com.apexkv.storage.sstable.SSTableWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Compaction & Multi-Way Merge Tests")
class CompactionTest {

    @Test
    @DisplayName("MergeIterator should prioritize newest timestamp and purge tombstones")
    void testMergeIteratorDeduplicationAndPurge() {
        // Iterator 1: older writes
        List<DataRecord> list1 = List.of(
                DataRecord.put("key1", "val1_old".getBytes(), 1000L),
                DataRecord.put("key2", "val2_old".getBytes(), 1000L),
                DataRecord.put("key3", "val3_old".getBytes(), 1000L)
        );

        // Iterator 2: newer writes (update key1, delete key2)
        List<DataRecord> list2 = List.of(
                DataRecord.put("key1", "val1_fresh".getBytes(), 2000L),
                DataRecord.delete("key2", 2000L),
                DataRecord.put("key4", "val4_fresh".getBytes(), 2000L)
        );

        List<Iterator<DataRecord>> iterators = List.of(list1.iterator(), list2.iterator());

        // Merge with tombstone purging enabled
        MergeIterator mergeIterator = new MergeIterator(iterators, true);

        List<DataRecord> merged = new ArrayList<>();
        while (mergeIterator.hasNext()) {
            merged.add(mergeIterator.next());
        }

        // Expect:
        // key1: val1_fresh (fresher timestamp wins)
        // key2: omitted (tombstone purged!)
        // key3: val3_old
        // key4: val4_fresh
        assertEquals(3, merged.size());
        assertEquals("key1", merged.get(0).getKey());
        assertEquals("val1_fresh", merged.get(0).getValueAsString());

        assertEquals("key3", merged.get(1).getKey());
        assertEquals("val3_old", merged.get(1).getValueAsString());

        assertEquals("key4", merged.get(2).getKey());
        assertEquals("val4_fresh", merged.get(2).getValueAsString());
    }

    @Test
    @DisplayName("CompactionEngine should consolidate multiple Level-0 SSTables into Level-1")
    void testFullCompactionExecution(@TempDir Path tempDir) {
        Path sstDir = tempDir.resolve("sstable");
        SSTableManager sstManager = new SSTableManager(sstDir);
        ApexKVConfig config = ApexKVConfig.builder()
                .dataDirectory(tempDir)
                .compactionThreshold(3)
                .build();
        EngineStats stats = new EngineStats();

        // Create 3 Level-0 SSTables
        for (int t = 0; t < 3; t++) {
            Path[] paths = sstManager.allocateNewSSTablePaths(0);
            SSTableWriter writer = new SSTableWriter(paths[0], paths[1], 4, 0.01);
            List<DataRecord> batch = List.of(
                    DataRecord.put("alpha", ("version_" + t).getBytes(), 1000L + t),
                    DataRecord.put("table_" + t, "present".getBytes(), 1000L + t)
            );
            writer.write(batch.iterator(), batch.size());
            SSTableReader reader = new SSTableReader(paths[0], paths[1], sstManager.getNextId(), 0);
            sstManager.registerNewSSTable(reader);
        }

        assertEquals(3, sstManager.getLevelSSTables(0).size());
        assertEquals(0, sstManager.getLevelSSTables(1).size());

        try (CompactionEngine compactor = new CompactionEngine(config, sstManager, stats)) {
            boolean compacted = compactor.compact();
            assertTrue(compacted, "Compaction should have executed");

            // Level 0 should now be empty, and Level 1 should have 1 consolidated table
            assertEquals(0, sstManager.getLevelSSTables(0).size());
            assertEquals(1, sstManager.getLevelSSTables(1).size());

            // Check that the consolidated table retains the freshest version of "alpha"
            DataRecord alphaRecord = sstManager.get("alpha");
            assertNotNull(alphaRecord);
            assertEquals("version_2", alphaRecord.getValueAsString());
        }
    }
}
