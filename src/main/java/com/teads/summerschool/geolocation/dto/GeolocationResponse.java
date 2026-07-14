package com.teads.summerschool.geolocation.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GeolocationResponse(
        String ip,
        Location location,
        @JsonProperty("country_metadata") CountryMetadata countryMetadata,
        Currency currency,
        Asn asn,
        @JsonProperty("time_zone") TimeZone timeZone
) {}
