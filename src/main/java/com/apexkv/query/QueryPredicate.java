package com.apexkv.query;

import com.apexkv.core.DataRecord;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Functional interface for filtering DataRecords within the StreamQueryEngine.
 */
@FunctionalInterface
public interface QueryPredicate {

    /**
     * Evaluates this predicate on the given record.
     */
    boolean test(DataRecord record);

    default QueryPredicate and(QueryPredicate other) {
        Objects.requireNonNull(other);
        return record -> test(record) && other.test(record);
    }

    default QueryPredicate or(QueryPredicate other) {
        Objects.requireNonNull(other);
        return record -> test(record) || other.test(record);
    }

    default QueryPredicate negate() {
        return record -> !test(record);
    }

    /**
     * Predicate matching records whose key matches a regular expression.
     */
    static QueryPredicate keyMatches(String regex) {
        Pattern pattern = Pattern.compile(regex);
        return record -> record != null && pattern.matcher(record.getKey()).matches();
    }

    /**
     * Predicate matching records whose key starts with a prefix.
     */
    static QueryPredicate keyStartsWith(String prefix) {
        Objects.requireNonNull(prefix);
        return record -> record != null && record.getKey().startsWith(prefix);
    }

    /**
     * Predicate matching records whose string value contains a substring.
     */
    static QueryPredicate valueContains(String substring) {
        Objects.requireNonNull(substring);
        return record -> {
            if (record == null || record.isTombstone()) return false;
            String valStr = record.getValueAsString();
            return valStr != null && valStr.contains(substring);
        };
    }

    /**
     * Always-true predicate.
     */
    static QueryPredicate any() {
        return record -> true;
    }
}
