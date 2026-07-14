package com.teads.summerschool.geolocation;

import com.teads.summerschool.geolocation.dto.GeolocationResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/geolocation")
public class GeolocationController {

    private final GeolocationService geolocationService;

    public GeolocationController(GeolocationService geolocationService) {
        this.geolocationService = geolocationService;
    }

    @GetMapping
    public Mono<GeolocationResponse> getGeolocation(@RequestParam String ip) {
        return geolocationService.getGeolocation(ip);
    }

    @GetMapping("/country")
    public Mono<String> getCountryCode(@RequestParam String ip) {
        return geolocationService.getCountryCode(ip);
    }

    @GetMapping("/city")
    public Mono<String> getCity(@RequestParam String ip) {
        return geolocationService.getCity(ip);
    }
}
