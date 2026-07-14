# IP Geolocation API Integration

This project integrates with the [ipgeolocation.io](https://ipgeolocation.io) API to enrich bid requests with geographic information.

## Configuration

The API key is configured in `config.env`:

```bash
GEOLOCATION_API_KEY=791813e96fa34c8a8a4d14f9816c0417
```

The key is loaded into `application.properties` via the environment variable:

```properties
geolocation.api.key=${GEOLOCATION_API_KEY:}
```

## Usage

### In BiddingService

The `GeolocationService` is injected into `BiddingService` and can be used to enrich targeting data:

```java
if (request.ipAddress() != null) {
    return geolocationService.getCountryCode(request.ipAddress())
        .flatMap(countryCode -> {
            // Use countryCode for geo-targeting logic
            log.info("Request from country: {}", countryCode);
            return processBidWithGeo(request, countryCode);
        })
        .switchIfEmpty(processBidWithGeo(request, null));
}
```

### Available Methods

- `getGeolocation(String ipAddress)` - Returns full geolocation data
- `getCountryCode(String ipAddress)` - Returns ISO country code (e.g., "SE", "US")
- `getCity(String ipAddress)` - Returns city name

### Test Endpoints

Test the geolocation API directly via REST endpoints:

```bash
# Full geolocation data
curl "http://localhost:8080/api/geolocation?ip=91.128.103.196"

# Country code only
curl "http://localhost:8080/api/geolocation/country?ip=91.128.103.196"

# City only
curl "http://localhost:8080/api/geolocation/city?ip=91.128.103.196"
```

## Response Structure

The API returns detailed geolocation information:

```json
{
  "ip": "91.128.103.196",
  "location": {
    "country_code2": "SE",
    "country_name": "Sweden",
    "city": "Stockholm",
    "latitude": "59.40510",
    "longitude": "17.95510",
    "is_eu": true
  },
  "currency": {
    "code": "SEK",
    "name": "Swedish Krona",
    "symbol": "kr"
  },
  "time_zone": {
    "name": "Europe/Stockholm",
    "offset": 1,
    "current_time": "2026-07-14 13:24:39.277+0200"
  }
}
```

## Error Handling

The service includes timeout (2 seconds) and error handling. If the API call fails:
- Logs an error message
- Returns `Mono.empty()` instead of failing the bid request
- This ensures that geolocation failures don't block bidding

## BidRequest Update

The `BidRequest` DTO now includes an `ipAddress` field:

```java
public record BidRequest(
    String requestId,
    double floorPrice,
    Targeting targeting,
    String ipAddress
) {}
```

Pass the user's IP address in bid requests to enable geolocation lookups.
