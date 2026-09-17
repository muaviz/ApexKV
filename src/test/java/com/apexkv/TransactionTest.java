package com.apexkv.txn;

import com.apexkv.core.ApexKVConfig;
import com.apexkv.core.ApexKVEngine;
import com.apexkv.exception.TransactionConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Transaction & Optimistic Concurrency Control Tests")
class TransactionTest {
    private ApexKVEngine engine;
    private TransactionManager txnManager;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        ApexKVConfig config = ApexKVConfig.builder()
                .dataDirectory(tempDir)
                .build();
        engine = new ApexKVEngine(config);
        txnManager = new TransactionManager(engine);
    }

    @AfterEach
    void tearDown() {
        if (engine != null && !engine.isClosed()) {
            engine.close();
        }
    }

    @Test
    @DisplayName("Should support read-your-own-writes inside active transaction")
    void testReadYourOwnWrites() {
        engine.put("account:101", "1000");

        try (Transaction txn = txnManager.beginTransaction()) {
            assertEquals("1000", txn.getAsString("account:101"));

            // Modify in transaction
            txn.put("account:101", "1500");
            txn.put("account:102", "500");

            // Txn should see its own uncommitted modifications
            assertEquals("1500", txn.getAsString("account:101"));
            assertEquals("500", txn.getAsString("account:102"));

            // Engine outside transaction should still see original state
            assertEquals("1000", engine.getAsString("account:101"));
            assertNull(engine.getAsString("account:102"));

            txn.commit();
        }

        // After commit, engine sees modified state
        assertEquals("1500", engine.getAsString("account:101"));
        assertEquals("500", engine.getAsString("account:102"));
    }

    @Test
    @DisplayName("Should discard uncommitted writes on rollback")
    void testRollback() {
        engine.put("stock:APEX", "50");

        try (Transaction txn = txnManager.beginTransaction()) {
            txn.put("stock:APEX", "100");
            txn.put("stock:GOOG", "200");
            txn.rollback();
        }

        // Engine should remain unmodified
        assertEquals("50", engine.getAsString("stock:APEX"));
        assertNull(engine.getAsString("stock:GOOG"));
    }

    @Test
    @DisplayName("Should detect write conflict and abort second transaction on colliding key")
    void testOptimisticConcurrencyConflict() {
        engine.put("balance", "500");

        Transaction txnA = txnManager.beginTransaction();
        Transaction txnB = txnManager.beginTransaction();

        // Both read balance
        assertEquals("500", txnA.getAsString("balance"));
        assertEquals("500", txnB.getAsString("balance"));

        // TxnA updates balance
        txnA.put("balance", "600");

        // TxnB also updates balance
        txnB.put("balance", "700");

        // TxnA commits first successfully
        assertDoesNotThrow(txnA::commit);
        assertEquals("600", engine.getAsString("balance"));

        // TxnB tries to commit second -> MUST throw TransactionConflictException!
        TransactionConflictException ex = assertThrows(
                TransactionConflictException.class,
                txnB::commit,
                "Expected OCC conflict on key 'balance'"
        );

        assertTrue(ex.getMessage().contains("balance"));
        // Final committed state remains from TxnA
        assertEquals("600", engine.getAsString("balance"));
    }
}
