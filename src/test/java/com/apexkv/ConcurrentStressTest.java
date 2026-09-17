package com.apexkv;

import com.apexkv.core.ApexKVConfig;
import com.apexkv.core.ApexKVEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Concurrent Multithreaded Stress Tests")
class ConcurrentStressTest {

    @Test
    @DisplayName("Should sustain high concurrent multi-threaded read/write/delete workload without race conditions")
    void testConcurrentReadWriteStress(@TempDir Path tempDir) throws Exception {
        ApexKVConfig config = ApexKVConfig.builder()
                .dataDirectory(tempDir)
                .memTableThresholdBytes(32 * 1024) // 32 KB threshold to trigger frequent background flushes
                .compactionThreshold(3)
                .build();

        int threadCount = 8;
        int operationsPerThread = 1_000;
        int totalOps = threadCount * operationsPerThread;

        AtomicInteger successfulOps = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        try (ApexKVEngine engine = new ApexKVEngine(config)) {
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch endGate = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                pool.submit(() -> {
                    try {
                        startGate.await(); // Synchronize thread start
                        Random rand = new Random(threadId * 31L);

                        for (int i = 0; i < operationsPerThread; i++) {
                            int keyNum = rand.nextInt(200); // 200 key working set creates contention
                            String key = String.format("account:%04d", keyNum);
                            int op = rand.nextInt(10);

                            if (op < 6) {
                                // 60% Puts
                                engine.put(key, "balance_" + i);
                            } else if (op < 9) {
                                // 30% Gets
                                engine.get(key);
                            } else {
                                // 10% Deletes
                                engine.delete(key);
                            }
                            successfulOps.incrementAndGet();
                        }
                    } catch (Throwable t1) {
                        failureCount.incrementAndGet();
                        t1.printStackTrace();
                    } finally {
                        endGate.countDown();
                    }
                });
            }

            // Release all threads simultaneously
            startGate.countDown();
            boolean completed = endGate.await(30, TimeUnit.SECONDS);

            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

            assertTrue(completed, "Stress test threads did not finish within timeout");
            assertEquals(0, failureCount.get(), "Encountered exceptions during concurrent stress test");
            assertEquals(totalOps, successfulOps.get());

            // Engine stats verification
            assertTrue(engine.getStats().getPutRequests() > 0);
            assertTrue(engine.getStats().getGetRequests() > 0);
        }
    }
}
