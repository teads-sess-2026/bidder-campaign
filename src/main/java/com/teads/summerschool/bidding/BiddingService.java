package com.teads.summerschool.bidding;

import com.teads.summerschool.bidding.dto.BidRequest;
import com.teads.summerschool.bidding.dto.BidResponse;
import com.teads.summerschool.bidding.dto.CreativeDto;
import com.teads.summerschool.config.BidderProperties;
import com.teads.summerschool.creative.CachedCreative;
import com.teads.summerschool.creative.Creative;
import com.teads.summerschool.creative.CreativeCache;
import com.teads.summerschool.geolocation.GeolocationService;
import com.teads.summerschool.metrics.BidderMetrics;
import com.teads.summerschool.record.BidRecord;
import com.teads.summerschool.record.BidRecordRepository;
import com.teads.summerschool.record.BidderStatsCache;
import com.teads.summerschool.record.OwnBidCache;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

@Service
public class BiddingService {

    private static final Logger log = LoggerFactory.getLogger(BiddingService.class);

    private final Random random = new Random();

    private final BidderProperties properties;
    private final CreativeCache creativeCache;
    private final BidRecordRepository bidRecordRepository;
    private final BidderStatsCache statsCache;
    private final BidderMetrics metrics;
    private final OwnBidCache ownBidCache;
    private final GeolocationService geolocationService;
    private final AdaptiveWeightsCache weightsCache;

    private double lastKnownBudget = 0.0;

    // Pacing state
    private Instant firstBidTime = null;
    private double initialBudget = -1;

    // In-memory creative DTO cache to avoid DB round-trip per bid
    private Map<String, CreativeDto> creativeDtoCache = new HashMap<>();

    public BiddingService(BidderProperties properties,
                          CreativeCache creativeCache,
                          BidRecordRepository bidRecordRepository,
                          BidderStatsCache statsCache,
                          BidderMetrics metrics,
                          OwnBidCache ownBidCache,
                          GeolocationService geolocationService,
                          AdaptiveWeightsCache weightsCache) {
        this.properties = properties;
        this.creativeCache = creativeCache;
        this.bidRecordRepository = bidRecordRepository;
        this.statsCache = statsCache;
        this.metrics = metrics;
        this.ownBidCache = ownBidCache;
        this.geolocationService = geolocationService;
        this.weightsCache = weightsCache;
    }

    @PostConstruct
    void init() {
        metrics.registerGauge("budget.remaining", this::getRemainingBudgetSafe);
        warmCreativeDtoCache();
    }

    /**
     * getRemainingBudget() does a DB query plus one Redis call per creative — under
     * DB/Redis pool contention (e.g. remote backing services with WAN latency) it can
     * queue for a connection indefinitely. /actuator/prometheus has no timeout of its
     * own, so an unbounded gauge supplier here stalls the entire scrape response.
     * Bound it the same way /api/bid bounds biddingService.bid(), and fall back to the
     * last known value instead of blocking Prometheus forever.
     *
     * <p>Micrometer's Gauge contract takes a plain synchronous Supplier<Number>, polled by the
     * Prometheus scrape thread — there's no reactive variant, so this is the one sanctioned
     * .block() outside of startup/Kafka-listener boundaries elsewhere in this codebase.
     */
    private double getRemainingBudgetSafe() {
        try {
            Double value = getRemainingBudget()
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .onErrorReturn(lastKnownBudget)
                    .block();
            lastKnownBudget = value;
            return value;
        } catch (Exception ex) {
            return lastKnownBudget;
        }
    }

