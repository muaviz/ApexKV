package com.apexkv;

import com.apexkv.core.ApexKVConfig;
import com.apexkv.core.ApexKVEngine;
import com.apexkv.core.DataRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ApexKVEngine End-to-End Integration Tests")
class ApexKVEngineIntegrationTest {

    @Test
    @DisplayName("Should maintain read consistency across MemTable, flush, and SSTables")
    void testEndToEndLifecycle(@TempDir Path tempDir) {
        ApexKVConfig config = ApexKVConfig.builder()
                .dataDirectory(tempDir)
                .memTableThresholdBytes(2048) // small threshold to trigger flushes
                .build();

        try (ApexKVEngine engine = new ApexKVEngine(config)) {
            // 1. Initial writes
            for (int i = 0; i < 50; i++) {
                engine.put("key:" + i, "val_" + i);
            }

            // Verify in-memory reads
            for (int i = 0; i < 50; i++) {
                assertEquals("val_" + i, engine.getAsString("key:" + i));
            }

            // 2. Explicit flush to SSTable
            engine.flush();
            assertTrue(engine.getStats().getActiveSSTableCount() > 0);

            // Verify reads from on-disk SSTable
            for (int i = 0; i < 50; i++) {
                assertEquals("val_" + i, engine.getAsString("key:" + i));
            }

            // 3. Updates and Deletes in active MemTable
            engine.put("key:10", "val_10_updated");
            engine.delete("key:20");

            // Verify multi-tier read precedence:
            // key:10 should return updated value (from active MemTable masking SSTable)
            assertEquals("val_10_updated", engine.getAsString("key:10"));
            // key:20 should return null (tombstone in active MemTable masking SSTable)
            assertNull(engine.getAsString("key:20"));
            // key:30 should still return original value from SSTable
            assertEquals("val_30", engine.getAsString("key:30"));

            // 4. Second flush
            engine.flush();
            assertEquals("val_10_updated", engine.getAsString("key:10"));
            assertNull(engine.getAsString("key:20"));
        }
    }

    @Test
    @DisplayName("Should recover un-flushed in-memory data on engine restart via WAL replay")
    void testRestartCrashRecovery(@TempDir Path tempDir) {
        ApexKVConfig config = ApexKVConfig.builder()
                .dataDirectory(tempDir)
                .syncWalOnWrite(true)
                .build();

        // Phase 1: Write records to engine and shutdown without explicit flush
        try (ApexKVEngine engine = new ApexKVEngine(config)) {
            engine.put("session:token1", "auth_user_alpha");
            engine.put("session:token2", "auth_user_beta");
            engine.put("session:token3", "auth_user_gamma");
            engine.delete("session:token2");
            // Engine closes here; active MemTable data was written to WAL
        }

        // Phase 2: Boot new engine instance on the same data directory
        try (ApexKVEngine engineRestarted = new ApexKVEngine(config)) {
            assertEquals("auth_user_alpha", engineRestarted.getAsString("session:token1"));
            assertNull(engineRestarted.getAsString("session:token2")); // Deleted record stays deleted
            assertEquals("auth_user_gamma", engineRestarted.getAsString("session:token3"));
        }
    }

    @Test
    @DisplayName("Should execute cross-tier range scans with tombstones filtered out")
    void testCrossTierRangeScan(@TempDir Path tempDir) {
        ApexKVConfig config = ApexKVConfig.builder()
                .dataDirectory(tempDir)
                .build();

        try (ApexKVEngine engine = new ApexKVEngine(config)) {
            // Persist to SSTable
            engine.put("item:1", "first");
            engine.put("item:2", "second");
            engine.put("item:3", "third");
            engine.flush();

            // Active MemTable modifications
            engine.put("item:4", "fourth");
            engine.delete("item:2"); // Mask SSTable item:2

            Iterator<DataRecord> it = engine.scan("item:1", "item:5");
            List<String> keys = new ArrayList<>();
            while (it.hasNext()) {
                keys.add(it.next().getKey());
            }

            // item:2 should be absent because tombstone was purged in scan
            assertEquals(List.of("item:1", "item:3", "item:4"), keys);
        }
    }
}
