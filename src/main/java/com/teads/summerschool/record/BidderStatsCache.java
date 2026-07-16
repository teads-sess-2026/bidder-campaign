package com.teads.summerschool.record;

import com.teads.summerschool.config.BidderProperties;
import com.teads.summerschool.creative.CreativeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-creative budget cache backed by Redis.
 *
 * <p>Key format: {@code {bidderId}_{creativeId}_budget}, value = remaining budget.
 * The SSP is the single owner of spend on these keys: it atomically decrements them
 * on each win. This bidder never writes spend — it only seeds missing keys with
 * SETNX/{@code setIfAbsent} (so a restart can't refill an already-spent budget) and
 * reads them to decide whether a creative can still spend.
 */
@Component
public class BidderStatsCache {

    private static final Logger log = LoggerFactory.getLogger(BidderStatsCache.class);

    private final BidderProperties properties;
    private final ReactiveRedisTemplate<String, String> redis;
    private final CreativeRepository creativeRepository;

    private final AtomicLong winCount = new AtomicLong(0);
    private final Deque<Double> recentWinPrices = new ArrayDeque<>();

    public BidderStatsCache(BidderProperties properties, ReactiveRedisTemplate<String, String> redis,
                             CreativeRepository creativeRepository) {
        this.properties = properties;
        this.redis = redis;
        this.creativeRepository = creativeRepository;
    }

    /** Redis key holding the remaining budget for one creative. */
    public String budgetKey(String creativeId) {
        return properties.getId() + "_" + creativeId + "_budget";
    }

    /**
     * Seed a creative's budget key if it doesn't exist yet (SETNX semantics). Called once per
     * creative on startup; never overwrites, so a bidder restart can't refill spent budget.
     */
    public Mono<Boolean> initBudget(String creativeId, double budget) {
        String key = budgetKey(creativeId);
        return redis.opsForValue().setIfAbsent(key, String.valueOf(budget))
                .doOnNext(seeded -> {
                    if (Boolean.TRUE.equals(seeded)) {
                        log.info("Creative budget seeded: {} = {}", key, budget);
                    } else {
                        log.info("Creative budget already exists, left untouched: {}", key);
                    }
                });
    }

    /**
     * Record a Kafka-confirmed win in local statistics (win count, rolling price window).
     * The Redis budget key is NOT touched here — the SSP is the single owner of budget
     * spend and decrements it atomically on each win.
     */
    public void recordWin(String creativeId, double clearingPrice) {
        winCount.incrementAndGet();
        synchronized (recentWinPrices) {
            recentWinPrices.addLast(clearingPrice);
            if (recentWinPrices.size() > properties.getStrategy().getWindowSize()) {
                recentWinPrices.pollFirst();
            }
        }
    }

    /** Remaining budget for a creative. Lazily initializes to the flat creative budget if missing. */
    public Mono<Double> getRemainingBudget(String creativeId) {
        String key = budgetKey(creativeId);
        double defaultBudget = properties.getCreativeBudget();
        return redis.opsForValue().get(key)
                .flatMap(val -> {
                    try {
                        return Mono.just(Double.parseDouble(val));
                    } catch (NumberFormatException e) {
                        return Mono.just(defaultBudget);
                    }
                })
                .switchIfEmpty(redis.opsForValue().setIfAbsent(key, String.valueOf(defaultBudget))
                        .thenReturn(defaultBudget));
    }

    /** Batch fetch remaining budgets for multiple creatives using MGET. Returns Map<creativeId, budget>. */
    public Mono<java.util.Map<String, Double>> getRemainingBudgets(List<String> creativeIds) {
        if (creativeIds.isEmpty()) {
            return Mono.just(java.util.Map.of());
        }

        List<String> keys = creativeIds.stream()
                .map(this::budgetKey)
                .toList();

        double defaultBudget = properties.getCreativeBudget();

        return redis.opsForValue().multiGet(keys)
                .map(values -> {
                    java.util.Map<String, Double> budgetMap = new java.util.HashMap<>();
                    for (int i = 0; i < creativeIds.size(); i++) {
                        String creativeId = creativeIds.get(i);
                        String value = values != null && i < values.size() ? values.get(i) : null;
                        double budget = defaultBudget;
                        if (value != null) {
                            try {
                                budget = Double.parseDouble(value);
                            } catch (NumberFormatException e) {
                                budget = defaultBudget;
                            }
                        }
                        budgetMap.put(creativeId, budget);
                    }
                    return budgetMap;
                });
    }

    public long getWinCount() {
        return winCount.get();
    }

    public double getRollingAverageWinPrice() {
        synchronized (recentWinPrices) {
            if (recentWinPrices.isEmpty()) return 0.0;
            return recentWinPrices.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
    }

    public long getSampleCount() {
        return winCount.get();
    }
}
