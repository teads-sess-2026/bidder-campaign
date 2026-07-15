package com.teads.summerschool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "bidder")
public class BidderProperties {

    private String id = "teads-bidder";
    private double budget = 1000.0;
    // Flat budget assigned to each creative on seed; remaining is tracked per creative in Redis.
    private double creativeBudget = 25.0;
    private long timeoutMs = 1000;
    private Strategy strategy = new Strategy();
    private Competition competition = new Competition();
    private AdaptiveStrategy adaptiveStrategy = new AdaptiveStrategy();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public double getBudget() { return budget; }
    public void setBudget(double budget) { this.budget = budget; }

    public double getCreativeBudget() { return creativeBudget; }
    public void setCreativeBudget(double creativeBudget) { this.creativeBudget = creativeBudget; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

    public Strategy getStrategy() { return strategy; }
    public void setStrategy(Strategy strategy) { this.strategy = strategy; }

    public Competition getCompetition() { return competition; }
    public void setCompetition(Competition competition) { this.competition = competition; }

    public AdaptiveStrategy getAdaptiveStrategy() { return adaptiveStrategy; }
    public void setAdaptiveStrategy(AdaptiveStrategy adaptiveStrategy) { this.adaptiveStrategy = adaptiveStrategy; }

    public static class Strategy {
        private int minSamples = 10;
        private double coldStartMultiplier = 1.15;
        private int windowSize = 50;
        private double marketMultiplier = 1.05;
        private double premiumMultiplier = 1.5;
        private double pacingBoost = 1.20;
        private double pacingCut = 0.85;

        public int getMinSamples() { return minSamples; }
        public void setMinSamples(int minSamples) { this.minSamples = minSamples; }

        public double getColdStartMultiplier() { return coldStartMultiplier; }
        public void setColdStartMultiplier(double coldStartMultiplier) { this.coldStartMultiplier = coldStartMultiplier; }

        public int getWindowSize() { return windowSize; }
        public void setWindowSize(int windowSize) { this.windowSize = windowSize; }

        public double getMarketMultiplier() { return marketMultiplier; }
        public void setMarketMultiplier(double marketMultiplier) { this.marketMultiplier = marketMultiplier; }

        public double getPremiumMultiplier() { return premiumMultiplier; }
        public void setPremiumMultiplier(double premiumMultiplier) { this.premiumMultiplier = premiumMultiplier; }

        public double getPacingBoost() { return pacingBoost; }
        public void setPacingBoost(double pacingBoost) { this.pacingBoost = pacingBoost; }

        public double getPacingCut() { return pacingCut; }
        public void setPacingCut(double pacingCut) { this.pacingCut = pacingCut; }
    }

    public static class Competition {
        // ISO-8601 instant, e.g. "2026-06-01T09:00:00Z". Empty = pacing disabled.
        private String startTime = "";
        private long durationSeconds = 1800;

        public String getStartTime() { return startTime; }
        public void setStartTime(String startTime) { this.startTime = startTime; }

        public long getDurationSeconds() { return durationSeconds; }
        public void setDurationSeconds(long durationSeconds) { this.durationSeconds = durationSeconds; }
    }

    /**
     * Adaptive bidding strategy configuration.
     * Simple and minimal: 6 parameters, each with clear purpose.
     */
    public static class AdaptiveStrategy {
        private boolean enabled = false;           // Feature flag (off by default)
        private double learningRate = 0.05;        // Fixed 5% adjustment per update
        private int minSamples = 20;               // Wait for data before adapting
        private double efficiencyThreshold = 0.90; // Target 90%+ efficiency (clearing/bid)
        private double maxWeightShift = 0.05;      // Cap adjustments at 5% per update
        private double explorationNoise = 0.05;    // Random factor ±5%

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public double getLearningRate() { return learningRate; }
        public void setLearningRate(double learningRate) { this.learningRate = learningRate; }

        public int getMinSamples() { return minSamples; }
        public void setMinSamples(int minSamples) { this.minSamples = minSamples; }

        public double getEfficiencyThreshold() { return efficiencyThreshold; }
        public void setEfficiencyThreshold(double efficiencyThreshold) { this.efficiencyThreshold = efficiencyThreshold; }

        public double getMaxWeightShift() { return maxWeightShift; }
        public void setMaxWeightShift(double maxWeightShift) { this.maxWeightShift = maxWeightShift; }

        public double getExplorationNoise() { return explorationNoise; }
        public void setExplorationNoise(double explorationNoise) { this.explorationNoise = explorationNoise; }
    }
}
