package com.apexkv.cli;

import com.apexkv.core.ApexKVEngine;
import com.apexkv.core.EngineStats;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * High-performance concurrent benchmark harness for ApexKV.
 * Measures multi-threaded write/read throughput, latency percentiles (p50, p95, p99),
 * cache hit ratios, and compaction impacts.
 */
public class BenchmarkRunner {
    private final ApexKVEngine engine;
    private final PrintStream out;

    public BenchmarkRunner(ApexKVEngine engine, PrintStream out) {
        this.engine = engine;
        this.out = out;
    }

    public void runBenchmark(int numWrites, int numReads, int threadCount) {
        out.println("\n=======================================================");
        out.println("            ApexKV Storage Engine Benchmark            ");
        out.println("=======================================================");
        out.printf("Configuration: %d writes, %d reads, %d concurrent worker threads\n",
                numWrites, numReads, threadCount);
        out.println("-------------------------------------------------------");

        EngineStats statsBefore = engine.getStats();

        // 1. Concurrent Write Phase
        long writeStart = System.nanoTime();
        List<Long> writeLatencies = runConcurrentWrites(numWrites, threadCount);
        long writeElapsedMs = (System.nanoTime() - writeStart) / 1_000_000L;
        double writeThroughput = (double) numWrites / (Math.max(1, writeElapsedMs) / 1000.0);

        // 2. Concurrent Read Phase (Mixed: 80% existing keys, 20% non-existing keys)
        long readStart = System.nanoTime();
        List<Long> readLatencies = runConcurrentReads(numReads, numWrites, threadCount);
        long readElapsedMs = (System.nanoTime() - readStart) / 1_000_000L;
        double readThroughput = (double) numReads / (Math.max(1, readElapsedMs) / 1000.0);

        // Format and print latency metrics
        printLatencyReport("Write Workload (PUT)", numWrites, writeElapsedMs, writeThroughput, writeLatencies);
        printLatencyReport("Read Workload (GET)", numReads, readElapsedMs, readThroughput, readLatencies);

        // Print engine status summary
        EngineStats statsAfter = engine.getStats();
        AsciiTablePrinter engineTable = new AsciiTablePrinter("Metric", "Value");
        engineTable.addRow("Total Level-0 / Level-1 SSTables", String.valueOf(statsAfter.getActiveSSTableCount()));
        engineTable.addRow("On-Disk Storage Footprint", formatBytes(statsAfter.getDiskSizeBytes()));
        engineTable.addRow("MemTable Cache Hits", String.valueOf(statsAfter.getMemTableHits() - statsBefore.getMemTableHits()));
        engineTable.addRow("SSTable Disk Hits", String.valueOf(statsAfter.getSstableHits() - statsBefore.getSstableHits()));
        engineTable.addRow("Bloom Filter Pruned Checks", String.valueOf(statsAfter.getBloomFilterPrunes() - statsBefore.getBloomFilterPrunes()));
        engineTable.addRow("Compaction Passes Completed", String.valueOf(statsAfter.getCompactionRuns()));
        out.println("\n--- Post-Benchmark Engine State ---");
        engineTable.print(out);
    }

    private List<Long> runConcurrentWrites(int totalWrites, int threadCount) {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(totalWrites));
        int writesPerThread = totalWrites / threadCount;
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    int startIdx = threadId * writesPerThread;
                    int endIdx = (threadId == threadCount - 1) ? totalWrites : startIdx + writesPerThread;
                    byte[] payload = "ApexKV_Benchmark_Test_Payload_Value_Bytes_123456789".getBytes();

                    for (int i = startIdx; i < endIdx; i++) {
                        String key = String.format("user:%08d", i);
                        long t0 = System.nanoTime();
                        engine.put(key, payload);
                        long durUs = (System.nanoTime() - t0) / 1_000L;
                        latencies.add(durUs);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
        }

        return latencies;
    }

    private List<Long> runConcurrentReads(int totalReads, int keySpace, int threadCount) {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(totalReads));
        int readsPerThread = totalReads / threadCount;
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            pool.submit(() -> {
                try {
                    Random rand = new Random();
                    for (int i = 0; i < readsPerThread; i++) {
                        // 80% existing keys, 20% missing keys
                        int targetId = (rand.nextDouble() < 0.8)
                                ? rand.nextInt(keySpace)
                                : keySpace + rand.nextInt(keySpace);

                        String key = String.format("user:%08d", targetId);
                        long t0 = System.nanoTime();
                        engine.get(key);
                        long durUs = (System.nanoTime() - t0) / 1_000L;
                        latencies.add(durUs);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
        }

        return latencies;
    }

    private void printLatencyReport(String title, int ops, long elapsedMs, double throughput, List<Long> latencies) {
        long[] sorted;
        synchronized (latencies) {
            sorted = latencies.stream().mapToLong(Long::longValue).toArray();
        }
        Arrays.sort(sorted);

        double p50 = sorted.length > 0 ? sorted[(int) (sorted.length * 0.50)] : 0;
        double p95 = sorted.length > 0 ? sorted[(int) (sorted.length * 0.95)] : 0;
        double p99 = sorted.length > 0 ? sorted[(int) (sorted.length * 0.99)] : 0;
        double avg = sorted.length > 0 ? Arrays.stream(sorted).average().orElse(0) : 0;

        out.println("\n--- " + title + " ---");
        AsciiTablePrinter table = new AsciiTablePrinter("Metric", "Measurement");
        table.addRow("Total Operations", String.format("%,d ops", ops));
        table.addRow("Elapsed Time", String.format("%,d ms", elapsedMs));
        table.addRow("Throughput", String.format("%,.2f ops/sec", throughput));
        table.addRow("Average Latency", String.format("%.2f µs", avg));
        table.addRow("p50 Median Latency", String.format("%.2f µs", p50));
        table.addRow("p95 Latency", String.format("%.2f µs", p95));
        table.addRow("p99 Latency", String.format("%.2f µs", p99));
        table.print(out);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.2f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
