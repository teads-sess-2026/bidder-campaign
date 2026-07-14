package com.teads.summerschool.geolocation.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Location(
        @JsonProperty("continent_code") String continentCode,
        @JsonProperty("continent_name") String continentName,
        @JsonProperty("country_code2") String countryCode2,
        @JsonProperty("country_code3") String countryCode3,
        @JsonProperty("country_name") String countryName,
        @JsonProperty("country_name_official") String countryNameOfficial,
        @JsonProperty("country_capital") String countryCapital,
        @JsonProperty("state_prov") String stateProv,
        @JsonProperty("state_code") String stateCode,
        String district,
        String city,
        String zipcode,
        String latitude,
        String longitude,
        @JsonProperty("is_eu") Boolean isEu,
        @JsonProperty("country_flag") String countryFlag,
        @JsonProperty("geoname_id") String geonameId,
        @JsonProperty("country_emoji") String countryEmoji
) {}
