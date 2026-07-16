package com.teads.summerschool.creative;

import java.io.Serializable;

/**
 * Lightweight DTO for caching creatives in Redis. Contains only the fields
 * needed for bidding logic (targeting and pricing constraints). Excludes
 * display fields (name, description, imageUrl, callToAction) which are
 * only needed when building the bid response, and budget which is tracked
 * separately in Redis via BidderStatsCache.
 */
public record CachedCreative(
        String id,
        String bidderId,
        Double maxBidPrice,          // nullable - no cap if null
        String allowedGeos,          // CSV format, empty means no restriction
        String allowedDevices,       // CSV format, empty means no restriction
        String audienceSegments      // CSV format, empty means no restriction
) implements Serializable {

    /**
     * Convert from full Creative entity to cached form.
     */
    public static CachedCreative fromCreative(Creative c) {
        return new CachedCreative(
                c.getId(),
                c.getBidderId(),
                c.getMaxBidPrice(),
                c.getAllowedGeos(),
                c.getAllowedDevices(),
                c.getAudienceSegments()
        );
    }

    /**
     * Check if this creative's max bid price allows bidding at the given floor price.
     */
    public boolean isWithinMaxBid(double floorPrice) {
        return maxBidPrice == null || maxBidPrice >= floorPrice;
    }

    /**
     * Check if this creative matches the given targeting criteria.
     * Requires ALL three dimensions to match (strict matching).
     */
    public boolean matches(String geo, String deviceType, String audienceSegment) {
        return matchesField(allowedGeos, geo)
                && matchesField(allowedDevices, deviceType)
                && matchesField(audienceSegments, audienceSegment);
    }

    /**
     * Partial match: geo and device MUST match, but audience segment is optional.
     * Use as fallback when strict matching yields no results.
     */
    public boolean matchesGeoAndDevice(String geo, String deviceType) {
        return matchesField(allowedGeos, geo)
                && matchesField(allowedDevices, deviceType);
    }

    private boolean matchesField(String allowed, String value) {
        // Creative accepts any value (no restriction)
        if (allowed == null || allowed.isBlank()) return true;
        // Request has no targeting value (wildcard - matches any creative)
        if (value == null || value.isBlank()) return true;
        // Check if request value matches one of creative's allowed values
        for (String entry : allowed.split(",")) {
            if (entry.trim().equalsIgnoreCase(value)) return true;
        }
        return false;
    }
}
