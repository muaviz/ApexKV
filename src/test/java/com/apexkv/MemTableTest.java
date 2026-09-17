package com.apexkv;

import com.apexkv.core.DataRecord;
import com.apexkv.core.RecordType;
import com.apexkv.storage.memtable.ConcurrentSkipListMemTable;
import com.apexkv.storage.memtable.MemTable;
import com.apexkv.storage.memtable.MemTableSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MemTable Unit Tests")
class MemTableTest {
    private MemTable memTable;

    @BeforeEach
    void setUp() {
        memTable = new ConcurrentSkipListMemTable();
    }

    @Test
    @DisplayName("Should insert and retrieve key-value pairs accurately")
    void testBasicPutAndGet() {
        assertTrue(memTable.isEmpty());
        assertEquals(0, memTable.count());

        memTable.put(DataRecord.put("user:001", "Alice".getBytes()));
        memTable.put(DataRecord.put("user:002", "Bob".getBytes()));

        assertEquals(2, memTable.count());
        assertFalse(memTable.isEmpty());

        DataRecord rec1 = memTable.get("user:001");
        assertNotNull(rec1);
        assertEquals("Alice", rec1.getValueAsString());
        assertFalse(rec1.isTombstone());

        DataRecord rec2 = memTable.get("user:002");
        assertNotNull(rec2);
        assertEquals("Bob", rec2.getValueAsString());

        assertNull(memTable.get("user:999"));
    }

    @Test
    @DisplayName("Should update existing keys and accurately track estimated byte size")
    void testUpdateAndByteSizeTracking() {
        long initialSize = memTable.byteSize();
        assertEquals(0, initialSize);

        memTable.put(DataRecord.put("key1", "short".getBytes()));
        long sizeAfterFirst = memTable.byteSize();
        assertTrue(sizeAfterFirst > 0);

        // Update with larger value
        memTable.put(DataRecord.put("key1", "a very long replacement value that increases byte footprint".getBytes()));
        long sizeAfterUpdate = memTable.byteSize();
        assertTrue(sizeAfterUpdate > sizeAfterFirst);

        assertEquals(1, memTable.count());
        assertEquals("a very long replacement value that increases byte footprint", memTable.get("key1").getValueAsString());
    }

    @Test
    @DisplayName("Should store tombstones on delete operations")
    void testDeleteTombstone() {
        memTable.put(DataRecord.put("keyA", "ValueA".getBytes()));
        assertFalse(memTable.get("keyA").isTombstone());

        memTable.put(DataRecord.delete("keyA"));
        DataRecord deletedRec = memTable.get("keyA");
        assertNotNull(deletedRec);
        assertTrue(deletedRec.isTombstone());
        assertEquals(RecordType.DELETE, deletedRec.getType());
    }

    @Test
    @DisplayName("Should iterate in lexicographical order")
    void testLexicographicalOrdering() {
        memTable.put(DataRecord.put("delta", "4".getBytes()));
        memTable.put(DataRecord.put("alpha", "1".getBytes()));
        memTable.put(DataRecord.put("gamma", "3".getBytes()));
        memTable.put(DataRecord.put("beta", "2".getBytes()));

        List<String> keys = new ArrayList<>();
        for (DataRecord rec : memTable) {
            keys.add(rec.getKey());
        }

        assertEquals(List.of("alpha", "beta", "delta", "gamma"), keys);
        assertEquals("alpha", memTable.getSmallestKey());
        assertEquals("gamma", memTable.getLargestKey());
    }

    @Test
    @DisplayName("Should accurately perform range scans")
    void testRangeScan() {
        memTable.put(DataRecord.put("k10", "v10".getBytes()));
        memTable.put(DataRecord.put("k20", "v20".getBytes()));
        memTable.put(DataRecord.put("k30", "v30".getBytes()));
        memTable.put(DataRecord.put("k40", "v40".getBytes()));
        memTable.put(DataRecord.put("k50", "v50".getBytes()));

        // Half open [k20, k40)
        Iterator<DataRecord> it = memTable.rangeScan("k20", "k40");
        List<String> keys = new ArrayList<>();
        while (it.hasNext()) {
            keys.add(it.next().getKey());
        }
        assertEquals(List.of("k20", "k30"), keys);

        // Scan from beginning to k30
        it = memTable.rangeScan(null, "k30");
        keys.clear();
        while (it.hasNext()) {
            keys.add(it.next().getKey());
        }
        assertEquals(List.of("k10", "k20"), keys);

        // Scan from k30 to end
        it = memTable.rangeScan("k30", null);
        keys.clear();
        while (it.hasNext()) {
            keys.add(it.next().getKey());
        }
        assertEquals(List.of("k30", "k40", "k50"), keys);
    }

    @Test
    @DisplayName("Should create immutable snapshots that isolate mutations")
    void testMemTableSnapshot() {
        memTable.put(DataRecord.put("snap1", "Val1".getBytes()));
        memTable.put(DataRecord.put("snap2", "Val2".getBytes()));

        MemTableSnapshot snapshot = new MemTableSnapshot(memTable);
        assertEquals(2, snapshot.count());
        assertEquals("Val1", snapshot.get("snap1").getValueAsString());

        // Mutate original active table
        memTable.put(DataRecord.put("snap3", "Val3".getBytes()));
        assertEquals(3, memTable.count());
        assertEquals(2, snapshot.count()); // Snapshot isolated
        assertNull(snapshot.get("snap3"));

        // Modifying snapshot should throw UnsupportedOperationException
        assertThrows(UnsupportedOperationException.class, () ->
                snapshot.put(DataRecord.put("snap4", "Val4".getBytes())));
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }
}
