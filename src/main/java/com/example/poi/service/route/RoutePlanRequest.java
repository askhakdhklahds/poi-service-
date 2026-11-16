package com.example.poi.service.route;

import java.util.List;

public record RoutePlanRequest(
        RoutePoint from,
        RoutePoint to,
        List<String> modes,
        Integer alternatives
) {
}
