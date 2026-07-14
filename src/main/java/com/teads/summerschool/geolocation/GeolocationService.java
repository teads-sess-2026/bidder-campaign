package com.teads.summerschool.geolocation;

import com.teads.summerschool.geolocation.dto.GeolocationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Service
public class GeolocationService {

    private static final Logger log = LoggerFactory.getLogger(GeolocationService.class);
    private static final String BASE_URL = "https://api.ipgeolocation.io";

    private final WebClient webClient;
    private final String apiKey;

    public GeolocationService(WebClient.Builder webClientBuilder,
                              @Value("${geolocation.api.key}") String apiKey) {
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
        this.apiKey = apiKey;
    }

    public Mono<GeolocationResponse> getGeolocation(String ipAddress) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v3/ipgeo")
                        .queryParam("apiKey", apiKey)
                        .queryParam("ip", ipAddress)
                        .build())
                .retrieve()
                .bodyToMono(GeolocationResponse.class)
                .timeout(Duration.ofSeconds(2))
                .doOnError(error -> log.error("Failed to fetch geolocation for IP: {}", ipAddress, error))
                .onErrorResume(error -> {
                    log.warn("Geolocation lookup failed for IP {}, returning empty", ipAddress);
                    return Mono.empty();
                });
    }

    public Mono<String> getCountryCode(String ipAddress) {
        return getGeolocation(ipAddress)
                .mapNotNull(response -> response.location() != null ? response.location().countryCode2() : null);
    }

    public Mono<String> getCity(String ipAddress) {
        return getGeolocation(ipAddress)
                .mapNotNull(response -> response.location() != null ? response.location().city() : null);
    }
}