    public Mono<Optional<BidResponse>> bid(BidRequest request) {
        long start = System.nanoTime();
        metrics.summerschool_bids.increment();
        metrics.recordRequest();
        BidRecord record = buildRecord(request);

        return creativeCache.getAllCached()
                .collectList()
                .flatMap(cachedCreatives -> {
                    if (cachedCreatives.isEmpty()) {
                        return noBid(record, start, "no_eligible_creative");
                    }

                    // F3: max bid price check BEFORE targeting and budget
                    List<CachedCreative> affordableCreatives = cachedCreatives.stream()
                            .filter(c -> c.isWithinMaxBid(request.floorPrice()))
                            .toList();

                    if (affordableCreatives.isEmpty()) {
                        return noBid(record, start, "floor_exceeds_max_bid");
                    }

                    // F1: audience targeting (all three dimensions must pass)
                    List<CachedCreative> matchingCreatives = affordableCreatives.stream()
                            .filter(c -> c.matches(
                                    request.targeting().geo(),
                                    request.targeting().deviceType(),
                                    request.targeting().audienceSegment()))
                            .toList();

                    if (matchingCreatives.isEmpty()) {
                        return noBid(record, start, "targeting_miss");
                    }

                    // F2: budget check via Redis MGET (single round-trip)
                    List<String> creativeIds = matchingCreatives.stream()
                            .map(c -> c.id())
                            .toList();

                    return statsCache.getRemainingBudgets(creativeIds)
                            .flatMap(budgetMap -> {
                                List<CachedCreative> eligibleCreatives = matchingCreatives.stream()
                                        .filter(c -> budgetMap.getOrDefault(c.id(), 0.0) > 0)
                                        .toList();

                                if (eligibleCreatives.isEmpty()) {
                                    return noBid(record, start, "budget_exhausted");
                                }

                                // Pacing: probabilistically skip bids to spread budget over duration
                                double totalRemaining = budgetMap.values().stream()
                                        .filter(b -> b > 0).mapToDouble(Double::doubleValue).sum();
                                if (!shouldBid(totalRemaining)) {
                                    return noBid(record, start, "pacing");
                                }

                                // Specificity-first: prefer narrow-targeted creatives,
                                // reserve broad catch-alls for auctions with no specific match
                                int maxSpecificity = eligibleCreatives.stream()
                                        .mapToInt(CachedCreative::specificityScore)
                                        .max().orElse(0);
                                List<CachedCreative> topTier = eligibleCreatives.stream()
                                        .filter(c -> c.specificityScore() >= maxSpecificity - 3)
                                        .toList();

                                CachedCreative selectedCached = selectCreativeByBudgetWeight(
                                        topTier.isEmpty() ? eligibleCreatives : topTier,
                                        budgetMap,
                                        properties.getStrategy().isWeightedSelectionEnabled()
                                );

                                double bidPrice = computeBidPrice(request);

                                if (selectedCached.maxBidPrice() != null && bidPrice > selectedCached.maxBidPrice()) {
                                    bidPrice = selectedCached.maxBidPrice();
                                }

                                record.setCreativeId(selectedCached.id());
                                record.setBidPrice(bidPrice);
                                record.setLatencyMs((int) ((System.nanoTime() - start) / 1_000_000));

                                ownBidCache.record(request.requestId(), selectedCached.id(), bidPrice,
                                                   BidSegment.from(request));

                                metrics.recordLatency(record.getLatencyMs());

                                // Build response from in-memory DTO cache (no DB round-trip)
                                CreativeDto dto = creativeDtoCache.get(selectedCached.id());
                                if (dto == null) {
                                    dto = new CreativeDto(
                                            selectedCached.id(), selectedCached.id(), "", "", "",
                                            splitCsv(selectedCached.allowedGeos()),
                                            splitCsv(selectedCached.allowedDevices()),
                                            splitCsv(selectedCached.audienceSegments())
                                    );
                                }

                                BidResponse response = new BidResponse(
                                        request.requestId(), bidPrice, dto);

                                // Fire-and-forget: don't block response on DB write
                                bidRecordRepository.save(record).subscribe();

                                return Mono.just(Optional.<BidResponse>of(response));
                            });
                });
    }

    private Mono<Optional<BidResponse>> noBid(BidRecord record, long startNanos, String reason) {
        record.setNoBidReason(reason);
        record.setLatencyMs((int) ((System.nanoTime() - startNanos) / 1_000_000));
        metrics.recordNoBid(reason);
        metrics.recordLatency(record.getLatencyMs());
        bidRecordRepository.save(record).subscribe();
        return Mono.just(Optional.empty());
    }

    /**
     * Pacing gate: probabilistically skip bids to spread budget evenly over competition duration.
     * All in-memory, <0.01ms — no I/O.
     */
    private boolean shouldBid(double remainingBudget) {
        long durationSeconds = properties.getCompetition().getDurationSeconds();
        if (durationSeconds <= 0) {
            return true;
        }

        if (firstBidTime == null) {
            firstBidTime = Instant.now();
            initialBudget = remainingBudget;
        }

        if (initialBudget <= 0) {
            return true;
        }

        double elapsed = Duration.between(firstBidTime, Instant.now()).toMillis() / 1000.0;
        double fractionTimeElapsed = Math.min(1.0, elapsed / durationSeconds);
        double fractionBudgetRemaining = remainingBudget / initialBudget;

        // Target: budget remaining should roughly equal time remaining
        // If we've used more budget than time elapsed, throttle
        double targetBudgetRemaining = 1.0 - fractionTimeElapsed;
        double pace = fractionBudgetRemaining / Math.max(0.01, targetBudgetRemaining);

        // pace > 1 = ahead of schedule (have budget to spare), always bid
        // pace < 1 = behind schedule (spending too fast), probabilistically skip
        if (pace >= 1.0) {
            return true;
        }

        // Probability of bidding = pace^2 (quadratic throttle: aggressive when overspending)
        return random.nextDouble() < (pace * pace);
    }

    /**
     * Warm the creative DTO cache on first use (called lazily).
     * Populates display fields so bid() never needs a DB round-trip.
     */
    public void warmCreativeDtoCache() {
        creativeCache.getAll()
                .doOnNext(c -> creativeDtoCache.put(c.getId(), toCreativeDto(c)))
                .subscribe();
    }

