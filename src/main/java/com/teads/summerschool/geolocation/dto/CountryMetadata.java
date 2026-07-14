package com.teads.summerschool.geolocation.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CountryMetadata(
        @JsonProperty("calling_code") String callingCode,
        String tld,
        List<String> languages
) {}
