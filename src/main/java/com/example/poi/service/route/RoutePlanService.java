package com.example.poi.service.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class RoutePlanService {

    private static final int POI_BUFFER_METERS = 150;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ValhallaRouteService valhalla;

    public RoutePlanService(JdbcTemplate jdbc, ObjectMapper mapper,
                            ValhallaRouteService valhalla) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.valhalla = valhalla;
    }

    @Cacheable(value = "routePlan",
            key = "#from.lon() + ',' + #from.lat() + ':' + "
                    + "#to.lon() + ',' + #to.lat() + ':' + #modes + ':' + #alternatives")
    public Map<String, Object> plan(RoutePoint from, RoutePoint to,
                                    List<String> modes, int alternatives) {
        List<String> safeModes = normalizeModes(modes);
        int k = Math.min(Math.max(alternatives, 1), 5);
        List<Map<String, Object>> routes = new ArrayList<>();
        if (safeModes.contains("car")) {
            routes.addAll(externalOrLocal("car", from, to, k));
        }
        if (safeModes.contains("walk")) {
            routes.addAll(externalOrLocal("walk", from, to, Math.min(k, 3)));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", pointMap(from));
        out.put("to", pointMap(to));
        out.put("routes", routes);
        out.put("note", "时间为估算值，未考虑实时路况、红绿灯和转向限制；"
                + "步行路网不连通时会回退到全市路网。");
        return out;
    }

    private List<Map<String, Object>> externalOrLocal(String mode, RoutePoint from,
                                                      RoutePoint to, int k) {
        List<Map<String, Object>> external = valhalla.planMode(mode, from, to, k);
        return external.isEmpty() ? planMode(mode, from, to, k) : external;
    }

    private List<String> normalizeModes(List<String> modes) {
        if (modes == null || modes.isEmpty()) {
            return List.of("car", "walk");
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String mode : modes) {
            if (mode == null) continue;
            String value = mode.trim().toLowerCase(Locale.ROOT);
            if ("both".equals(value) || "all".equals(value)) {
                set.add("car");
                set.add("walk");
            } else if ("car".equals(value) || "drive".equals(value)) {
                set.add("car");
            } else if ("walk".equals(value) || "foot".equals(value)) {
                set.add("walk");
            }
        }
        return set.isEmpty() ? List.of("car", "walk") : new ArrayList<>(set);
    }

    private List<Map<String, Object>> planMode(String mode, RoutePoint from,
                                               RoutePoint to, int k) {
        if ("car".equals(mode)) {
            return planModeWithTable(mode, "road_car", true, from, to, k);
        }
        for (String table : List.of("road_walk_v2", "road_walk", "road_walk_all")) {
            List<Map<String, Object>> routes = planModeWithTable(
                    mode, table, false, from, to, k);
            if (!routes.isEmpty()) {
                return routes;
            }
        }
        return List.of();
    }

    private List<Map<String, Object>> planModeWithTable(String mode, String table,
                                                        boolean directed,
                                                        RoutePoint from,
                                                        RoutePoint to, int k) {
        Long start = nearestNode(table, from);
        Long end = nearestNode(table, to);
        if (start == null || end == null || start.equals(end)) {
            return List.of();
        }

        String edgesSql = "SELECT id, source, target, cost, reverse_cost FROM " + table;
        int kspK = Math.min(k + 2, 6);
        String sql = """
                WITH ksp AS (
                    SELECT * FROM pgr_ksp(?, ?, ?, ?, directed := %s)
                )
                SELECT k.path_id,
                       k.path_seq,
                       k.edge,
                       e.highway,
                       e.length_m,
                       e.cost,
                       ST_AsGeoJSON(e.geom) AS geojson
                FROM ksp k
                JOIN %s e ON e.id = k.edge
                WHERE k.edge <> -1
                ORDER BY k.path_id, k.path_seq
                """.formatted(directed, table);

        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(sql, edgesSql, start, end, kspK);
        } catch (Exception e) {
            return List.of();
        }
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<Integer, List<Map<String, Object>>> byPath = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            int pathId = ((Number) row.get("path_id")).intValue();
            byPath.computeIfAbsent(pathId, key -> new ArrayList<>()).add(row);
        }

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map.Entry<Integer, List<Map<String, Object>>> entry : byPath.entrySet()) {
            Map<String, Object> route = buildRoute(mode, entry.getValue());
            if (route != null) {
                candidates.add(route);
            }
        }

        List<Map<String, Object>> selected = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            if (selected.size() >= k) break;
            boolean diverse = true;
            for (Map<String, Object> chosen : selected) {
                if (jaccard(candidate.get("_edges"), chosen.get("_edges")) > 0.8) {
                    diverse = false;
                    break;
                }
            }
            if (diverse) selected.add(candidate);
        }
        if (selected.size() < k) {
            for (Map<String, Object> candidate : candidates) {
                if (selected.size() >= k) break;
                if (!selected.contains(candidate)) selected.add(candidate);
            }
        }

        selected.sort(Comparator.comparingDouble(
                route -> ((Number) route.get("durationSeconds")).doubleValue()));
        for (int i = 0; i < selected.size(); i++) {
            Map<String, Object> route = selected.get(i);
            route.put("routeId", mode + "-" + (i + 1));
            route.put("label", modeLabel(mode, i));
            if ("road_walk_all".equals(table)) {
                route.put("fallback", true);
                route.put("fallbackNote", "可步行路网不连通，已回退到全市路网");
            }
            route.remove("_edges");
        }
        return selected;
    }

    private Map<String, Object> buildRoute(String mode, List<Map<String, Object>> rows) {
        List<List<Double>> coords = new ArrayList<>();
        Set<Long> edgeSet = new LinkedHashSet<>();
        Map<String, Double> lengthByType = new LinkedHashMap<>();
        double totalMeters = 0;
        double totalSeconds = 0;

        for (Map<String, Object> row : rows) {
            Object edge = row.get("edge");
            if (edge instanceof Number number) edgeSet.add(number.longValue());
            double length = number(row.get("length_m"));
            totalMeters += length;
            totalSeconds += number(row.get("cost"));
            String highway = row.get("highway") == null
                    ? "unknown" : String.valueOf(row.get("highway"));
            lengthByType.merge(highway, length, Double::sum);
            appendEdgeCoords(coords, String.valueOf(row.get("geojson")));
        }
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

        Map<String, Object> route = new LinkedHashMap<>();
        route.put("mode", mode);
        route.put("distanceMeters", Math.round(totalMeters));
        route.put("durationSeconds", Math.round(totalSeconds));
        route.put("geometry", Map.of("type", "LineString", "coordinates", coords));
        route.put("highlights", Map.of(
                "roadTypes", roadTypes(lengthByType, totalMeters),
                "pois", pois,
                "districts", districts,
                "summary", summary(mode, totalMeters, totalSeconds,
                        lengthByType, pois, districts)
        ));
        route.put("_edges", edgeSet);
        return route;
    }

    private void appendEdgeCoords(List<List<Double>> coords, String geojson) {
        try {
            JsonNode node = mapper.readTree(geojson);
            JsonNode coordinates = node.path("coordinates");
            if (!coordinates.isArray()) return;
            for (JsonNode point : coordinates) {
                if (!point.isArray() || point.size() < 2) continue;
                double lon = point.get(0).asDouble();
                double lat = point.get(1).asDouble();
                if (!coords.isEmpty()) {
                    List<Double> last = coords.get(coords.size() - 1);
                    if (Math.abs(last.get(0) - lon) < 1e-7
                            && Math.abs(last.get(1) - lat) < 1e-7) {
                        continue;
                    }
                }
                coords.add(List.of(lon, lat));
            }
        } catch (Exception ignored) {
            // 单条边解析失败不影响整条路线
        }
    }

    private List<Map<String, Object>> roadTypes(Map<String, Double> lengthByType,
                                                double totalMeters) {
        List<Map<String, Object>> list = new ArrayList<>();
        lengthByType.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(4)
                .forEach(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", entry.getKey());
                    item.put("label", roadLabel(entry.getKey()));
                    item.put("ratio", totalMeters <= 0 ? 0
                            : Math.round(entry.getValue() / totalMeters * 1000) / 1000.0);
                    list.add(item);
                });
        return list;
    }

    private String summary(String mode, double meters, double seconds,
                           Map<String, Double> lengthByType,
                           List<Map<String, Object>> pois,
                           List<String> districts) {
        String topRoad = lengthByType.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown");
        String topPoi = pois.isEmpty()
                ? "无明显设施"
                : pois.get(0).get("category") + " " + pois.get(0).get("cnt") + " 个";
        String districtText = districts.isEmpty()
                ? "未穿过行政区" : String.join("、", districts);
        return String.format(
                "全程 %.1f 公里，预计%s %d 分钟；以%s为主，沿途%s，穿过%s。",
                meters / 1000.0,
                "car".equals(mode) ? "驾车" : "步行",
                Math.max(1, Math.round(seconds / 60.0)),
                roadLabel(topRoad),
                topPoi,
                districtText);
    }

    private String roadLabel(String highway) {
        return switch (highway == null ? "" : highway) {
            case "trunk", "trunk_link" -> "快速路";
            case "primary", "primary_link" -> "主干道";
            case "secondary", "secondary_link" -> "次干道";
            case "tertiary", "tertiary_link" -> "支路";
            case "residential" -> "居住区道路";
            case "service" -> "服务道路";
            case "living_street" -> "生活街道";
            case "footway" -> "步道";
            case "pedestrian" -> "步行街";
            case "steps" -> "台阶";
            case "cycleway" -> "自行车道";
            case "path" -> "小径";
            case "track" -> "土路";
            default -> "其他道路";
        };
    }

    private String modeLabel(String mode, int index) {
        if ("walk".equals(mode)) {
            return index == 0 ? "步行 · 推荐" : "步行 · 备选 " + (index + 1);
        }
        return index == 0 ? "驾车 · 最快" : "驾车 · 备选 " + (index + 1);
    }

    private Long nearestNode(String table, RoutePoint point) {
        String sql = """
                WITH p AS (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326) AS geom),
                edge AS (
                    SELECT source, target,
                           ST_StartPoint(r.geom) AS start_geom,
                           ST_EndPoint(r.geom) AS end_geom
                    FROM %s r, p
                    ORDER BY r.geom <-> p.geom
                    LIMIT 1
                )
                SELECT CASE
                    WHEN ST_Distance(start_geom::geography, p.geom::geography)
                         <= ST_Distance(end_geom::geography, p.geom::geography)
                    THEN source ELSE target
                END AS node
                FROM edge, p
                """.formatted(table);
        try {
            return jdbc.queryForObject(sql, Long.class, point.lon(), point.lat());
        } catch (Exception e) {
            return null;
        }
    }

    private double jaccard(Object a, Object b) {
        if (!(a instanceof Set<?> left) || !(b instanceof Set<?> right)) return 0;
        if (left.isEmpty() && right.isEmpty()) return 1;
        int intersection = 0;
        for (Object item : left) {
            if (right.contains(item)) intersection++;
        }
        int union = left.size() + right.size() - intersection;
        return union == 0 ? 0 : (double) intersection / union;
    }

    private String toWkt(List<List<Double>> coords) {
        StringBuilder sb = new StringBuilder("LINESTRING(");
        for (int i = 0; i < coords.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(coords.get(i).get(0)).append(' ').append(coords.get(i).get(1));
        }
        return sb.append(')').toString();
    }

    private Map<String, Object> pointMap(RoutePoint point) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("lon", point.lon());
        map.put("lat", point.lat());
        map.put("name", point.name());
        return map;
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }
}
