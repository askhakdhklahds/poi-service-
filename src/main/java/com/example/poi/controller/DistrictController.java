package com.example.poi.controller;

import com.example.poi.service.DistrictService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/district")
public class DistrictController {

    private final DistrictService service;

    public DistrictController(DistrictService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public Map<String, Object> list() {
        List<Map<String, Object>> data = service.list();
        return Map.of("code", 0, "message", "ok", "data", data);
    }

    @GetMapping("/{adcode}/stats")
    public Map<String, Object> stats(@PathVariable String adcode) {
        Map<String, Object> data = service.stats(adcode);
        return Map.of("code", 0, "message", "ok", "data", data);
    }
}
