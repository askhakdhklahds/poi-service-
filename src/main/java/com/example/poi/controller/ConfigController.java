package com.example.poi.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ConfigController {

    @Value("${tiles.public-base-url:}")
    private String publicBaseUrl;

    @Value("${tiles.public-gcj-base-url:}")
    private String publicGcjBaseUrl;

    @GetMapping("/api/config")
    public Map<String, Object> config() {
        String base = publicBaseUrl == null ? "" : publicBaseUrl;
        String gcj = publicGcjBaseUrl == null ? "" : publicGcjBaseUrl;
        return Map.of("code", 0, "message", "ok", "data", Map.of(
                "tileBaseUrl", base,
                "gcjTileBaseUrl", gcj));
    }
}
