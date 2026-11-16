package com.example.poi.controller;

import com.example.poi.service.PoiService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/poi")
public class PoiController {

    private final PoiService service;

    public PoiController(PoiService service) {
        this.service = service;
    }

    @GetMapping("/within")
    public Map<String, Object> within(@RequestParam double lon,
                                      @RequestParam double lat,
                                      @RequestParam(defaultValue = "1000") int radius,
                                      @RequestParam(required = false) String category,
                                      @RequestParam(defaultValue = "200") int limit) {
        validate(lon, lat);
        int safeRadius = Math.min(Math.max(radius, 1), 50000);
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        List<Map<String, Object>> data =
                service.within(lon, lat, safeRadius, category, safeLimit);
        return Map.of("code", 0, "message", "ok", "data", data);
    }

    @GetMapping("/nearest")
    public Map<String, Object> nearest(@RequestParam double lon,
                                       @RequestParam double lat,
                                       @RequestParam(defaultValue = "10") int limit) {
        validate(lon, lat);
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        List<Map<String, Object>> data = service.nearest(lon, lat, safeLimit);
        return Map.of("code", 0, "message", "ok", "data", data);
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam(defaultValue = "") String keyword,
                                      @RequestParam(defaultValue = "8") int limit) {
        String text = keyword.trim();
        int safeLimit = Math.min(Math.max(limit, 1), 20);
        List<Map<String, Object>> data = text.isEmpty()
                ? List.of() : service.searchPlaces(text, safeLimit);
        return Map.of("code", 0, "message", "ok", "data", data);
    }

    @GetMapping("/count")
    public Map<String, Object> count() {
        return Map.of("code", 0, "message", "ok",
                "data", Map.of("count", service.count()));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("code", 0, "message", "ok", "data", service.stats());
    }

    @GetMapping("/{id:\\d+}")
    public Map<String, Object> detail(@PathVariable long id) {
        Map<String, Object> data = service.detail(id);
        if (data.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "poi not found");
        }
        return Map.of("code", 0, "message", "ok", "data", data);
    }

    @GetMapping(value = "/geojson", produces = "application/geo+json")
    public org.springframework.http.ResponseEntity<String> geojson(
            @RequestParam(defaultValue = "20000") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 50000);
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType("application/geo+json"))
                .body(service.geoJsonSample(safeLimit));
    }

    private void validate(double lon, double lat) {
        if (lon < -180 || lon > 180 || lat < -90 || lat > 90) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "lon/lat out of range");
        }
    }
}
