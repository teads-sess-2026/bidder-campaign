package com.teads.summerschool.creative;

import com.teads.summerschool.config.BidderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.*;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Lookup for this bidder's creative catalog. Originally snapshotted the catalog once
 * (right after CreativeSeeder seeded it) to save a Postgres round trip per bid() call,
 * but creatives can be added/removed after startup faster than any cache could track —
 * a stale snapshot then either hides a just-added creative or keeps matching one that's
 * already gone. getAll() reads straight from Postgres each time to stay correct;
 * revisit only with a cache invalidated by the write path itself, not time.
 */
@Component
public class CreativeCache {

    private static final Logger log = LoggerFactory.getLogger(CreativeCache.class);
    private static final Duration TTL = Duration.ofHours(1);

    private final CreativeRepository repository;
    private final BidderProperties properties;
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    // In-memory cache to avoid Redis roundtrip on every bid
    private volatile java.util.List<CachedCreative> memoryCache = null;

    public CreativeCache(CreativeRepository repository, BidderProperties properties,
                         ReactiveRedisTemplate<String, String> redisTemplate) {
        this.repository = repository;
        this.properties = properties;
        this.redisTemplate = redisTemplate;
    }

    /** Redis hash key for all creatives: creative:campaign */
    private String creativeHashKey() {
        return "creative:" + properties.getId();
    }

    /** Serialize CachedCreative to Base64 string */
    private String serialize(CachedCreative creative) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(creative);
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize creative", e);
        }
    }

    /** Deserialize CachedCreative from Base64 string */
    private CachedCreative deserialize(String str) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(Base64.getDecoder().decode(str));
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (CachedCreative) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize creative", e);
        }
    }

    public Flux<Creative> getAll() {
        return repository.findByBidderId(properties.getId());
    }

    /**
     * Fetch all creatives for bidding with in-memory caching.
     * Returns CachedCreative (lightweight DTO with only bidding fields).
     * Uses in-memory cache to avoid Redis roundtrip on every bid.
     */
    public Flux<CachedCreative> getAllCached() {
        if (memoryCache != null) {
            return Flux.fromIterable(memoryCache);
        }

        // Memory cache miss - load from DB and cache in memory
        return repository.findByBidderId(properties.getId())
                .map(CachedCreative::fromCreative)
                .collectList()
                .doOnNext(creatives -> {
                    memoryCache = creatives;
                    log.info("Loaded {} creatives into memory cache", creatives.size());
                })
                .flatMapMany(Flux::fromIterable);
    }

    /** Load creatives from DB and populate Redis hash */
    private Flux<CachedCreative> loadFromDatabaseAndCache() {
        return repository.findByBidderId(properties.getId())
                .map(CachedCreative::fromCreative)
                .collectList()
                .flatMapMany(creatives -> {
                    if (creatives.isEmpty()) {
                        return Flux.empty();
                    }

                    Map<String, String> hashEntries = creatives.stream()
                            .collect(Collectors.toMap(CachedCreative::id, this::serialize));

                    return redisTemplate.opsForHash()
                            .putAll(creativeHashKey(), hashEntries)
                            .then(redisTemplate.expire(creativeHashKey(), TTL))
                            .thenMany(Flux.fromIterable(creatives))
                            .doOnComplete(() ->
                                    log.info("Cached {} creatives to Redis hash", creatives.size()));
                });
    }

    /** Fallback: load directly from DB without caching (Redis is down) */
    private Flux<CachedCreative> loadFromDatabaseDirectly() {
        return repository.findByBidderId(properties.getId())
                .map(CachedCreative::fromCreative);
    }

    /** Invalidate the entire creative cache for this bidder */
    public Mono<Void> invalidate() {
        memoryCache = null;
        return redisTemplate.delete(creativeHashKey())
                .then()
                .doOnSuccess(v -> log.info("Creative cache invalidated"));
    }

    /** Kept for CreativeSeeder, which logs the catalog size right after seeding. */
    public Mono<Void> refresh() {
        memoryCache = null;
        return invalidate()
                .then(loadFromDatabaseAndCache().count())
                .doOnNext(n -> log.info("Creative catalog seeded: {} creatives", n))
                .then();
    }
}
