package com.apexkv.query;

import java.util.Objects;

/**
 * Encapsulates range boundaries for key scanning operations in ApexKV.
 */
public final class KeyRange {
    private final String startKey;
    private final String endKey;
    private final boolean startInclusive;
    private final boolean endInclusive;

    public KeyRange(String startKey, String endKey, boolean startInclusive, boolean endInclusive) {
        this.startKey = startKey;
        this.endKey = endKey;
        this.startInclusive = startInclusive;
        this.endInclusive = endInclusive;
    }

    /**
     * Represents an unbounded scan over all keys.
     */
    public static KeyRange all() {
        return new KeyRange(null, null, true, false);
    }

    /**
     * Half-open range [startKey, endKey).
     */
    public static KeyRange halfOpen(String startKey, String endKey) {
        return new KeyRange(startKey, endKey, true, false);
    }

    /**
     * Fully closed range [startKey, endKey].
     */
    public static KeyRange closed(String startKey, String endKey) {
        return new KeyRange(startKey, endKey, true, true);
    }

    /**
     * Fully open range (startKey, endKey).
     */
    public static KeyRange open(String startKey, String endKey) {
        return new KeyRange(startKey, endKey, false, false);
    }

    /**
     * Prefix range covering all keys starting with the specified prefix.
     */
    public static KeyRange prefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix cannot be null");
        if (prefix.isEmpty()) {
            return all();
        }
        // Calculate successor string for prefix boundary
        StringBuilder next = new StringBuilder(prefix);
        int lastCharIdx = next.length() - 1;
        char nextChar = (char) (next.charAt(lastCharIdx) + 1);
        next.setCharAt(lastCharIdx, nextChar);
        return new KeyRange(prefix, next.toString(), true, false);
    }

    /**
     * Checks whether a candidate key falls within this range.
     */
    public boolean contains(String key) {
        if (key == null) return false;

        if (startKey != null) {
            int startCmp = key.compareTo(startKey);
            if (startInclusive ? startCmp < 0 : startCmp <= 0) {
                return false;
            }
        }

        if (endKey != null) {
            int endCmp = key.compareTo(endKey);
            if (endInclusive ? endCmp > 0 : endCmp >= 0) {
                return false;
            }
        }

        return true;
    }

    public String getStartKey() {
        return startKey;
    }

    public String getEndKey() {
        return endKey;
    }

    public boolean isStartInclusive() {
        return startInclusive;
    }

    public boolean isEndInclusive() {
        return endInclusive;
    }

    @Override
    public String toString() {
        return (startInclusive ? "[" : "(") +
                (startKey == null ? "-inf" : startKey) + ", " +
                (endKey == null ? "+inf" : endKey) +
                (endInclusive ? "]" : ")");
    }
}
