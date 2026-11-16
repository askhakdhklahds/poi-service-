package com.example.poi.agent.tool;

import com.example.poi.agent.GisTools;
import com.example.poi.agent.RoadRoutingService;
import com.example.poi.agent.orchestrator.AgentType;
import com.example.poi.service.route.RoutePlanService;
import com.example.poi.service.route.RoutePoint;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

@Component
public class ToolRegistry {

    private final GisTools gisTools;
    private final RoadRoutingService roadRouting;
    private final RoutePlanService routePlanService;

    private final Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, Function<Map<String, Object>, Object>> handlers =
            new LinkedHashMap<>();

    private final ExecutorService executor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "poi-tool-");
        thread.setDaemon(true);
        return thread;
    });

    public ToolRegistry(GisTools gisTools, RoadRoutingService roadRouting,
                        RoutePlanService routePlanService) {
        this.gisTools = gisTools;
        this.roadRouting = roadRouting;
        this.routePlanService = routePlanService;
        registerTools();
    }

    private void registerTools() {
        register("search_place", "按名称搜索地点，返回名称、分类、经纬度",
                Set.of("keyword"), allAgents(), 5000,
                args -> gisTools.searchPlace(str(args, "keyword")));

        register("pois_within", "查询某个坐标附近一定半径内的 POI",
                Set.of("lon", "lat", "radius"), Set.of(AgentType.SPATIAL_QUERY), 5000,
                args -> gisTools.poisWithin(
                        dbl(args, "lon", 0), dbl(args, "lat", 0),
                        integer(args, "radius", 1000), str(args, "category"),
                        integer(args, "limit", 200)));

        register("nearest_pois", "查询离某个坐标最近的 POI",
                Set.of("lon", "lat"),
                Set.of(AgentType.SPATIAL_QUERY, AgentType.ROUTE_PLANNING), 5000,
                args -> gisTools.nearestPois(
                        dbl(args, "lon", 0), dbl(args, "lat", 0),
                        integer(args, "limit", 10), str(args, "category")));

        register("buffer_stats", "统计某个坐标附近半径内各分类 POI 数量",
                Set.of("lon", "lat", "radius"),
                Set.of(AgentType.SPATIAL_QUERY, AgentType.REPORT), 5000,
                args -> gisTools.bufferStats(
                        dbl(args, "lon", 0), dbl(args, "lat", 0),
                        integer(args, "radius", 1000)));

        register("district_stats", "查询行政区 POI 总数",
                Set.of("nameOrCode"), Set.of(AgentType.SPATIAL_QUERY), 5000,
                args -> gisTools.districtStats(str(args, "nameOrCode")));

        register("district_ranking", "行政区 POI 数量排名",
                Set.of(), Set.of(AgentType.SPATIAL_QUERY, AgentType.REPORT), 5000,
                args -> gisTools.districtRanking(integer(args, "limit", 10)));

        register("list_districts", "列出所有行政区",
                Set.of(), Set.of(AgentType.GENERAL, AgentType.SPATIAL_QUERY, AgentType.REPORT),
                3000, args -> gisTools.listDistricts());

        register("district_center", "查询行政区中心点",
                Set.of("nameOrCode"),
                Set.of(AgentType.SPATIAL_QUERY, AgentType.ROUTE_PLANNING), 3000,
                args -> gisTools.districtCenter(str(args, "nameOrCode")));

        register("default_center", "获取当前数据的中心点",
                Set.of(),
                Set.of(AgentType.SPATIAL_QUERY, AgentType.ROUTE_PLANNING, AgentType.REPORT),
                3000, args -> gisTools.defaultCenter());

        register("route_plan", "基于真实路网规划一条访问多个 POI 的路线",
                Set.of("lon", "lat"), Set.of(AgentType.ROUTE_PLANNING), 15000,
                this::planRoute);

        register("route_compare", "规划起点到终点的多条驾车/步行路线",
                Set.of("fromLon", "fromLat", "toLon", "toLat"),
                Set.of(AgentType.ROUTE_PLANNING), 30000,
                this::routeCompare);
    }

    private void register(String name, String description, Set<String> requiredArgs,
                          Set<AgentType> allowedAgents, long timeoutMs,
                          Function<Map<String, Object>, Object> handler) {
        definitions.put(name, new ToolDefinition(
                name, description, requiredArgs, allowedAgents, timeoutMs));
        handlers.put(name, handler);
    }

    public ToolResult execute(String name, AgentType agentType, Map<String, Object> args) {
        ToolDefinition definition = definitions.get(name);
        if (definition == null) {
            return ToolResult.fail("unknown tool: " + name, 0);
        }
        if (agentType != null && !definition.allowedAgents().contains(agentType)) {
            return ToolResult.fail("tool not allowed for agent: " + name, 0);
        }
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        for (String required : definition.requiredArgs()) {
            if (safeArgs.get(required) == null) {
                return ToolResult.fail("missing arg: " + required, 0);
            }
        }
        long start = System.nanoTime();
        try {
            Object data = CompletableFuture
                    .supplyAsync(() -> handlers.get(name).apply(safeArgs), executor)
                    .get(definition.timeoutMs(), TimeUnit.MILLISECONDS);
            return ToolResult.ok(data, elapsedMs(start));
        } catch (TimeoutException e) {
            return ToolResult.fail("tool timeout: " + name, elapsedMs(start));
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return ToolResult.fail(cause.getMessage(), elapsedMs(start));
        }
    }

    public ToolDefinition definition(String name) {
        return definitions.get(name);
    }

    public Map<String, ToolDefinition> definitions() {
        return Map.copyOf(definitions);
    }

    private Object planRoute(Map<String, Object> args) {
        double lon = dbl(args, "lon", 121.4737);
        double lat = dbl(args, "lat", 31.2304);
        String category = str(args, "category");
        int limit = Math.min(Math.max(integer(args, "limit", 5), 3), 10);

        List<Map<String, Object>> candidates =
                gisTools.nearestPois(lon, lat, limit, category);
        List<Map<String, Object>> ordered = greedy(lon, lat, candidates, limit);
        Map<String, Object> road = roadRouting.route(lon, lat, ordered);

        boolean available = Boolean.TRUE.equals(road.get("available"));
        List<List<Double>> route = new ArrayList<>();
        double total = 0;
        if (available) {
            if (road.get("routePoints") instanceof List<?> points) {
                for (Object value : points) {
                    if (value instanceof List<?> point && point.size() >= 2) {
                        route.add(List.of(
                                ((Number) point.get(0)).doubleValue(),
                                ((Number) point.get(1)).doubleValue()));
                    }
                }
            }
            if (road.get("distanceMeters") instanceof Number number) {
                total = number.doubleValue();
            }
        } else {
            route.add(List.of(lon, lat));
            for (Map<String, Object> row : ordered) {
                route.add(List.of(value(row.get("lon")), value(row.get("lat"))));
                total += value(row.get("leg_distance_m"));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", available);
        out.put("stops", ordered);
        out.put("route", route);
        out.put("distanceMeters", total);
        out.put("routeGeoJson", road.get("routeGeoJson"));
        return out;
    }

    private Object routeCompare(Map<String, Object> args) {
        RoutePoint from = new RoutePoint(
                dbl(args, "fromLon", 0),
                dbl(args, "fromLat", 0),
                str(args, "fromName"));
        RoutePoint to = new RoutePoint(
                dbl(args, "toLon", 0),
                dbl(args, "toLat", 0),
                str(args, "toName"));
        return routePlanService.plan(from, to, modes(args.get("modes")),
                integer(args, "alternatives", 3));
    }

    private List<String> modes(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return java.util.Arrays.stream(text.split(","))
                    .map(String::trim)
                    .filter(item -> !item.isEmpty())
                    .toList();
        }
        return List.of("car", "walk");
    }

    private List<Map<String, Object>> greedy(double lon, double lat,
                                             List<Map<String, Object>> candidates,
                                             int limit) {
        List<Map<String, Object>> remaining = new ArrayList<>(candidates);
        List<Map<String, Object>> ordered = new ArrayList<>();
        double currentLon = lon;
        double currentLat = lat;
        while (!remaining.isEmpty() && ordered.size() < limit) {
            Map<String, Object> best = null;
            double bestDistance = Double.MAX_VALUE;
            for (Map<String, Object> candidate : remaining) {
                double distance = haversine(currentLon, currentLat,
                        value(candidate.get("lon")), value(candidate.get("lat")));
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
            if (best == null) {
                break;
            }
            remaining.remove(best);
            Map<String, Object> item = new LinkedHashMap<>(best);
            item.put("order", ordered.size() + 1);
            item.put("leg_distance_m", Math.round(bestDistance));
            ordered.add(item);
            currentLon = value(best.get("lon"));
            currentLat = value(best.get("lat"));
        }
        return ordered;
    }

    private double haversine(double lon1, double lat1, double lon2, double lat2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private static Set<AgentType> allAgents() {
        return Set.of(AgentType.GENERAL, AgentType.SPATIAL_QUERY,
                AgentType.ROUTE_PLANNING, AgentType.REPORT);
    }

    private static String str(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static double dbl(Map<String, Object> args, String key, double fallback) {
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int integer(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double value(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
