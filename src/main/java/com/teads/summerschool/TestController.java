package com.teads.summerschool;

import com.teads.summerschool.geolocation.GeolocationService;
import com.teads.summerschool.geolocation.dto.GeolocationResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

public class TestController {
    private final GeolocationService ipGeoService;

    public TestController(GeolocationService ipGeoService){
        this.ipGeoService = ipGeoService;

    }

    @GetMapping("/test/geo")
    public GeolocationResponse testGeoResolution(@RequestParam("ip") final Integer ip){
        return ipGeoService.getGeolocation(ip.toString()).block();
    }
}
