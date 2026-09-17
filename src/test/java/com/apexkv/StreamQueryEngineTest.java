package com.apexkv.query;

import com.apexkv.core.ApexKVConfig;
import com.apexkv.core.ApexKVEngine;
import com.apexkv.core.DataRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Stream Query Engine Tests")
class StreamQueryEngineTest {
    private ApexKVEngine engine;
    private StreamQueryEngine queryEngine;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        ApexKVConfig config = ApexKVConfig.builder()
                .dataDirectory(tempDir)
                .build();
        engine = new ApexKVEngine(config);
        queryEngine = new StreamQueryEngine(engine);

        // Seed data
        engine.put("user:001", "Alice - Senior Engineer");
        engine.put("user:002", "Bob - Product Designer");
        engine.put("user:003", "Charlie - DevOps Specialist");
        engine.put("order:101", "Order #101 - Paid");
        engine.put("order:102", "Order #102 - Pending");
        engine.put("order:103", "Order #103 - Shipped");
    }

    @AfterEach
    void tearDown() {
        if (engine != null && !engine.isClosed()) {
            engine.close();
        }
    }

    @Test
    @DisplayName("Should accurately stream records by prefix")
    void testStreamPrefix() {
        List<DataRecord> users = queryEngine.streamPrefix("user:").toList();
        assertEquals(3, users.size());
        assertEquals("user:001", users.get(0).getKey());
        assertEquals("user:002", users.get(1).getKey());
        assertEquals("user:003", users.get(2).getKey());

        List<DataRecord> orders = queryEngine.streamPrefix("order:").toList();
        assertEquals(3, orders.size());
    }

    @Test
    @DisplayName("Should filter records using custom lambda predicates")
    void testStreamWithPredicates() {
        // Find engineers
        QueryPredicate engineerFilter = QueryPredicate.valueContains("Engineer");
        List<DataRecord> results = queryEngine.streamFilter(engineerFilter).toList();

        assertEquals(1, results.size());
        assertEquals("user:001", results.get(0).getKey());

        // Regex pattern on key
        QueryPredicate regexFilter = QueryPredicate.keyMatches("order:10[12]");
        List<DataRecord> matchedOrders = queryEngine.streamFilter(regexFilter).toList();
        assertEquals(2, matchedOrders.size());
    }

    @Test
    @DisplayName("Should aggregate counts by key prefix")
    void testCountByKeyPrefix() {
        Map<String, Long> prefixCounts = queryEngine.countByKeyPrefix(5); // "user:" and "order"
        assertEquals(3L, prefixCounts.get("user:"));
        assertEquals(3L, prefixCounts.get("order"));
    }

    @Test
    @DisplayName("Should correctly project range scans into a Map")
    void testToMap() {
        Map<String, String> userMap = queryEngine.toMap("user:001", "user:003");
        assertEquals(2, userMap.size());
        assertTrue(userMap.containsKey("user:001"));
        assertTrue(userMap.containsKey("user:002"));
        assertFalse(userMap.containsKey("user:003"));
    }
}
