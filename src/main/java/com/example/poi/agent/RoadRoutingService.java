package com.example.poi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RoadRoutingService {

    private static final Logger log = LoggerFactory.getLogger(RoadRoutingService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RoadRoutingService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public boolean available() {
        try {
            String table = jdbc.queryForObject(
                    "SELECT to_regclass('public.road_main')::text", String.class);
            return table != null;
        } catch (DataAccessException e) {
            return false;
        }
    }

    public Map<String, Object> route(double startLon, double startLat,
                                     List<Map<String, Object>> stops) {
        if (!available()) {
            return Map.of("available", false);
        }
        List<Long> nodes = new ArrayList<>();
        nodes.add(nearestNode(startLon, startLat));
        for (Map<String, Object> stop : stops) {
            nodes.add(nearestNode(number(stop.get("lon")), number(stop.get("lat"))));
        }
        if (nodes.stream().anyMatch(n -> n == null)) {
            return Map.of("available", false);
        }

        List<Map<String, Object>> features = new ArrayList<>();
        List<Map<String, Object>> legs = new ArrayList<>();
        double total = 0;
        for (int i = 1; i < nodes.size(); i++) {
            if (nodes.get(i - 1).equals(nodes.get(i))) {
                continue;
            }
            Map<String, Object> leg = routeLeg(nodes.get(i - 1), nodes.get(i));
            if (leg == null) {
                log.warn("road leg failed: {} -> {}", nodes.get(i - 1), nodes.get(i));
                return Map.of("available", false);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> geometry = (Map<String, Object>) leg.get("geometry");
            Map<String, Object> feature = new LinkedHashMap<>();
            feature.put("type", "Feature");
            feature.put("geometry", geometry);
            feature.put("properties", Map.of("leg", i));
            features.add(feature);

            double cost = number(leg.get("cost"));
            total += cost;
            legs.add(Map.of("from", i - 1, "to", i, "distanceMeters", cost));
        }

        if (features.isEmpty()) {
            return Map.of("available", false);
        }
        log.info("road route ok: {} legs, {} m", features.size(), Math.round(total));

        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("type", "FeatureCollection");
        collection.put("features", features);

        List<List<Double>> points = new ArrayList<>();
        for (Map<String, Object> feature : features) {
            @SuppressWarnings("unchecked")
            Map<String, Object> geometry = (Map<String, Object>) feature.get("geometry");
            collectCoordinates(geometry, points);
        }

        return Map.of(
                "available", true,
                "distanceMeters", total,
                "routeGeoJson", collection,
                "routePoints", points,
                "legs", legs);
    }

    private Long nearestNode(double lon, double lat) {
        List<Long> rows = jdbc.queryForList("""
                SELECT source
                FROM road_main
                ORDER BY geom <-> ST_SetSRID(ST_MakePoint(?, ?), 4326)
                LIMIT 1
                """, Long.class, lon, lat);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> routeLeg(long start, long end) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                WITH route AS (
                    SELECT * FROM pgr_dijkstra(
                        'SELECT id, source, target, cost, reverse_cost FROM road_main',
                        ?, ?, directed := false
                    )
                )
                SELECT ST_AsGeoJSON(ST_LineMerge(ST_Collect(r.geom ORDER BY route.seq))) AS geojson,
                       COALESCE(SUM(route.cost), 0) AS cost
                FROM route
                JOIN road_main r ON r.id = route.edge
                WHERE route.edge <> -1
                """, start, end);
        if (rows.isEmpty() || rows.get(0).get("geojson") == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> geometry = mapper.readValue(
                    String.valueOf(rows.get(0).get("geojson")), Map.class);
            return Map.of("geometry", geometry,
                    "cost", number(rows.get(0).get("cost")));
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void collectCoordinates(Map<String, Object> geometry, List<List<Double>> out) {
        Object type = geometry.get("type");
        Object coordinates = geometry.get("coordinates");
        if (!(coordinates instanceof List<?> list)) {
            return;
        }
        if ("LineString".equals(type)) {
            for (Object point : list) {
                out.add(toPoint((List<?>) point));
            }
        } else if ("MultiLineString".equals(type)) {
            for (Object line : list) {
                for (Object point : (List<?>) line) {
                    out.add(toPoint((List<?>) point));
                }
            }
        }
    }

    private List<Double> toPoint(List<?> point) {
        List<Double> out = new ArrayList<>();
        out.add(((Number) point.get(0)).doubleValue());
        out.add(((Number) point.get(1)).doubleValue());
        return out;
    }

    private double number(Object value) {
        return value == null ? 0 : ((Number) value).doubleValue();
    }
}
