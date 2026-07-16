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
import java.util.Arrays;
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

    // Last successfully computed budget.remaining, served when a scrape's
    // computation times out instead of blocking the scrape thread forever.
    private volatile double lastKnownBudget = 0.0;

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
    void registerBudgetGauge() {
        metrics.registerGauge("budget.remaining", this::getRemainingBudgetSafe);
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
        // TODO: implement your bidding strategy
        // Hints:
        //   1. Record the request with buildRecord(request)
        //   2. Find matching creatives with matchingCreatives(request, creativeCache.getAll())
        //   3. Filter creatives whose maxBidPrice covers this floor: c.isWithinMaxBid(request.floorPrice())
        //   4. Filter creatives that still have budget: statsCache.getRemainingBudget(c.getId()) > 0
        //      (returns a Mono<Double> — flatMap/filterWhen into it)
        //   5. Compute a bid price with computeBidPrice(request)
        //   6. Record metrics: metrics.recordRequest(), metrics.recordBid(), metrics.recordNoBid(reason)
        //   7. Call ownBidCache.record(requestId, creativeId, bidPrice) so AuctionNoticeConsumer
        //      can look this bid up without a DB round trip
        //   8. Save the BidRecord with bidRecordRepository.save(record) and return
        //      Optional.of(new BidResponse(...)) or Optional.empty()
        //
        // Example: Use geolocation to enrich targeting
        // if (request.ipAddress() != null) {
        //     return geolocationService.getCountryCode(request.ipAddress())
        //         .flatMap(countryCode -> {
        //             // Use countryCode for geo-targeting logic
        //             return processBidWithGeo(request, countryCode);
        //         })
        //         .switchIfEmpty(processBidWithGeo(request, null));
        // }

        metrics.recordRequest();
        BidRecord record = buildRecord(request);

        return creativeCache.getAllCached()
                .collectList()
                .flatMap(cachedCreatives -> {
                    if (cachedCreatives.isEmpty()) {
                        record.setNoBidReason("no_eligible_creative");
                        record.setLatencyMs((int) ((System.nanoTime() - start) / 1_000_000));
                        metrics.recordNoBid("no_eligible_creative");
                        metrics.recordLatency(record.getLatencyMs());
                        return bidRecordRepository.save(record).thenReturn(Optional.empty());
                    }

                    // F3: Filter creatives by max bid price (checked BEFORE targeting and budget)
                    List<CachedCreative> affordableCreatives = cachedCreatives.stream()
                            .filter(c -> c.isWithinMaxBid(request.floorPrice()))
                            .toList();

                    if (affordableCreatives.isEmpty()) {
                        record.setNoBidReason("floor_exceeds_max_bid");
                        record.setLatencyMs((int) ((System.nanoTime() - start) / 1_000_000));
                        metrics.recordNoBid("floor_exceeds_max_bid");
                        metrics.recordLatency(record.getLatencyMs());
                        return bidRecordRepository.save(record).thenReturn(Optional.empty());
                    }

                    // F1: Filter creatives by audience targeting (geo, device, audience_segment)
                    // Try strict matching first (all three dimensions)
                    List<CachedCreative> strictMatches = affordableCreatives.stream()
                            .filter(c -> c.matches(
                                    request.targeting().geo(),
                                    request.targeting().deviceType(),
                                    request.targeting().audienceSegment()))
                            .toList();

                    // Fallback: if no strict matches, relax audience_segment requirement
                    // (geo and device still must match - they're critical for format/region)
                    final List<CachedCreative> matchingCreatives;
                    if (strictMatches.isEmpty()) {
                        List<CachedCreative> fallbackMatches = affordableCreatives.stream()
                                .filter(c -> c.matchesGeoAndDevice(
                                        request.targeting().geo(),
                                        request.targeting().deviceType()))
                                .toList();

                        if (!fallbackMatches.isEmpty()) {
                            log.debug("Fallback to geo+device match (ignoring audience_segment) for request {}",
                                    request.requestId());
                            matchingCreatives = fallbackMatches;
                        } else {
                            matchingCreatives = List.of();
                        }
                    } else {
                        matchingCreatives = strictMatches;
                    }

                    if (matchingCreatives.isEmpty()) {
                        record.setNoBidReason("targeting_miss");
                        record.setLatencyMs((int) ((System.nanoTime() - start) / 1_000_000));
                        metrics.recordNoBid("targeting_miss");
                        metrics.recordLatency(record.getLatencyMs());
                        return bidRecordRepository.save(record).thenReturn(Optional.empty());
                    }

                    // F2: Filter creatives by budget (must have remaining budget > 0) - batch fetch with MGET
                    List<String> creativeIds = matchingCreatives.stream()
                            .map(c -> c.id())
                            .toList();

                    return statsCache.getRemainingBudgets(creativeIds)
                            .flatMap(budgetMap -> {
                                List<CachedCreative> eligibleCreatives = matchingCreatives.stream()
                                        .filter(c -> budgetMap.getOrDefault(c.id(), 0.0) > 0)
                                        .toList();

                                if (eligibleCreatives.isEmpty()) {
                                    record.setNoBidReason("budget_exhausted");
                                    record.setLatencyMs((int) ((System.nanoTime() - start) / 1_000_000));
                                    metrics.recordNoBid("budget_exhausted");
                                    metrics.recordLatency(record.getLatencyMs());
                                    return bidRecordRepository.save(record).thenReturn(Optional.empty());
                                }

                                // Budget-weighted selection: creatives with more remaining budget
                                // have higher probability of being selected, naturally balancing
                                // usage across the portfolio without sacrificing performance
                                CachedCreative selectedCached = selectCreativeByBudgetWeight(
                                        eligibleCreatives,
                                        budgetMap,
                                        properties.getStrategy().isWeightedSelectionEnabled()
                                );

                                double computedBidPrice = computeBidPrice(request);

                                // Cap bid at creative's maxBidPrice (if set)
                                final double bidPrice;
                                if (selectedCached.maxBidPrice() != null && computedBidPrice > selectedCached.maxBidPrice()) {
                                    bidPrice = selectedCached.maxBidPrice();
                                    log.debug("Capped bid to creative maxBidPrice: {}", bidPrice);
                                } else {
                                    bidPrice = computedBidPrice;
                                }

                                record.setCreativeId(selectedCached.id());
                                record.setBidPrice(bidPrice);
                                record.setLatencyMs((int) ((System.nanoTime() - start) / 1_000_000));

                                ownBidCache.record(request.requestId(), selectedCached.id(), bidPrice,
                                                   BidSegment.from(request));

                                metrics.recordLatency(record.getLatencyMs());

                                // Fetch full Creative from DB for response fields (name, imageUrl, etc)
                                return creativeCache.getAll()
                                        .filter(c -> c.getId().equals(selectedCached.id()))
                                        .next()
                                        .flatMap(fullCreative -> {
                                            BidResponse response = new BidResponse(
                                                    request.requestId(),
                                                    bidPrice,
                                                    toCreativeDto(fullCreative)
                                            );
                                            return bidRecordRepository.save(record).thenReturn(Optional.of(response));
                                        })
                                        .switchIfEmpty(Mono.defer(() -> {
                                            log.warn("Creative {} not found in DB after cache lookup", selectedCached.id());
                                            record.setNoBidReason("creative_not_found");
                                            metrics.recordNoBid("creative_not_found");
                                            return bidRecordRepository.save(record).thenReturn(Optional.empty());
                                        }));
                            });
                });
    }

    private double computeBidPrice(BidRequest request) {
        // Static fallback if adaptive disabled
        if (!properties.getAdaptiveStrategy().isEnabled()) {
            return request.floorPrice() * 1.01;
        }

        // Adaptive weight-based pricing (100ms SLA: pure in-memory, <0.01ms)
        BidSegment segment = BidSegment.from(request);
        AdaptiveWeights weights = weightsCache.getWeightsSync(segment);

        double floor = request.floorPrice();
        double market = Math.max(statsCache.getRollingAverageWinPrice(), floor);

        // Random exploration with gaussian noise (prevents local optima)
        double randomFactor = 1.0 + random.nextGaussian() *
            properties.getAdaptiveStrategy().getExplorationNoise();
        double randomComponent = floor * Math.max(0.95, Math.min(1.05, randomFactor));

        // Weighted combination
        double bid = weights.floorWeight() * floor +
                     weights.marketWeight() * market +
                     weights.randomWeight() * randomComponent;

        // Safety: never below floor * 1.001 (must beat floor price)
        return Math.max(floor * 1.001, bid);
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
