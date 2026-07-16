package com.teads.summerschool.bidding;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure in-memory cache for segment performance statistics.
 *
 * <p>Similar to AdaptiveWeightsCache: no persistence, converges quickly.
 * Thread-safety: ConcurrentHashMap + synchronized updates to SegmentStats.
 */
@Component
public class SegmentStatsCache {

    private final ConcurrentHashMap<BidSegment, SegmentStats> stats = new ConcurrentHashMap<>();

    /**
     * Record an auction outcome for a segment.
     * Creates new SegmentStats on first access.
     */
    public synchronized void recordOutcome(BidSegment segment, boolean won, double clearingPrice) {
        SegmentStats segmentStats = stats.computeIfAbsent(segment, s -> new SegmentStats());
        segmentStats.recordOutcome(won, clearingPrice);
    }

    /**
     * Get statistics for a segment (returns new empty stats if not tracked yet).
     */
    public SegmentStats getStats(BidSegment segment) {
        return stats.computeIfAbsent(segment, s -> new SegmentStats());
    }

    /**
     * Get all segment statistics (for monitoring).
     */
    public Map<BidSegment, SegmentStats> getAllStats() {
        return Map.copyOf(stats);
    }

    /**
     * Clear all statistics (for testing).
     */
    public void clear() {
        stats.clear();
    }
}
