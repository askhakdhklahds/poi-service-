package com.example.poi.controller;

import com.example.poi.service.life_circle.CoverageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/life-circle")
public class LifeCircleController {

    private final CoverageService coverageService;

    public LifeCircleController(CoverageService coverageService) {
        this.coverageService = coverageService;
    }

    @GetMapping("/categories")
    public Map<String, Object> categories() {
        return Map.of("code", 0, "message", "ok",
                "data", coverageService.categories());
    }

    @GetMapping("/coverage")
    public Map<String, Object> coverage(
            @RequestParam String adcode,
            @RequestParam String category,
            @RequestParam(defaultValue = "900") int threshold) {
        if (adcode == null || adcode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "adcode required");
        }
        if (category == null || category.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "category required");
        }
        int safeThreshold = Math.min(Math.max(threshold, 60), 3600);
        try {
            return Map.of("code", 0, "message", "ok",
                    "data", coverageService.coverage(adcode, category, safeThreshold));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