    private double computeBidPrice(BidRequest request) {
        double floor = request.floorPrice();

        if (!properties.getAdaptiveStrategy().isEnabled()) {
            return floor * 1.01;
        }

        BidSegment segment = BidSegment.from(request);
        AdaptiveWeights weights = weightsCache.getWeightsSync(segment);

        double market = statsCache.getRollingAverageWinPrice();

        // If no market data yet, bid conservatively just above floor
        if (market <= floor) {
            return floor * 1.005;
        }

        // Bid between floor and market — never above market price
        double randomFactor = 1.0 + random.nextGaussian() *
            properties.getAdaptiveStrategy().getExplorationNoise();
        double randomComponent = floor * Math.max(0.98, Math.min(1.02, randomFactor));

        double bid = weights.floorWeight() * floor +
                     weights.marketWeight() * Math.min(market, floor * 1.3) +
                     weights.randomWeight() * randomComponent;

        // Never below floor * 1.001, never above market (avoid overpaying)
        bid = Math.max(floor * 1.001, bid);
        bid = Math.min(bid, market);

        return bid;
    }

    /** Total remaining budget across all this bidder's creatives. */
    public Mono<Double> getRemainingBudget() {
        return creativeCache.getAllCached()
                .flatMap(c -> statsCache.getRemainingBudget(c.id()))
                .reduce(0.0, Double::sum);
    }

    /** Remaining budget per creative id. */
    public Mono<Map<String, Double>> getRemainingBudgets() {
        return creativeCache.getAllCached()
                .flatMap(c -> statsCache.getRemainingBudget(c.id()).map(budget -> Map.entry(c.id(), budget)))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new);
    }

    private Flux<Creative> matchingCreatives(BidRequest request, Flux<Creative> all) {
        return all.filter(c -> c.matches(
                        request.targeting().geo(),
                        request.targeting().deviceType(),
                        request.targeting().audienceSegment()));
    }

    private Flux<CachedCreative> matchingCachedCreatives(BidRequest request, Flux<CachedCreative> all) {
        return all.filter(c -> c.matches(
                        request.targeting().geo(),
                        request.targeting().deviceType(),
                        request.targeting().audienceSegment()));
    }

    private CreativeDto toCreativeDto(Creative creative) {
        return new CreativeDto(
                creative.getId(),
                creative.getName(),
                creative.getDescription(),
                creative.getImageUrl(),
                creative.getCallToAction(),
                splitCsv(creative.getAllowedGeos()),
                splitCsv(creative.getAllowedDevices()),
                splitCsv(creative.getAudienceSegments())
        );
    }

    private List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private BidRecord buildRecord(BidRequest request) {
        BidRecord record = new BidRecord();
        record.setRequestId(request.requestId());
        record.setFloorPrice(request.floorPrice());
        if (request.targeting() != null) {
            record.setGeo(request.targeting().geo());
            record.setDeviceType(request.targeting().deviceType());
            record.setAudienceSegment(request.targeting().audienceSegment());
        }
        return record;
    }

    /**
     * Select a creative from eligible list using budget-weighted random selection.
     * Creatives with more remaining budget have higher probability of selection,
     * naturally balancing spend across the portfolio.
     *
     * @param eligibleCreatives List of creatives that passed all filters
     * @param budgetMap Map of creative ID to remaining budget
     * @param useWeightedSelection If false, falls back to uniform random selection
     * @return Selected creative
     */
    private CachedCreative selectCreativeByBudgetWeight(
            List<CachedCreative> eligibleCreatives,
            java.util.Map<String, Double> budgetMap,
            boolean useWeightedSelection) {

        if (!useWeightedSelection || eligibleCreatives.size() == 1) {
            return eligibleCreatives.get(random.nextInt(eligibleCreatives.size()));
        }

        // Calculate total budget across all eligible creatives
        double totalBudget = 0.0;
        for (CachedCreative creative : eligibleCreatives) {
            totalBudget += budgetMap.getOrDefault(creative.id(), 0.0);
        }

        // Edge case: all budgets are 0 (shouldn't happen as we filter these out)
        if (totalBudget <= 0.0) {
            return eligibleCreatives.get(random.nextInt(eligibleCreatives.size()));
        }

        // Weighted random selection using roulette wheel algorithm
        double randomValue = random.nextDouble() * totalBudget;
        double cumulativeWeight = 0.0;

        for (CachedCreative creative : eligibleCreatives) {
            cumulativeWeight += budgetMap.getOrDefault(creative.id(), 0.0);
            if (randomValue <= cumulativeWeight) {
                return creative;
            }
        }

        // Fallback (rounding edge case)
        return eligibleCreatives.get(eligibleCreatives.size() - 1);
    }
}
