package com.teads.summerschool.bidding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure in-memory cache for adaptive weights per segment.
 *
 * <p>Architecture for 100ms SLA:
 * - All reads are synchronous HashMap.get() (<0.01ms)
 * - All writes are in-memory only (no network I/O)
 * - No persistence: weights reset on restart but converge quickly
 *
 * <p>Thread-safety: ConcurrentHashMap provides lock-free reads and atomic writes.
 *
 * <p>Cold-start: New segments get random bounded weights, which adapt over ~200 auctions.
 */
@Component
public class AdaptiveWeightsCache {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveWeightsCache.class);

    // Unbounded: typical deployment has ~10-50 segments (few geos × few device types)
    private final ConcurrentHashMap<BidSegment, AdaptiveWeights> weights = new ConcurrentHashMap<>();

    /**
     * Get weights for a segment (synchronous, <0.01ms).
     *
     * <p>Returns default weights on first access to a new segment.
     * On subsequent calls, the segment will have learned weights from auction feedback.
     */
    public AdaptiveWeights getWeightsSync(BidSegment segment) {
        return weights.computeIfAbsent(segment, s -> {
            AdaptiveWeights initial = AdaptiveWeights.randomBounded();
            log.info("Cold-start segment {} with weights {}", segment, initial);
            return initial;
        });
    }

    /**
     * Update weights for a segment (synchronous, in-memory only).
     *
     * <p>Called from AuctionNoticeConsumer after weight adjustment.
     * Updates happen async (not in bid path), so synchronous write is fine.
     */
    public void updateWeightsSync(BidSegment segment, AdaptiveWeights newWeights) {
        AdaptiveWeights old = weights.put(segment, newWeights);
        log.debug("Updated weights for segment {}: {} -> {}", segment, old, newWeights);
    }

    /**
     * Get all active segments and their weights (for monitoring/debugging).
     */
    public Map<BidSegment, AdaptiveWeights> getAllWeights() {
        return Map.copyOf(weights);
    }

    /**
     * Count of active segments being tracked.
     */
    public int getSegmentCount() {
        return weights.size();
    }

    /**
     * Clear all weights (useful for testing or manual reset).
     */
    public void clear() {
        log.warn("Clearing all adaptive weights");
        weights.clear();
    }
}
