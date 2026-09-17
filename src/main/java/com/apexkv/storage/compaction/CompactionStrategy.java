package com.apexkv.storage.compaction;

import com.apexkv.storage.sstable.SSTableReader;

import java.util.List;

/**
 * Strategy interface governing when and which SSTables are selected for compaction.
 */
public interface CompactionStrategy {

    /**
     * Determines whether compaction should be triggered based on active tables.
     *
     * @param tables    list of current tables in the level
     * @param threshold minimum table count threshold
     * @return true if compaction is required
     */
    boolean shouldCompact(List<SSTableReader> tables, int threshold);

    /**
     * Selects candidate SSTables for consolidation.
     *
     * @param tables    list of current tables
     * @param threshold minimum count threshold
     * @return candidate tables to be merged
     */
    List<SSTableReader> selectCandidates(List<SSTableReader> tables, int threshold);
}
