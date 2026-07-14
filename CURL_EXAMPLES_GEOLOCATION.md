# Geolocation API - Curl Examples

## Geolocation Endpoints

### Get Full Geolocation Data

```bash
# Swedish IP
curl -s "http://localhost:8080/api/geolocation?ip=91.128.103.196" | python3 -m json.tool

# Google DNS (US)
curl -s "http://localhost:8080/api/geolocation?ip=8.8.8.8" | python3 -m json.tool

# Cloudflare DNS (Australia)
curl -s "http://localhost:8080/api/geolocation?ip=1.1.1.1" | python3 -m json.tool
```

### Get Country Code Only

```bash
# Swedish IP → SE
curl "http://localhost:8080/api/geolocation/country?ip=91.128.103.196"

# Google DNS → US
curl "http://localhost:8080/api/geolocation/country?ip=8.8.8.8"

# UK IP
curl "http://localhost:8080/api/geolocation/country?ip=81.2.69.142"
```

### Get City Only

```bash
# Swedish IP → Stockholm
curl "http://localhost:8080/api/geolocation/city?ip=91.128.103.196"

# Google DNS → Mountain View
curl "http://localhost:8080/api/geolocation/city?ip=8.8.8.8"

# London IP → London
curl "http://localhost:8080/api/geolocation/city?ip=81.2.69.142"
```

## Bid Request with IP Address

Send a bid request that includes an IP address for geolocation enrichment:

```bash
# Bid request from Swedish IP
curl -X POST http://localhost:8080/api/bid \
  -H 'Content-Type: application/json' \
  -d '{
    "request_id": "test-geo-sweden",
    "floor_price": 0.10,
    "targeting": {
      "geo": "SE",
      "device_type": "mobile",
      "audience_segment": "tech"
    },
    "ip_address": "91.128.103.196"
  }'

# Bid request from US IP
curl -X POST http://localhost:8080/api/bid \
  -H 'Content-Type: application/json' \
  -d '{
    "request_id": "test-geo-us",
    "floor_price": 0.15,
    "targeting": {
      "geo": "US",
      "device_type": "desktop",
      "audience_segment": "business"
    },
    "ip_address": "8.8.8.8"
  }'

# Bid request from UK IP
curl -X POST http://localhost:8080/api/bid \
  -H 'Content-Type: application/json' \
  -d '{
    "request_id": "test-geo-uk",
    "floor_price": 0.12,
    "targeting": {
      "geo": "GB",
      "device_type": "tablet",
      "audience_segment": "finance"
    },
    "ip_address": "81.2.69.142"
  }'
```

## Sample Response Structure

Full geolocation response includes:

```json
{
  "ip": "91.128.103.196",
  "location": {
    "continent_code": "EU",
    "continent_name": "Europe",
    "country_code2": "SE",
    "country_code3": "SWE",
    "country_name": "Sweden",
    "country_name_official": "Kingdom of Sweden",
    "country_capital": "Stockholm",
    "state_prov": "Stockholms län",
    "state_code": "SE-AB",
    "city": "Stockholm",
    "latitude": "59.40510",
    "longitude": "17.95510",
    "is_eu": true
  },
  "country_metadata": {
    "calling_code": "+46",
    "tld": ".se",
    "languages": ["sv-SE", "se", "sma", "fi-SE"]
  },
  "currency": {
    "code": "SEK",
    "name": "Swedish Krona",
    "symbol": "kr"
  },
  "asn": {
    "as_number": "AS1257",
    "organization": "Tele2 Sverige AB",
    "country": "SE"
  },
  "time_zone": {
    "name": "Europe/Stockholm",
    "offset": 1,
    "offset_with_dst": 2,
    "is_dst": true
  }
}
```

## Test Different Regions

```bash
# Europe
curl "http://localhost:8080/api/geolocation/country?ip=91.128.103.196"  # Sweden
curl "http://localhost:8080/api/geolocation/country?ip=81.2.69.142"    # UK
curl "http://localhost:8080/api/geolocation/country?ip=82.165.165.1"   # Germany

# North America
curl "http://localhost:8080/api/geolocation/country?ip=8.8.8.8"        # US
curl "http://localhost:8080/api/geolocation/country?ip=99.224.0.1"     # Canada

# Asia
curl "http://localhost:8080/api/geolocation/country?ip=202.12.27.33"   # Japan
curl "http://localhost:8080/api/geolocation/country?ip=1.0.16.0"       # China

# Oceania
curl "http://localhost:8080/api/geolocation/country?ip=1.1.1.1"        # Australia
```

## Performance Testing

Test multiple requests:

```bash
# Quick loop test
for ip in 8.8.8.8 1.1.1.1 91.128.103.196 81.2.69.142; do
  echo -n "$ip -> "
  curl -s "http://localhost:8080/api/geolocation/city?ip=$ip"
  echo ""
done
```

## Debugging

Check if the API key is working:

```bash
# Check environment variable in container
docker exec ss2026-bidder env | grep GEOLOCATION

# Check application logs for geolocation errors
docker logs ss2026-bidder 2>&1 | grep -i geolocation

# Test the API directly (should work)
curl -s "https://api.ipgeolocation.io/v3/ipgeo?apiKey=791813e96fa34c8a8a4d14f9816c0417&ip=8.8.8.8"
```

## Notes

- The geolocation service has a 2-second timeout
- Failed lookups return empty responses without blocking bid requests
- The API key is configured in `config.env` and passed via `docker-compose.yml`
- All endpoints are non-blocking and use reactive programming (Mono/Flux)
