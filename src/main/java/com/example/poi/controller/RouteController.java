package com.example.poi.controller;

import com.example.poi.service.route.RoutePlanRequest;
import com.example.poi.service.route.RoutePlanService;
import com.example.poi.service.route.RoutePoint;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/route")
public class RouteController {

    private final RoutePlanService service;

    public RouteController(RoutePlanService service) {
        this.service = service;
    }

    @PostMapping("/plan")
    public Map<String, Object> plan(@RequestBody RoutePlanRequest request) {
        if (request == null || request.from() == null || request.to() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from/to required");
        }
        validate(request.from());
        validate(request.to());
        int alternatives = request.alternatives() == null
                ? 3
                : Math.min(Math.max(request.alternatives(), 1), 5);
        return Map.of("code", 0, "message", "ok", "data",
                service.plan(request.from(), request.to(),
                        request.modes(), alternatives));
    }

    private void validate(RoutePoint point) {
        if (point.lon() < -180 || point.lon() > 180
                || point.lat() < -90 || point.lat() > 90) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "lon/lat out of range");
        }
    }
}
