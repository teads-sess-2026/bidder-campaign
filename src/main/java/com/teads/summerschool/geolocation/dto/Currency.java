package com.teads.summerschool.geolocation.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Currency(
        String code,
        String name,
        String symbol
) {}
