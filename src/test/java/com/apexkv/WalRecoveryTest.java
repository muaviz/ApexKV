package com.apexkv.storage.wal;

import com.apexkv.core.DataRecord;
import com.apexkv.exception.CorruptedWalException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WAL & Crash Recovery Unit Tests")
class WalRecoveryTest {

    @Test
    @DisplayName("Should durably persist and replay records in exact sequence")
    void testBasicAppendAndReplay(@TempDir Path tempDir) {
        Path walPath = tempDir.resolve("test.wal");

        try (WriteAheadLog wal = new WriteAheadLog(walPath, true)) {
            wal.append(DataRecord.put("user:001", "Alice".getBytes(), 1000L));
            wal.append(DataRecord.put("user:002", "Bob".getBytes(), 2000L));
            wal.append(DataRecord.delete("user:001", 3000L));
            wal.sync();
        }

        try (WalReader reader = new WalReader(walPath)) {
            List<DataRecord> records = reader.readAll();
            assertEquals(3, records.size());

            assertEquals("user:001", records.get(0).getKey());
            assertEquals("Alice", records.get(0).getValueAsString());
            assertFalse(records.get(0).isTombstone());

            assertEquals("user:002", records.get(1).getKey());
            assertEquals("Bob", records.get(1).getValueAsString());

            assertEquals("user:001", records.get(2).getKey());
            assertTrue(records.get(2).isTombstone());
        }
    }

    @Test
    @DisplayName("Should detect bit rot or corrupted data via CRC32 validation")
    void testCorruptionDetection(@TempDir Path tempDir) throws Exception {
        Path walPath = tempDir.resolve("corrupted.wal");

        try (WriteAheadLog wal = new WriteAheadLog(walPath, true)) {
            wal.append(DataRecord.put("criticalKey", "OriginalSecureValue".getBytes()));
            wal.sync();
        }

        // Intentionally corrupt a byte inside the payload section of the file
        try (RandomAccessFile raf = new RandomAccessFile(walPath.toFile(), "rw")) {
            long corruptOffset = raf.length() - 5; // near end of payload
            raf.seek(corruptOffset);
            byte b = raf.readByte();
            raf.seek(corruptOffset);
            raf.writeByte(b ^ 0xFF); // Flip all bits
        }

        // Replay should now fail with CorruptedWalException
        try (WalReader reader = new WalReader(walPath, false)) {
            assertThrows(CorruptedWalException.class, reader::readAll);
        }
    }

    @Test
    @DisplayName("Should gracefully tolerate torn writes at end-of-file during crash")
    void testTornWriteTolerance(@TempDir Path tempDir) throws Exception {
        Path walPath = tempDir.resolve("torn.wal");

        try (WriteAheadLog wal = new WriteAheadLog(walPath, true)) {
            wal.append(DataRecord.put("k1", "val1".getBytes()));
            wal.append(DataRecord.put("k2", "val2".getBytes()));
            wal.sync();
        }

        // Simulate crash mid-write by appending partial bytes (incomplete header)
        try (RandomAccessFile raf = new RandomAccessFile(walPath.toFile(), "rw")) {
            raf.seek(raf.length());
            raf.write(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05}); // Incomplete torn write
        }

        try (WalReader reader = new WalReader(walPath, true)) {
            List<DataRecord> records = reader.readAll();
            // Should successfully recover the 2 complete records and gracefully stop at torn tail
            assertEquals(2, records.size());
            assertEquals("k1", records.get(0).getKey());
            assertEquals("k2", records.get(1).getKey());
        }
    }
}
