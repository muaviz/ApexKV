# Project Statement: ApexKV

**Course:** CSE2006 — Programming in Java  
**Student Name:** Muaviz Mushtaq Shah  
**Registration Number:** 24BCY10184  
**Faculty Name:** Dr. Adarsh Patel  
**Institution:** Vellore Institute of Technology (VIT)  

---

## 1. Problem Statement
Traditional relational and key-value database storage architectures that rely heavily on balanced tree structures (such as B-Trees and $B^+$Trees) perform disk updates in-place. Under intensive write workloads, in-place disk modifications result in significant random I/O bottlenecks, high write amplification, and severe disk seek overhead on traditional storage drives and solid-state media. 

Furthermore, embedded data management applications frequently require low-latency point reads, atomic multi-key transactions, and rich analytical querying without the operational overhead, heavy footprint, and external dependency vulnerabilities of complex standalone database clusters.

Existing lightweight embedded Java key-value stores often suffer from either:
1. Complete lack of crash durability (in-memory only data structures).
2. High write stalls caused by naive synchronous file rewrites.
3. Inability to execute declarative, complex queries directly over high-throughput storage records without pulling entire datasets into JVM heap memory.

**ApexKV** addresses these fundamental limitations by implementing a pure Java 17+ Log-Structured Merge (LSM) Tree storage engine integrated with an append-only Write-Ahead Log (WAL), probabilistic Bloom filters, background multi-way merge compaction, optimistic concurrency control (OCC) transactions, and a fluent Java Streams analytical query engine.

---

## 2. Scope of the Project

The scope of ApexKV encompasses the engineering of an enterprise-grade, embedded, thread-safe, crash-durable key-value storage engine implemented with zero external runtime dependencies. 

The project boundaries cover:
* **Storage Hierarchy:** In-memory sorted write buffers (`MemTable` backed by `ConcurrentSkipListMap`), append-only disk logging (`WriteAheadLog` with CRC32 framing), and immutable sorted disk tables (`SSTable` pair files containing binary data blocks and sparse block indices).
* **Fault Tolerance & Durability:** Automated crash recovery replaying binary logs upon database boot, resilient handling of torn writes at end-of-file, and bit-rot detection through CRC32 verification.
* **Storage Maintenance:** Dedicated background daemon performing size-tiered multi-way merge compaction (`CompactionEngine` using a min-heap `PriorityQueue`) to deduplicate superseded records and reclaim disk space from deleted keys (tombstones).
* **Concurrency & Transactions:** Multi-threaded read-write synchronization via `ReentrantReadWriteLock` and ACID-compliant snapshot transactions governed by an Optimistic Concurrency Control (`TransactionManager`) coordinator.
* **Declarative Query Engine:** High-level query interface (`StreamQueryEngine`) bridging storage disk iterators with the Java 8+ Streams API, providing prefix streaming, custom functional predicates, and grouping aggregations.
* **User & Developer Interfaces:** An interactive terminal shell (CLI REPL), scriptable batch execution mode, and a programmatic Java SDK.

*Out of Scope:* Distributed network cluster consensus (Raft/Paxos) and network socket protocols (e.g., HTTP REST / Redis RESP), which are reserved as future architectural extensions.

---

## 3. Target Users

1. **Embedded Java Application Developers:** Software engineers building desktop applications, microservices, edge computing nodes, or IoT gateways requiring a zero-dependency, ultra-fast, embedded persistent store.
2. **Systems Software & Storage Researchers:** Computer science students, researchers, and systems engineers studying low-level storage mechanics, LSM-Tree dynamics, I/O amplification trade-offs, and probabilistic algorithms in modern managed runtimes.
3. **Data Infrastructure Architects:** Engineers evaluating write-optimized storage patterns for write-heavy telemetry, event sourcing, auditing, and time-series logging workloads.

---

## 4. High-Level Features

* **Log-Structured Merge Architecture:** Converts random disk writes into high-throughput sequential appends via active in-memory skip lists and immutable on-disk SSTables.
* **Zero-Data-Loss Crash Durability:** Strictly framed binary Write-Ahead Log (WAL) with CRC32 checksum verification guaranteeing crash recovery and isolating corrupted or torn writes.
* **Probabilistic Negative Lookup Pruning:** Custom Bit-Vector Bloom Filter utilizing double-hashing MurmurHash3 to reject non-existent key lookups in $O(1)$ time without disk seeks.
* **Bounded Sparse Indexing:** In-memory sparse block index enabling $O(\log K)$ binary search and minimizing memory footprint to $<1\%$ of dataset size.
* **Background Multi-Way Merge Compaction:** Asynchronous daemon thread executing K-way merge-sort deduplication across Level-0 SSTables to consolidate files and physically purge tombstones.
* **ACID Snapshot Transactions (OCC):** Multi-key transactional operations with private read/write buffers, read-your-own-writes semantics, and optimistic conflict detection at commit time.
* **Fluent Stream Query Engine:** Seamless integration with standard Java Streams API supporting custom lambda predicates, key-range slicing, regex matching, and grouping aggregations.
* **Interactive CLI Shell & Diagnostics:** Rich terminal REPL featuring ASCII tabular output formatting, command execution timers, engine metrics monitoring, and built-in multi-threaded benchmarking.
* **Zero External Dependencies:** 100% pure Java 17+ implementation utilizing standard Java SE libraries and JUnit 5 for testing.
