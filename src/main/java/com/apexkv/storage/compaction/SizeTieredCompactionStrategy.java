package com.apexkv.storage.compaction;

import com.apexkv.storage.sstable.SSTableReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Size-Tiered Compaction Strategy.
 * Triggers compaction when the number of SSTables within Level 0 reaches or exceeds
 * the configured threshold (e.g. 4 tables), merging all candidates into Level 1.
 */
public class SizeTieredCompactionStrategy implements CompactionStrategy {

    @Override
    public boolean shouldCompact(List<SSTableReader> tables, int threshold) {
        return tables != null && tables.size() >= threshold;
    }

    @Override
    public List<SSTableReader> selectCandidates(List<SSTableReader> tables, int threshold) {
        if (!shouldCompact(tables, threshold)) {
            return List.of();
        }
        // Select all tables currently in the level for complete consolidation
        return new ArrayList<>(tables);
    }
}
