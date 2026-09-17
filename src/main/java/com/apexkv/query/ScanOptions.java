package com.apexkv.query;

import java.util.Objects;

/**
 * Configuration options for scan operations including range bounds, limit, and predicates.
 */
public final class ScanOptions {
    private final KeyRange range;
    private final int limit;
    private final QueryPredicate filter;

    private ScanOptions(Builder builder) {
        this.range = builder.range;
        this.limit = builder.limit;
        this.filter = builder.filter;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ScanOptions defaults() {
        return new Builder().build();
    }

    public KeyRange getRange() {
        return range;
    }

    public int getLimit() {
        return limit;
    }

    public QueryPredicate getFilter() {
        return filter;
    }

    public static final class Builder {
        private KeyRange range = KeyRange.all();
        private int limit = Integer.MAX_VALUE;
        private QueryPredicate filter = QueryPredicate.any();

        public Builder range(KeyRange range) {
            this.range = Objects.requireNonNull(range, "range cannot be null");
            return this;
        }

        public Builder limit(int limit) {
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive: " + limit);
            }
            this.limit = limit;
            return this;
        }

        public Builder filter(QueryPredicate filter) {
            this.filter = Objects.requireNonNull(filter, "filter cannot be null");
            return this;
        }

        public ScanOptions build() {
            return new ScanOptions(this);
        }
    }
}
