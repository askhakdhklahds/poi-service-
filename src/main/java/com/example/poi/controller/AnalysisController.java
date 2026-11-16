package com.example.poi.controller;

import com.example.poi.service.PoiService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final PoiService service;

    public AnalysisController(PoiService service) {
        this.service = service;
    }

    @GetMapping("/buffer")
    public Map<String, Object> buffer(@RequestParam double lon,
                                      @RequestParam double lat,
                                      @RequestParam(defaultValue = "1000") int radius) {
        if (lon < -180 || lon > 180 || lat < -90 || lat > 90) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "lon/lat out of range");
        }
        int safeRadius = Math.min(Math.max(radius, 1), 50000);
        List<Map<String, Object>> data = service.bufferStats(lon, lat, safeRadius);
        return Map.of("code", 0, "message", "ok", "data", data);
    }
}
