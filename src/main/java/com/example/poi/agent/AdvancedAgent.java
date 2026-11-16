package com.example.poi.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AdvancedAgent {

    private final GisTools tools;

    private static final Map<String, String> CATEGORY = new LinkedHashMap<>();

    static {
        put("医院", "医院", "诊所", "药房", "药店", "看病", "医疗");
        put("餐饮", "餐厅", "餐饮", "吃饭", "美食", "饭店", "咖啡", "快餐");
        put("购物", "购物", "商场", "超市", "商店", "买东西");
        put("学校", "学校", "大学", "学院", "中学", "小学", "幼儿园");
        put("交通", "地铁", "公交", "车站", "停车", "加油", "充电", "交通");
        put("景点", "景点", "公园", "旅游", "景区", "博物馆");
        put("商业", "商业", "公司", "写字楼", "办公");
    }

    private static void put(String category, String... words) {
        for (String word : words) {
            CATEGORY.put(word, category);
        }
    }

    private final RoadRoutingService roadRouting;

    public AdvancedAgent(GisTools tools, RoadRoutingService roadRouting) {
        this.tools = tools;
        this.roadRouting = roadRouting;
    }

    public Map<String, Object> tryAsk(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        List<Map<String, Object>> steps = new ArrayList<>();

        Map<String, Object> result = tryCompare(q, steps);
        if (result != null) return result;

        result = tryRoute(q, steps);
        if (result != null) return result;

        result = tryRanking(q, steps);
        if (result != null) return result;

        return null;
    }

    private Map<String, Object> tryCompare(String q, List<Map<String, Object>> steps) {
        if (!(q.contains("比较") || q.contains("对比") || q.contains("相比"))) {
            return null;
        }
        Matcher m = Pattern.compile("比较(.+?)(?:和|与|跟)(.+?)(?:附近|周围|周边|的|$)")
                .matcher(q);
        boolean found = m.find();
        if (!found) {
            m = Pattern.compile("(.+?)(?:和|与|跟)(.+?)(?:附近|周围|周边)").matcher(q);
            found = m.find();
        }
        if (!found) {
            return null;
        }

        Place a = resolve(cleanPlace(m.group(1)));
        Place b = resolve(cleanPlace(m.group(2)));
        int radius = radius(q, 1000);
        String category = category(q);

        long start = System.nanoTime();
        List<Map<String, Object>> rawA = tools.poisWithin(a.lon, a.lat, radius, category, 500);
        addStep(steps, "pois_within", a.name + " / " + radius + "m / " + label(category),
                rawA.size() + " points", start);

        start = System.nanoTime();
        List<Map<String, Object>> rawB = tools.poisWithin(b.lon, b.lat, radius, category, 500);
        addStep(steps, "pois_within", b.name + " / " + radius + "m / " + label(category),
                rawB.size() + " points", start);

        start = System.nanoTime();
        int countA = count(tools.bufferStats(a.lon, a.lat, radius), category);
        int countB = count(tools.bufferStats(b.lon, b.lat, radius), category);
        addStep(steps, "buffer_stats", a.name + " vs " + b.name,
                countA + " vs " + countB, start);

        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> row : rawA) {
            data.add(withGroup(row, a.name));
        }
        for (Map<String, Object> row : rawB) {
            data.add(withGroup(row, b.name));
        }

        String label = label(category);
        String comparison = countA == countB ? "两者持平"
                : countA > countB ? "【" + a.name + "】更多" : "【" + b.name + "】更多";
        String answer = String.format(
                "【%s】%d 米内%s %d 个；【%s】%d 米内%s %d 个；%s（差 %d 个）。",
                a.name, radius, label, countA, b.name, radius, label, countB,
                comparison, Math.abs(countA - countB));

        List<Map<String, Object>> cards = List.of(
                card(a.name + " · " + radius + "m", "stats",
                        List.of(row(label, countA))),
                card(b.name + " · " + radius + "m", "stats",
                        List.of(row(label, countB))));

        Map<String, Object> center = center((a.lon + b.lon) / 2.0, (a.lat + b.lat) / 2.0);
        return result("compare", answer, data, center, radius, steps, cards, null);
    }

    private Map<String, Object> tryRoute(String q, List<Map<String, Object>> steps) {
        if (!(q.contains("路线") || q.contains("规划") || q.contains("一日游")
                || q.contains("顺路") || q.contains("行程"))) {
            return null;
        }
        Place startPlace = resolve(cleanPlace(extractPlace(q)));
        String category = category(q);
        if (category == null) {
            category = "景点";
        }
        int limit = Math.min(Math.max(limit(q, 5), 3), 10);

        long start = System.nanoTime();
        List<Map<String, Object>> candidates =
                tools.nearestPois(startPlace.lon, startPlace.lat, limit, category);
        addStep(steps, "nearest_pois",
                startPlace.name + " / " + category + " / limit=" + limit,
                candidates.size() + " candidates", start);

        List<Map<String, Object>> ordered = greedy(startPlace.lon, startPlace.lat, candidates, limit);

        long roadStart = System.nanoTime();
        Map<String, Object> road = roadRouting.route(
                startPlace.lon, startPlace.lat, ordered);
        boolean roadOk = Boolean.TRUE.equals(road.get("available"));
        List<List<Double>> route = new ArrayList<>();
        double total = 0;
        if (roadOk) {
            total = number(road.get("distanceMeters"));
            Object points = road.get("routePoints");
            if (points instanceof List<?> list) {
                for (Object value : list) {
                    if (value instanceof List<?> point && point.size() >= 2) {
                        route.add(List.of(
                                ((Number) point.get(0)).doubleValue(),
                                ((Number) point.get(1)).doubleValue()));
                    }
                }
            }
            addStep(steps, "pgr_dijkstra",
                    startPlace.name + " / " + ordered.size() + " stops",
                    Math.round(total) + " m",
                    roadStart);
        } else {
            route.add(List.of(startPlace.lon, startPlace.lat));
            for (Map<String, Object> row : ordered) {
                route.add(List.of(number(row.get("lon")), number(row.get("lat"))));
                total += number(row.get("leg_distance_m"));
            }
        }

        StringBuilder answer = new StringBuilder();
        answer.append("从【").append(startPlace.name).append("】出发，按最近顺序安排 ")
                .append(ordered.size()).append(" 个").append(category).append("：");
        for (Map<String, Object> row : ordered) {
            answer.append(row.get("order")).append(". ").append(row.get("name"))
                    .append("（约").append(Math.round(number(row.get("leg_distance_m"))))
                    .append(" 米）；");
        }
        if (!ordered.isEmpty()) {
            answer.setLength(answer.length() - 1);
        }
        answer.append(" 总移动距离约 ").append(Math.round(total)).append(" 米");
        if (roadOk) {
            answer.append("（基于真实路网）");
        }
        answer.append("。");

        List<Map<String, Object>> cardRows = new ArrayList<>();
        for (Map<String, Object> row : ordered) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("order", row.get("order"));
            item.put("name", row.get("name"));
            item.put("distance_m", row.get("leg_distance_m"));
            cardRows.add(item);
        }
        List<Map<String, Object>> cards = List.of(
                card("路线行程", "route", cardRows));

        Map<String, Object> out = result("route", answer.toString(), ordered,
                center(startPlace.lon, startPlace.lat), null, steps, cards, route);
        if (roadOk) {
            out.put("routeGeoJson", road.get("routeGeoJson"));
            out.put("distanceMeters", total);
        }
        return out;
    }

    private Map<String, Object> tryRanking(String q, List<Map<String, Object>> steps) {
        boolean ranking = q.contains("排名") || q.contains("排行") || q.contains("哪个区")
                || q.contains("最多") || q.contains("哪类");
        if (!ranking) {
            return null;
        }
        boolean nearby = q.contains("附近") || q.contains("周围") || q.contains("周边");

        if (nearby) {
            Place place = resolve(cleanPlace(extractPlace(q)));
            int radius = radius(q, 1000);
            long start = System.nanoTime();
            List<Map<String, Object>> stats = tools.bufferStats(place.lon, place.lat, radius);
            addStep(steps, "buffer_stats", place.name + " / " + radius + "m",
                    stats.size() + " categories", start);

            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < Math.min(stats.size(), 6); i++) {
                Map<String, Object> item = stats.get(i);
                rows.add(row(String.valueOf(item.get("category")), number(item.get("cnt"))));
            }
            String top = rows.isEmpty() ? "无数据"
                    : rows.get(0).get("label") + "（" + Math.round(number(rows.get(0).get("value"))) + " 个）";
            String answer = "【" + place.name + "】附近 " + radius
                    + " 米内，最多的是 " + top + "。";
            return result("ranking", answer, List.of(), center(place.lon, place.lat),
                    radius, steps, List.of(card("周边分类排名", "stats", rows)), null);
        }

        long start = System.nanoTime();
        List<Map<String, Object>> rankingRows = tools.districtRanking(10);
        addStep(steps, "district_ranking", "top 10", rankingRows.size() + " districts", start);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> item : rankingRows) {
            rows.add(row(String.valueOf(item.get("name")), number(item.get("poi_cnt"))));
        }
        StringBuilder answer = new StringBuilder("行政区 POI 数量排名：");
        for (int i = 0; i < Math.min(rows.size(), 3); i++) {
            answer.append(i + 1).append(". ").append(rows.get(i).get("label"))
                    .append("（").append(Math.round(number(rows.get(i).get("value"))))
                    .append(" 个）");
            if (i < Math.min(rows.size(), 3) - 1) answer.append("；");
        }
        answer.append("。");
        Map<String, Object> dc = tools.defaultCenter();
        Map<String, Object> center = dc.isEmpty()
                ? center(121.4737, 31.2304)
                : center(number(dc.get("lon")), number(dc.get("lat")));
        return result("ranking", answer.toString(), List.of(), center, null,
                steps, List.of(card("行政区排名 Top 10", "stats", rows)), null);
    }

    private List<Map<String, Object>> greedy(double lon, double lat,
                                             List<Map<String, Object>> candidates,
                                             int limit) {
        List<Map<String, Object>> remaining = new ArrayList<>(candidates);
        List<Map<String, Object>> ordered = new ArrayList<>();
        double curLon = lon;
        double curLat = lat;
        while (!remaining.isEmpty() && ordered.size() < limit) {
            Map<String, Object> best = null;
            double bestDistance = Double.MAX_VALUE;
            for (Map<String, Object> candidate : remaining) {
                double d = haversine(curLon, curLat,
                        number(candidate.get("lon")), number(candidate.get("lat")));
                if (d < bestDistance) {
                    bestDistance = d;
                    best = candidate;
                }
            }
            if (best == null) break;
            remaining.remove(best);
            Map<String, Object> item = new LinkedHashMap<>(best);
            item.put("order", ordered.size() + 1);
            item.put("leg_distance_m", Math.round(bestDistance));
            ordered.add(item);
            curLon = number(best.get("lon"));
            curLat = number(best.get("lat"));
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

    private Place resolve(String raw) {
        Place place = new Place();
        if (raw == null || raw.isBlank()) {
            Map<String, Object> dc = tools.defaultCenter();
            place.name = "当前区域中心";
            place.lon = number(dc.get("lon"));
            place.lat = number(dc.get("lat"));
            place.found = true;
            return place;
        }
        List<Map<String, Object>> places = tools.searchPlace(raw);
        if (!places.isEmpty()) {
            Map<String, Object> first = places.get(0);
            place.name = String.valueOf(first.get("name"));
            place.lon = number(first.get("lon"));
            place.lat = number(first.get("lat"));
            place.found = true;
            return place;
        }
        Map<String, Object> dc = tools.districtCenter(raw);
        if (!dc.isEmpty()) {
            place.name = String.valueOf(dc.get("name"));
            place.lon = number(dc.get("lon"));
            place.lat = number(dc.get("lat"));
            place.found = true;
            return place;
        }
        Map<String, Object> center = tools.defaultCenter();
        place.name = raw;
        place.lon = number(center.get("lon"));
        place.lat = number(center.get("lat"));
        place.found = false;
        return place;
    }

    private Map<String, Object> result(String intent, String answer,
                                       List<Map<String, Object>> data,
                                       Map<String, Object> center, Integer radius,
                                       List<Map<String, Object>> steps,
                                       List<Map<String, Object>> cards,
                                       List<List<Double>> route) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intent", intent);
        out.put("answer", answer);
        out.put("data", data);
        out.put("center", center);
        if (radius != null) out.put("radius", radius);
        out.put("steps", steps);
        out.put("cards", cards);
        if (route != null) out.put("route", route);
        out.put("engine", "advanced-rule");
        return out;
    }

    private Map<String, Object> withGroup(Map<String, Object> row, String area) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.put("area", area);
        return copy;
    }

    private Map<String, Object> card(String title, String type,
                                     List<Map<String, Object>> rows) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("title", title);
        card.put("type", type);
        card.put("rows", rows);
        return card;
    }

    private Map<String, Object> row(String label, double value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("value", value);
        return row;
    }

    private Map<String, Object> center(double lon, double lat) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("lon", lon);
        c.put("lat", lat);
        return c;
    }

    private void addStep(List<Map<String, Object>> steps, String tool, String input,
                         String output, long startNanos) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("tool", tool);
        step.put("input", input);
        step.put("output", output);
        step.put("ms", (System.nanoTime() - startNanos) / 1_000_000);
        steps.add(step);
    }

    private int count(List<Map<String, Object>> stats, String category) {
        int total = 0;
        for (Map<String, Object> row : stats) {
            if (category == null || category.equals(row.get("category"))) {
                total += number(row.get("cnt"));
            }
        }
        return total;
    }

    private String label(String category) {
        return category == null ? "POI" : category + " POI";
    }

    private String category(String q) {
        for (Map.Entry<String, String> e : CATEGORY.entrySet()) {
            if (q.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    private int radius(String q, int fallback) {
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(公里|千米|km|KM|米|m)").matcher(q);
        if (m.find()) {
            double value = Double.parseDouble(m.group(1));
            String unit = m.group(2);
            boolean km = unit.contains("公") || unit.contains("千") || unit.equalsIgnoreCase("km");
            return (int) Math.round(km ? value * 1000 : value);
        }
        return fallback;
    }

    private int limit(String q, int fallback) {
        Matcher m = Pattern.compile("(\\d+)\\s*个").matcher(q);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private String extractPlace(String q) {
        String[] patterns = {
                "从(.+?)出发",
                "离(.+?)最近",
                "在(.+?)(?:附近|周围|周边)",
                "(.+?)(?:附近|周围|周边)",
                "(.+?)的"
        };
        for (String pattern : patterns) {
            Matcher m = Pattern.compile(pattern).matcher(q);
            if (m.find()) {
                String s = cleanPlace(m.group(1));
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    private String cleanPlace(String raw) {
        if (raw == null) return null;
        return raw
                .replaceAll("^(帮我|帮忙|查一下|看看|看一下|请问|我想知道|比较|对比|在|从|的)+", "")
                .replaceAll("[\\d.]+\\s*(公里|千米|km|KM|米|m)", "")
                .replaceAll("(附近|周围|周边|的|内|里|出发|最近|有哪些|有什么|多少个|几家)+$", "")
                .trim();
    }

    private double number(Object value) {
        return value == null ? 0 : ((Number) value).doubleValue();
    }

    private static class Place {
        String name;
        double lon;
        double lat;
        boolean found;
    }
}
