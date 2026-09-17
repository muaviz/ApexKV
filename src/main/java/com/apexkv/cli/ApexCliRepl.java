package com.apexkv.cli;

import com.apexkv.core.ApexKVConfig;
import com.apexkv.core.ApexKVEngine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Main command-line interactive REPL (Read-Eval-Print Loop) and batch launcher for ApexKV.
 */
public class ApexCliRepl {
    private static final String BANNER = """
             _                   _  ____     __
            / \\   _ __   _____ _| |/ /\\ \\   / /
           / _ \\ | '_ \\ / _ \\ \\/ / ' /  \\ \\ / / 
          / ___ \\| |_) |  __/>  <| . \\   \\ V /  
         /_/   \\_\\ .__/ \\___/_/\\_\\_|\\_\\   \\_/   
                 |_| High-Performance LSM-Tree Engine
        """;

    public static void main(String[] args) {
        Path dataDir = ApexKVConfig.DEFAULT_DATA_DIR;
        String execCommand = null;
        boolean runBenchmark = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dir", "-d" -> {
                    if (i + 1 < args.length) {
                        dataDir = Paths.get(args[++i]);
                    }
                }
                case "--exec", "-e" -> {
                    if (i + 1 < args.length) {
                        execCommand = args[++i];
                    }
                }
                case "--benchmark", "-b" -> runBenchmark = true;
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
            }
        }

        ApexKVConfig config = ApexKVConfig.builder()
                .dataDirectory(dataDir)
                .build();

        try (ApexKVEngine engine = new ApexKVEngine(config)) {
            CommandHandler handler = new CommandHandler(engine, System.out);

            if (runBenchmark) {
                new BenchmarkRunner(engine, System.out).runBenchmark(10_000, 10_000, 4);
                return;
            }

            if (execCommand != null) {
                handler.execute(execCommand);
                return;
            }

            // Interactive Shell
            System.out.println(BANNER);
            System.out.println(" ApexKV Interactive Terminal Shell (v1.0.0)");
            System.out.printf(" Data Directory : %s\n", dataDir.toAbsolutePath());
            System.out.printf(" Java Runtime   : %s (%s)\n",
                    System.getProperty("java.version"), System.getProperty("java.vendor"));
            System.out.println(" Type 'help' for commands or 'exit' to quit.\n");

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                while (true) {
                    System.out.print("apexkv> ");
                    System.out.flush();
                    String line = reader.readLine();
                    if (line == null) {
                        System.out.println("\nGoodbye!");
                        break; // EOF
                    }
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    boolean keepGoing = handler.execute(line.trim());
                    if (!keepGoing) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Fatal engine error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("ApexKV - High-Performance LSM Key-Value Storage Engine");
        System.out.println("Usage: java -jar apexkv.jar [OPTIONS]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -d, --dir <path>       Specify custom database storage directory (default: ./data/apexkv)");
        System.out.println("  -e, --exec <command>   Execute single command batch and exit");
        System.out.println("  -b, --benchmark        Run built-in multi-threaded performance benchmark and exit");
        System.out.println("  -h, --help             Display this help manual");
    }
}
