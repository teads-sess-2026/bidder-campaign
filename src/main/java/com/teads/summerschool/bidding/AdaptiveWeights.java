package com.teads.summerschool.bidding;

import java.util.Random;

/**
 * Immutable weights for adaptive bid price computation.
 *
 * <p>Three-factor model:
 * - floorWeight: emphasis on floor price (baseline, always wins if bid)
 * - marketWeight: emphasis on market clearing prices (competitive intelligence)
 * - randomWeight: emphasis on random exploration (prevents local optima)
 *
 * <p>Weights should sum to ~1.0 (tolerance ±0.05 for rounding).
 *
 * <p>Pure in-memory: no persistence. Weights reset on restart but converge quickly
 * (~200 auctions per segment, ~10 minutes).
 */
public record AdaptiveWeights(
    double floorWeight,
    double marketWeight,
    double randomWeight
) {

    private static final Random RANDOM = new Random();
    private static final double TOLERANCE = 0.05;

    public AdaptiveWeights {
        if (floorWeight < 0 || marketWeight < 0 || randomWeight < 0) {
            throw new IllegalArgumentException("All weights must be non-negative");
        }
        double sum = floorWeight + marketWeight + randomWeight;
        if (Math.abs(sum - 1.0) > TOLERANCE) {
            throw new IllegalArgumentException(
                String.format("Weights must sum to ~1.0 (got %.3f)", sum)
            );
        }
    }

    /**
     * Create safe random weights for cold-start initialization.
     *
     * <p>Bounded ranges ensure reasonable starting behavior:
     * - Floor weight dominates (bid slightly above floor)
     * - Market weight provides some competitive awareness
     * - Random weight enables exploration
     *
     * <p>Normalized to sum exactly 1.0.
     */
    public static AdaptiveWeights randomBounded() {
        double floor = 0.50 + RANDOM.nextDouble() * 0.20;   // [0.50, 0.70]
        double market = 0.20 + RANDOM.nextDouble() * 0.20;  // [0.20, 0.40]
        double random = 0.05 + RANDOM.nextDouble() * 0.10;  // [0.05, 0.15]

        return normalize(floor, market, random);
    }

    /**
     * Create default weights for when no segment-specific data exists.
     * Conservative: heavily floor-based with some market awareness.
     */
    public static AdaptiveWeights defaultWeights() {
        return new AdaptiveWeights(0.60, 0.30, 0.10);
    }

    /**
     * Normalize three weights to sum exactly 1.0.
     */
    private static AdaptiveWeights normalize(double floor, double market, double random) {
        double sum = floor + market + random;
        return new AdaptiveWeights(
            floor / sum,
            market / sum,
            random / sum
        );
    }

    /**
     * Create normalized weights from raw values (ensures sum = 1.0).
     */
    public static AdaptiveWeights of(double floor, double market, double random) {
        return normalize(floor, market, random);
    }

    @Override
    public String toString() {
        return String.format("Weights[floor=%.3f, market=%.3f, random=%.3f]",
            floorWeight, marketWeight, randomWeight);
    }
}
