package com.teads.summerschool.geolocation.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TimeZone(
        String name,
        Integer offset,
        @JsonProperty("offset_with_dst") Integer offsetWithDst,
        @JsonProperty("current_time") String currentTime,
        @JsonProperty("current_time_unix") Double currentTimeUnix,
        @JsonProperty("is_dst") Boolean isDst
) {}
