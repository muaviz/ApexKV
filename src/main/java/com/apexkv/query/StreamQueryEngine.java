package com.apexkv.query;

import com.apexkv.core.DataRecord;
import com.apexkv.core.StorageEngine;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Fluent query engine integrating ApexKV storage with Java 8+ Streams API.
 * Supports declarative filtering, regex pattern matching, prefix aggregation,
 * and key-value mapping over stored records.
 */
public class StreamQueryEngine {
    private final StorageEngine engine;

    public StreamQueryEngine(StorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine cannot be null");
    }

    /**
     * Converts a DataRecord iterator into a lazily-evaluated Java Stream.
     */
    private Stream<DataRecord> toStream(Iterator<DataRecord> iterator) {
        Spliterator<DataRecord> spliterator = Spliterators.spliteratorUnknownSize(
                iterator,
                Spliterator.ORDERED | Spliterator.NONNULL
        );
        return StreamSupport.stream(spliterator, false);
    }

    /**
     * Streams all active non-tombstone records in the database.
     */
    public Stream<DataRecord> stream() {
        return toStream(engine.scan(null, null));
    }

    /**
     * Streams all records within the half-open interval [startKey, endKey).
     */
    public Stream<DataRecord> streamRange(String startKey, String endKey) {
        return toStream(engine.scan(startKey, endKey));
    }

    /**
     * Streams all records having keys matching the given prefix.
     */
    public Stream<DataRecord> streamPrefix(String prefix) {
        KeyRange range = KeyRange.prefix(prefix);
        return toStream(engine.scan(range.getStartKey(), range.getEndKey()))
                .filter(record -> record.getKey().startsWith(prefix));
    }

    /**
     * Streams all records matching a custom functional predicate.
     */
    public Stream<DataRecord> streamFilter(QueryPredicate predicate) {
        return stream().filter(predicate::test);
    }

    /**
     * Streams records conforming to the specified {@link ScanOptions}.
     */
    public Stream<DataRecord> stream(ScanOptions options) {
        Objects.requireNonNull(options, "options cannot be null");
        KeyRange range = options.getRange();
        Stream<DataRecord> stream = toStream(engine.scan(range.getStartKey(), range.getEndKey()))
                .filter(r -> range.contains(r.getKey()))
                .filter(options.getFilter()::test);

        if (options.getLimit() < Integer.MAX_VALUE) {
            stream = stream.limit(options.getLimit());
        }
        return stream;
    }

    /**
     * Counts all records satisfying the given predicate.
     */
    public long countMatching(QueryPredicate predicate) {
        return streamFilter(predicate).count();
    }

    /**
     * Groups and aggregates record counts by their key prefix of given length.
     */
    public Map<String, Long> countByKeyPrefix(int prefixLength) {
        if (prefixLength <= 0) {
            throw new IllegalArgumentException("prefixLength must be positive: " + prefixLength);
        }
        return stream()
                .collect(Collectors.groupingBy(
                        record -> {
                            String key = record.getKey();
                            return key.length() >= prefixLength ? key.substring(0, prefixLength) : key;
                        },
                        Collectors.counting()
                ));
    }

    /**
     * Retrieves the top N records matching the given predicate.
     */
    public List<DataRecord> findTopN(QueryPredicate predicate, int n) {
        return streamFilter(predicate).limit(n).toList();
    }

    /**
     * Collects key-value string entries within range [startKey, endKey) into an in-memory Map.
     */
    public Map<String, String> toMap(String startKey, String endKey) {
        return streamRange(startKey, endKey)
                .collect(Collectors.toMap(
                        DataRecord::getKey,
                        record -> record.getValueAsString() != null ? record.getValueAsString() : "",
                        (v1, v2) -> v2
                ));
    }
}
