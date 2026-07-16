package com.teads.summerschool.bidding;

import com.teads.summerschool.bidding.dto.BidRequest;

import java.util.Objects;

/**
 * Immutable segment identifier for grouping bids by targeting dimensions.
 * Used as a key for per-segment performance tracking and adaptive weight storage.
 *
 * <p>Current segmentation: geo × deviceType (2D).
 * Future expansion: could include audienceSegment for finer-grained optimization.
 */
public record BidSegment(String geo, String deviceType) {

    public BidSegment {
        Objects.requireNonNull(geo, "geo cannot be null");
        Objects.requireNonNull(deviceType, "deviceType cannot be null");
        if (geo.isBlank()) {
            throw new IllegalArgumentException("geo cannot be blank");
        }
        if (deviceType.isBlank()) {
            throw new IllegalArgumentException("deviceType cannot be blank");
        }
    }

    /**
     * Extract segment from a bid request's targeting dimensions.
     */
    public static BidSegment from(BidRequest request) {
        if (request.targeting() == null) {
            throw new IllegalArgumentException("BidRequest targeting cannot be null");
        }
        String geo = request.targeting().geo();
        String deviceType = request.targeting().deviceType();

        // Default to "UNKNOWN" if targeting fields are missing
        if (geo == null || geo.isBlank()) {
            geo = "UNKNOWN";
        }
        if (deviceType == null || deviceType.isBlank()) {
            deviceType = "UNKNOWN";
        }

        return new BidSegment(geo, deviceType);
    }

    /**
     * Redis key suffix format: "{geo}_{device}".
     * Full key will be prefixed by bidder ID in cache layer.
     */
    public String toRedisKey() {
        return geo + "_" + deviceType;
    }

    @Override
    public String toString() {
        return geo + "-" + deviceType;
    }
}
