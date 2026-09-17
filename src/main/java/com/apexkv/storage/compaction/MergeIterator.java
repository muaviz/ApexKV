package com.apexkv.storage.compaction;

import com.apexkv.core.DataRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * K-Way Merge-Sort iterator over multiple sorted SSTable or MemTable iterators.
 * Uses a min-heap {@link PriorityQueue} to streamingly deduplicate records in O(N log K) time,
 * always yielding the freshest version of each key and optionally purging tombstones.
 */
public class MergeIterator implements Iterator<DataRecord> {
    private final PriorityQueue<PeekingIterator> minHeap;
    private final boolean purgeTombstones;
    private DataRecord nextRecord;

    public MergeIterator(List<Iterator<DataRecord>> iterators, boolean purgeTombstones) {
        Objects.requireNonNull(iterators, "iterators cannot be null");
        this.purgeTombstones = purgeTombstones;

        Comparator<PeekingIterator> comparator = (a, b) -> {
            DataRecord recA = a.peek();
            DataRecord recB = b.peek();
            int keyCmp = recA.getKey().compareTo(recB.getKey());
            if (keyCmp != 0) {
                return keyCmp;
            }
            // Newer timestamp has higher precedence (descending)
            return Long.compare(recB.getTimestamp(), recA.getTimestamp());
        };

        this.minHeap = new PriorityQueue<>(Math.max(1, iterators.size()), comparator);

        for (Iterator<DataRecord> it : iterators) {
            if (it != null && it.hasNext()) {
                minHeap.offer(new PeekingIterator(it));
            }
        }

        advance();
    }

    private void advance() {
        nextRecord = null;

        while (nextRecord == null && !minHeap.isEmpty()) {
            PeekingIterator winnerIter = minHeap.poll();
            DataRecord candidate = winnerIter.next();
            String targetKey = candidate.getKey();

            if (winnerIter.hasNext()) {
                minHeap.offer(winnerIter);
            }

            // Consume and discard all stale/older versions of the same key across other iterators
            List<PeekingIterator> drainedIterators = new ArrayList<>();
            while (!minHeap.isEmpty() && minHeap.peek().peek().getKey().equals(targetKey)) {
                PeekingIterator duplicateIter = minHeap.poll();
                duplicateIter.next(); // Discard older duplicate record
                if (duplicateIter.hasNext()) {
                    drainedIterators.add(duplicateIter);
                }
            }
            minHeap.addAll(drainedIterators);

            // Check if this record is a tombstone to be purged
            if (purgeTombstones && candidate.isTombstone()) {
                // Discard deleted key entirely; disk space reclaimed!
                continue;
            }

            nextRecord = candidate;
        }
    }

    @Override
    public boolean hasNext() {
        return nextRecord != null;
    }

    @Override
    public DataRecord next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        DataRecord current = nextRecord;
        advance();
        return current;
    }

    /**
     * Helper wrapper enabling 1-step lookahead on an Iterator.
     */
    private static class PeekingIterator implements Iterator<DataRecord> {
        private final Iterator<DataRecord> underlying;
        private DataRecord current;

        PeekingIterator(Iterator<DataRecord> underlying) {
            this.underlying = underlying;
            if (underlying.hasNext()) {
                this.current = underlying.next();
            } else {
                this.current = null;
            }
        }

        DataRecord peek() {
            return current;
        }

        @Override
        public boolean hasNext() {
            return current != null;
        }

        @Override
        public DataRecord next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            DataRecord result = current;
            current = underlying.hasNext() ? underlying.next() : null;
            return result;
        }
    }
}
