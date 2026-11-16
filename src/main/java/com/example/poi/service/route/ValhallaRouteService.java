package com.example.poi.service.route;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可选的高精度路由：调用 Valhalla 公共/自建服务，使用 OSM 真实路网。
 * 只发送起终点坐标；失败/超时返回空列表，由 RoutePlanService 回退本地 pgRouting。
 */
@Service
public class ValhallaRouteService {

    private static final int POI_BUFFER_METERS = 150;

    private final RestClient client;
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public ValhallaRouteService(
            @Value("${route.valhalla.enabled:true}") boolean enabled,
            @Value("${route.valhalla.base-url:https://valhalla1.openstreetmap.de}") String baseUrl,
            @Value("${route.valhalla.timeout-seconds:8}") int timeoutSeconds,
            JdbcTemplate jdbc) {
        this.enabled = enabled;
        this.jdbc = jdbc;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(timeoutSeconds * 1000);
        this.client = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .build();
    }

    public List<Map<String, Object>> planMode(String mode, RoutePoint from,
                                              RoutePoint to, int alternatives) {
        if (!enabled) {
            return List.of();
        }
        String costing = "car".equals(mode) ? "auto" : "pedestrian";
        int alternateCount = Math.max(0, Math.min(alternatives - 1, 4));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locations", List.of(
                Map.of("lat", from.lat(), "lon", from.lon()),
                Map.of("lat", to.lat(), "lon", to.lon())));
        body.put("costing", costing);
        body.put("alternates", alternateCount);
        body.put("directions_options", Map.of("units", "kilometers"));

        JsonNode response;
        try {
            response = client.post().uri("/route")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            return List.of();
        }
        if (response == null || !response.has("trip")) {
            return List.of();
        }

        List<JsonNode> trips = new ArrayList<>();
        trips.add(response.get("trip"));
        JsonNode alternates = response.path("alternates");
        if (alternates.isArray()) {
            for (JsonNode alternate : alternates) {
                JsonNode trip = alternate.path("trip");
                if (trip.isObject()) {
                    trips.add(trip);
                }
            }
        }

        List<Map<String, Object>> routes = new ArrayList<>();
        for (int i = 0; i < trips.size(); i++) {
            Map<String, Object> route = parseTrip(mode, trips.get(i), i);
            if (route != null) {
                routes.add(route);
            }
        }
        routes.sort(Comparator.comparingDouble(
                route -> ((Number) route.get("durationSeconds")).doubleValue()));
        for (int i = 0; i < routes.size(); i++) {
            Map<String, Object> route = routes.get(i);
            route.put("routeId", "valhalla-" + mode + "-" + (i + 1));
            route.put("label", label(mode, i));
        }
        return routes;
    }

    private Map<String, Object> parseTrip(String mode, JsonNode trip, int index) {
        JsonNode summary = trip.path("summary");
        double lengthKm = summary.path("length").asDouble(0);
        double seconds = summary.path("time").asDouble(0);
        JsonNode legs = trip.path("legs");
        if (!legs.isArray() || legs.isEmpty()) {
            return null;
        }
        JsonNode leg = legs.get(0);
        List<List<Double>> coords = decodePolyline6(leg.path("shape").asText(""));
        if (coords.size() < 2) {
            return null;
        }

        String wkt = toWkt(coords);
        List<Map<String, Object>> pois = jdbc.queryForList("""
                WITH route AS (
                    SELECT ST_Transform(ST_GeomFromText(?, 4326), 4549) AS geom
                )
                SELECT p.category, count(*) AS cnt
                FROM poi p, route r
                WHERE ST_DWithin(p.geom_4549, r.geom, ?)
                GROUP BY p.category
                ORDER BY cnt DESC
                LIMIT 5
                """, wkt, POI_BUFFER_METERS);
        List<String> districts = jdbc.queryForList("""
                SELECT name FROM district
                WHERE ST_Intersects(geom, ST_GeomFromText(?, 4326))
                ORDER BY name
                """, String.class, wkt);

        String topPoi = pois.isEmpty()
                ? "无明显设施"
                : pois.get(0).get("category") + " " + pois.get(0).get("cnt") + " 个";
        String districtText = districts.isEmpty()
                ? "未穿过行政区" : String.join("、", districts);
        String summaryText = String.format(
                "高精度路线（Valhalla/OSM）：全程 %.1f 公里，预计%s %d 分钟；沿途%s，穿过%s。",
                lengthKm,
                "car".equals(mode) ? "驾车" : "步行",
                Math.max(1, Math.round(seconds / 60.0)),
                topPoi,
                districtText);

        Map<String, Object> highlights = new LinkedHashMap<>();
        highlights.put("roadTypes", List.of());
        highlights.put("pois", pois);
        highlights.put("districts", districts);
        highlights.put("summary", summaryText);

        Map<String, Object> route = new LinkedHashMap<>();
        route.put("routeId", "valhalla-" + mode + "-" + (index + 1));
        route.put("mode", mode);
        route.put("label", label(mode, index));
        route.put("distanceMeters", Math.round(lengthKm * 1000));
        route.put("durationSeconds", Math.round(seconds));
        route.put("geometry", Map.of(
                "type", "LineString",
                "coordinates", coords));
        route.put("highlights", highlights);
        route.put("source", "valhalla");
        route.put("sourceLabel", "高精度路网");
        return route;
    }

    private String label(String mode, int index) {
        if ("walk".equals(mode)) {
            return index == 0 ? "步行 · 推荐" : "步行 · 备选 " + (index + 1);
        }
        return index == 0 ? "驾车 · 最快" : "驾车 · 备选 " + (index + 1);
    }

    private String toWkt(List<List<Double>> coords) {
        StringBuilder sb = new StringBuilder("LINESTRING(");
        for (int i = 0; i < coords.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(coords.get(i).get(0)).append(' ').append(coords.get(i).get(1));
        }
        return sb.append(')').toString();
    }

    /** Valhalla 默认 shape 是 polyline6（精度 1e6）。 */
    static List<List<Double>> decodePolyline6(String encoded) {
        List<List<Double>> coords = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) {
            return coords;
        }
        int index = 0;
        int lat = 0;
        int lng = 0;
        while (index < encoded.length()) {
            int b;
            int shift = 0;
            int result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlat = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lat += dlat;

            shift = 0;
            result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlng = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lng += dlng;

            coords.add(List.of(lng / 1_000_000.0, lat / 1_000_000.0));
        }
        return coords;
    }
}
