package com.teads.summerschool.bidding;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Performance metrics for a single segment (geo × device).
 *
 * <p>Tracks auction outcomes to inform weight adjustment decisions:
 * - Total auctions participated
 * - Wins vs losses
 * - Recent clearing prices (for volatility detection)
 * - Total spend
 *
 * <p>Mutable: updated on each auction outcome. Thread-safety provided by SegmentStatsCache.
 */
public class SegmentStats {

    private static final int WINDOW_SIZE = 50;

    private long auctionCount = 0;
    private long winCount = 0;
    private double totalSpend = 0.0;
    private final Deque<Double> recentClearingPrices = new ArrayDeque<>();

    /**
     * Record an auction outcome (win or loss).
     */
    public void recordOutcome(boolean won, double clearingPrice) {
        auctionCount++;
        if (won) {
            winCount++;
            totalSpend += clearingPrice;
        }

        // Track recent clearing prices for volatility detection
        recentClearingPrices.addLast(clearingPrice);
        if (recentClearingPrices.size() > WINDOW_SIZE) {
            recentClearingPrices.pollFirst();
        }
    }

    /**
     * Win rate: wins / total auctions.
     */
    public double getWinRate() {
        return auctionCount > 0 ? (double) winCount / auctionCount : 0.0;
    }

    /**
     * Average spend per auction (including losses where spend = 0).
     */
    public double getAverageSpend() {
        return auctionCount > 0 ? totalSpend / auctionCount : 0.0;
    }

    /**
     * Coefficient of variation for recent clearing prices (stddev / mean).
     * High CV (>0.15) indicates volatile market conditions.
     */
    public double getClearingPriceVolatility() {
        if (recentClearingPrices.size() < 10) {
            return 0.0; // Not enough data
        }

        double mean = recentClearingPrices.stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);

        if (mean < 0.001) {
            return 0.0; // Avoid division by zero
        }

        double variance = recentClearingPrices.stream()
            .mapToDouble(p -> Math.pow(p - mean, 2))
            .average()
            .orElse(0.0);

        return Math.sqrt(variance) / mean;
    }

    public long getAuctionCount() {
        return auctionCount;
    }

    public long getWinCount() {
        return winCount;
    }

    public double getTotalSpend() {
        return totalSpend;
    }

    @Override
    public String toString() {
        return String.format("Stats[auctions=%d, wins=%d, winRate=%.2f%%, spend=%.2f, volatility=%.3f]",
            auctionCount, winCount, getWinRate() * 100, totalSpend, getClearingPriceVolatility());
    }
}
