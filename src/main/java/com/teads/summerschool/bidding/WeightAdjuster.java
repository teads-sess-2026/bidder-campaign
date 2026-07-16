package com.teads.summerschool.bidding;

import com.teads.summerschool.config.BidderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adaptive weight adjustment algorithm.
 *
 * <p>Adjusts weights based on auction outcomes to optimize efficiency:
 * - Win with high efficiency (clearing ≈ bid) → reinforce current weights
 * - Win with low efficiency (clearing << bid) → increase floor weight (cheaper)
 * - Loss with small gap → increase floor/random weights (bid slightly higher)
 * - Loss with large gap → not our target segment, no adjustment
 *
 * <p>Simple fixed learning rate: no decay, no volatility boost (Phase 1-3).
 * Advanced features (learning rate decay, volatility detection) can be added in Phase 4.
 */
@Component
public class WeightAdjuster {

    private static final Logger log = LoggerFactory.getLogger(WeightAdjuster.class);

    private final BidderProperties properties;

    public WeightAdjuster(BidderProperties properties) {
        this.properties = properties;
    }

    /**
     * Adjust weights based on auction outcome.
     *
     * @param segment The segment being adjusted
     * @param won Whether we won the auction
     * @param ourBid Our bid price
     * @param clearingPrice The winning price
     * @param currentWeights Current weights for this segment
     * @param stats Performance statistics for this segment
     * @return New adjusted weights
     */
    public AdaptiveWeights adjust(BidSegment segment, boolean won, double ourBid,
                                   double clearingPrice, AdaptiveWeights currentWeights,
                                   SegmentStats stats) {

        double learningRate = properties.getAdaptiveStrategy().getLearningRate();
        double efficiencyThreshold = properties.getAdaptiveStrategy().getEfficiencyThreshold();
        double maxShift = properties.getAdaptiveStrategy().getMaxWeightShift();

        // Start with current weights
        double newFloor = currentWeights.floorWeight();
        double newMarket = currentWeights.marketWeight();
        double newRandom = currentWeights.randomWeight();

        if (won) {
            // We won - check efficiency
            double efficiency = clearingPrice / ourBid;

            if (efficiency < efficiencyThreshold) {
                // Overbid by >10%: we're bidding too high
                // Increase floor weight (cheaper component), decrease market weight
                newFloor += learningRate * 0.10;
                newMarket -= learningRate * 0.10;

                log.debug("Segment {} WIN inefficient (eff={:.2f}): increase floor weight",
                    segment, efficiency);
            } else {
                // Efficient win: reinforce current distribution slightly
                // No change needed - current weights are working well
                log.debug("Segment {} WIN efficient (eff={:.2f}): no adjustment",
                    segment, efficiency);
            }

        } else {
            // We lost - check how close we were
            double gap = clearingPrice - ourBid;
            double gapPercent = gap / clearingPrice;

            if (gapPercent < 0.10) {
                // Lost by <10%: very close, bid slightly more aggressively
                newFloor += learningRate * 0.05;
                newRandom += learningRate * 0.03;
                newMarket -= learningRate * 0.08;

                log.debug("Segment {} LOSS close (gap={:.2f}%): increase aggressiveness",
                    segment, gapPercent * 100);

            } else if (gapPercent < 0.20) {
                // Lost by 10-20%: moderate loss, increase market tracking
                newMarket += learningRate * 0.05;
                newFloor -= learningRate * 0.03;
                newRandom -= learningRate * 0.02;

                log.debug("Segment {} LOSS moderate (gap={:.2f}%): increase market weight",
                    segment, gapPercent * 100);

            } else {
                // Lost by >20%: probably not our target segment, no adjustment
                log.debug("Segment {} LOSS large (gap={:.2f}%): no adjustment",
                    segment, gapPercent * 100);
            }
        }

        // Clamp individual weight changes to maxShift
        newFloor = clamp(newFloor, currentWeights.floorWeight(), maxShift);
        newMarket = clamp(newMarket, currentWeights.marketWeight(), maxShift);
        newRandom = clamp(newRandom, currentWeights.randomWeight(), maxShift);

        // Normalize to sum = 1.0
        AdaptiveWeights adjusted = AdaptiveWeights.of(newFloor, newMarket, newRandom);

        log.info("Segment {} adjusted: {} -> {} (outcome={}, eff/gap={:.2f})",
            segment, currentWeights, adjusted, won ? "WIN" : "LOSS",
            won ? clearingPrice / ourBid : (clearingPrice - ourBid) / clearingPrice);

        return adjusted;
    }

    /**
     * Clamp a weight change to maxShift from original value.
     */
    private double clamp(double newValue, double originalValue, double maxShift) {
        double maxIncrease = originalValue + maxShift;
        double maxDecrease = Math.max(0, originalValue - maxShift);
        return Math.min(maxIncrease, Math.max(maxDecrease, newValue));
    }
}
