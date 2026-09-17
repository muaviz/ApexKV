package com.apexkv.cli;

import com.apexkv.core.ApexKVEngine;
import com.apexkv.core.DataRecord;
import com.apexkv.core.EngineStats;
import com.apexkv.query.StreamQueryEngine;
import com.apexkv.txn.Transaction;
import com.apexkv.txn.TransactionManager;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Command dispatcher and argument parser implementing the Command Pattern for the CLI shell.
 */
public class CommandHandler {
    private final ApexKVEngine engine;
    private final TransactionManager txnManager;
    private final StreamQueryEngine queryEngine;
    private final PrintStream out;
    private Transaction activeTxn;

    public CommandHandler(ApexKVEngine engine, PrintStream out) {
        this.engine = Objects.requireNonNull(engine, "engine cannot be null");
        this.out = Objects.requireNonNull(out, "out cannot be null");
        this.txnManager = new TransactionManager(engine);
        this.queryEngine = new StreamQueryEngine(engine);
        this.activeTxn = null;
    }

    /**
     * Parses a command string into whitespace-separated tokens, preserving quoted strings.
     */
    public static List<String> parseTokens(String line) {
        List<String> tokens = new ArrayList<>();
        if (line == null || line.isBlank()) {
            return tokens;
        }

        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = ' ';

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == quoteChar) {
                    inQuotes = false;
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"' || c == '\'') {
                    inQuotes = true;
                    quoteChar = c;
                } else if (Character.isWhitespace(c)) {
                    if (sb.length() > 0) {
                        tokens.add(sb.toString());
                        sb.setLength(0);
                    }
                } else {
                    sb.append(c);
                }
            }
        }
        if (sb.length() > 0) {
            tokens.add(sb.toString());
        }
        return tokens;
    }

    /**
     * Executes the parsed command tokens. Returns false if the session should terminate.
     */
    public boolean execute(String line) {
        List<String> tokens = parseTokens(line);
        if (tokens.isEmpty()) {
            return true;
        }

        String cmd = tokens.get(0).toLowerCase();

        try {
            switch (cmd) {
                case "exit", "quit" -> {
                    if (activeTxn != null) {
                        activeTxn.rollback();
                    }
                    out.println("Goodbye!");
                    return false;
                }
                case "help", "?" -> printHelp();
                case "put" -> handlePut(tokens);
                case "get" -> handleGet(tokens);
                case "delete", "del", "rm" -> handleDelete(tokens);
                case "scan" -> handleScan(tokens);
                case "prefix" -> handlePrefix(tokens);
                case "flush" -> handleFlush();
                case "compact" -> handleCompact();
                case "stats" -> handleStats();
                case "count" -> handleCount();
                case "begin" -> handleBegin();
                case "txn-put" -> handleTxnPut(tokens);
                case "txn-get" -> handleTxnGet(tokens);
                case "txn-delete" -> handleTxnDelete(tokens);
                case "commit" -> handleCommit();
                case "rollback" -> handleRollback();
                case "benchmark" -> handleBenchmark(tokens);
                default -> out.println("Unknown command: '" + cmd + "'. Type 'help' for available commands.");
            }
        } catch (Exception e) {
            out.println("[ERROR] " + e.getMessage());
        }

        return true;
    }

    private void handlePut(List<String> tokens) {
        if (tokens.size() < 3) {
            out.println("Usage: put <key> <value>");
            return;
        }
        String key = tokens.get(1);
        String val = String.join(" ", tokens.subList(2, tokens.size()));
        long t0 = System.nanoTime();
        engine.put(key, val);
        long durUs = (System.nanoTime() - t0) / 1000L;
        out.printf("OK (stored in %d µs)\n", durUs);
    }

    private void handleGet(List<String> tokens) {
        if (tokens.size() < 2) {
            out.println("Usage: get <key>");
            return;
        }
        String key = tokens.get(1);
        long t0 = System.nanoTime();
        String val = engine.getAsString(key);
        long durUs = (System.nanoTime() - t0) / 1000L;

        if (val != null) {
            out.printf("\"%s\" (found in %d µs)\n", val, durUs);
        } else {
            out.printf("(nil) (evaluated in %d µs)\n", durUs);
        }
    }

    private void handleDelete(List<String> tokens) {
        if (tokens.size() < 2) {
            out.println("Usage: delete <key>");
            return;
        }
        String key = tokens.get(1);
        long t0 = System.nanoTime();
        engine.delete(key);
        long durUs = (System.nanoTime() - t0) / 1000L;
        out.printf("OK (tombstone recorded in %d µs)\n", durUs);
    }

    private void handleScan(List<String> tokens) {
        String startKey = (tokens.size() > 1 && !tokens.get(1).equals("*")) ? tokens.get(1) : null;
        String endKey = (tokens.size() > 2 && !tokens.get(2).equals("*")) ? tokens.get(2) : null;
        int limit = (tokens.size() > 3) ? Integer.parseInt(tokens.get(3)) : 50;

        AsciiTablePrinter table = new AsciiTablePrinter("Key", "Value", "Timestamp (ms)");
        Iterator<DataRecord> it = engine.scan(startKey, endKey);

        int count = 0;
        while (it.hasNext() && count < limit) {
            DataRecord rec = it.next();
            table.addRow(rec.getKey(), rec.getValueAsString(), String.valueOf(rec.getTimestamp()));
            count++;
        }
        table.print(out);
        out.printf("(Showing %d records, limit %d)\n", count, limit);
    }

    private void handlePrefix(List<String> tokens) {
        if (tokens.size() < 2) {
            out.println("Usage: prefix <prefix> [limit]");
            return;
        }
        String prefix = tokens.get(1);
        int limit = (tokens.size() > 2) ? Integer.parseInt(tokens.get(2)) : 50;

        AsciiTablePrinter table = new AsciiTablePrinter("Key", "Value", "Timestamp (ms)");
        List<DataRecord> records = queryEngine.streamPrefix(prefix).limit(limit).toList();

        for (DataRecord rec : records) {
            table.addRow(rec.getKey(), rec.getValueAsString(), String.valueOf(rec.getTimestamp()));
        }
        table.print(out);
        out.printf("(Showing %d matching prefix '%s')\n", records.size(), prefix);
    }

    private void handleFlush() {
        out.print("Flushing active MemTable to Level-0 SSTable... ");
        long t0 = System.currentTimeMillis();
        engine.flush();
        out.printf("Done (%d ms)\n", System.currentTimeMillis() - t0);
    }

    private void handleCompact() {
        out.print("Triggering compaction pass... ");
        long t0 = System.currentTimeMillis();
        engine.compact();
        out.printf("Done (%d ms)\n", System.currentTimeMillis() - t0);
    }

    private void handleStats() {
        EngineStats stats = engine.getStats();
        AsciiTablePrinter table = new AsciiTablePrinter("Metric", "Value");
        table.addRow("Total Write Ops (PUT)", String.format("%,d", stats.getPutRequests()));
        table.addRow("Total Read Ops (GET)", String.format("%,d", stats.getGetRequests()));
        table.addRow("Total Delete Ops", String.format("%,d", stats.getDeleteRequests()));
        table.addRow("MemTable In-Memory Hits", String.format("%,d", stats.getMemTableHits()));
        table.addRow("SSTable On-Disk Hits", String.format("%,d", stats.getSstableHits()));
        table.addRow("Point Read Misses", String.format("%,d", stats.getMisses()));
        table.addRow("MemTable Hit Ratio", String.format("%.2f%%", stats.getMemTableHitRatio() * 100.0));
        table.addRow("Bloom Filter Negative Prunes", String.format("%,d", stats.getBloomFilterPrunes()));
        table.addRow("Active SSTables (L0 + L1)", String.valueOf(stats.getActiveSSTableCount()));
        table.addRow("Disk Storage Footprint", stats.getDiskSizeBytes() + " bytes");
        table.addRow("Flushes Completed", String.valueOf(stats.getFlushRuns()));
        table.addRow("Compactions Completed", String.valueOf(stats.getCompactionRuns()));
        table.addRow("Total Bytes Written", String.format("%,d bytes", stats.getBytesWritten()));
        table.print(out);
    }

    private void handleCount() {
        long count = queryEngine.stream().count();
        out.printf("Total live records: %,d\n", count);
    }

    private void handleBegin() {
        if (activeTxn != null && activeTxn.isActive()) {
            out.println("[WARN] An active transaction is already open (Txn ID: " + activeTxn.getTransactionId() + ")");
            return;
        }
        activeTxn = txnManager.beginTransaction();
        out.printf("Transaction started [Txn ID: %d, Isolation: SNAPSHOT_ISOLATION]\n", activeTxn.getTransactionId());
    }

    private void handleTxnPut(List<String> tokens) {
        ensureTxnActive();
        if (tokens.size() < 3) {
            out.println("Usage: txn-put <key> <value>");
            return;
        }
        String key = tokens.get(1);
        String val = String.join(" ", tokens.subList(2, tokens.size()));
        activeTxn.put(key, val);
        out.println("Buffered PUT for key: '" + key + "' in Txn " + activeTxn.getTransactionId());
    }

    private void handleTxnGet(List<String> tokens) {
        ensureTxnActive();
        if (tokens.size() < 2) {
            out.println("Usage: txn-get <key>");
            return;
        }
        String key = tokens.get(1);
        String val = activeTxn.getAsString(key);
        if (val != null) {
            out.printf("\"%s\" (read in Txn %d)\n", val, activeTxn.getTransactionId());
        } else {
            out.printf("(nil) (read in Txn %d)\n", activeTxn.getTransactionId());
        }
    }

    private void handleTxnDelete(List<String> tokens) {
        ensureTxnActive();
        if (tokens.size() < 2) {
            out.println("Usage: txn-delete <key>");
            return;
        }
        String key = tokens.get(1);
        activeTxn.delete(key);
        out.println("Buffered DELETE for key: '" + key + "' in Txn " + activeTxn.getTransactionId());
    }

    private void handleCommit() {
        ensureTxnActive();
        long id = activeTxn.getTransactionId();
        activeTxn.commit();
        activeTxn = null;
        out.printf("Transaction %d successfully committed (OCC validated).\n", id);
    }

    private void handleRollback() {
        ensureTxnActive();
        long id = activeTxn.getTransactionId();
        activeTxn.rollback();
        activeTxn = null;
        out.printf("Transaction %d rolled back.\n", id);
    }

    private void handleBenchmark(List<String> tokens) {
        int writes = (tokens.size() > 1) ? Integer.parseInt(tokens.get(1)) : 10_000;
        int reads = (tokens.size() > 2) ? Integer.parseInt(tokens.get(2)) : 10_000;
        int threads = (tokens.size() > 3) ? Integer.parseInt(tokens.get(3)) : 4;
        new BenchmarkRunner(engine, out).runBenchmark(writes, reads, threads);
    }

    private void ensureTxnActive() {
        if (activeTxn == null || !activeTxn.isActive()) {
            throw new IllegalStateException("No active transaction. Run 'begin' first.");
        }
    }

    private void printHelp() {
        AsciiTablePrinter table = new AsciiTablePrinter("Command", "Parameters", "Description");
        table.addRow("put", "<key> <val>", "Store a key-value record");
        table.addRow("get", "<key>", "Retrieve value for a key");
        table.addRow("delete", "<key>", "Delete a key (writes tombstone)");
        table.addRow("scan", "[start] [end] [limit]", "Range scan sorted records");
        table.addRow("prefix", "<prefix> [limit]", "Stream records by key prefix");
        table.addRow("count", "", "Count live non-tombstone records");
        table.addRow("flush", "", "Synchronously flush MemTable to disk");
        table.addRow("compact", "", "Manually trigger background compaction");
        table.addRow("stats", "", "Display engine metrics and hit ratios");
        table.addRow("begin", "", "Begin a new snapshot transaction");
        table.addRow("txn-put", "<key> <val>", "Buffer write within active transaction");
        table.addRow("txn-get", "<key>", "Read key within active transaction");
        table.addRow("txn-delete", "<key>", "Buffer delete within active transaction");
        table.addRow("commit", "", "Commit active transaction (OCC)");
        table.addRow("rollback", "", "Discard active transaction writes");
        table.addRow("benchmark", "[writes] [reads] [threads]", "Run multi-threaded stress benchmark");
        table.addRow("help", "", "Show this commands reference");
        table.addRow("exit", "", "Gracefully shutdown engine and quit");
        table.print(out);
    }
}
