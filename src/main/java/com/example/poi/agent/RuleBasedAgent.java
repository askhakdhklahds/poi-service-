package com.example.poi.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RuleBasedAgent {

    private final GisTools tools;

    @Value("${agent.default-lon:114.3055}")
    private double defaultLon;

    @Value("${agent.default-lat:30.5928}")
    private double defaultLat;

    @Value("${agent.default-radius:1000}")
    private int defaultRadius;

    private static final Map<String, String> CATEGORY_KEYWORDS = new LinkedHashMap<>();

    static {
        put("医院", "医院", "诊所", "药房", "药店", "看病", "医疗");
        put("餐饮", "餐厅", "餐饮", "吃饭", "美食", "饭店", "咖啡", "快餐");
        put("购物", "购物", "商场", "超市", "商店", "买东西");
        put("学校", "学校", "大学", "学院", "中学", "小学", "幼儿园");
        put("交通", "地铁", "公交", "车站", "停车", "加油", "充电", "交通");
        put("景点", "景点", "公园", "旅游", "景区", "博物馆");
        put("商业", "商业", "公司", "写字楼", "办公");
    }

    private static void put(String category, String... keywords) {
        for (String k : keywords) {
            CATEGORY_KEYWORDS.put(k, category);
        }
    }

    public RuleBasedAgent(GisTools tools) {
        this.tools = tools;
    }

    public Map<String, Object> ask(String question) {
        String q = question == null ? "" : question.trim();
        String category = detectCategory(q);
        int radius = detectRadius(q, defaultRadius);
        int limit = detectLimit(q, 10);

        Map<String, String> district = matchDistrict(q);
        String place = extractPlace(q);

        boolean nearby = q.contains("附近") || q.contains("周围") || q.contains("周边")
                || q.contains("离我");
        boolean nearest = q.contains("最近") && !nearby;
        boolean stats = q.contains("统计") || q.contains("分布") || q.contains("多少")
                || q.contains("数量") || q.contains("各类");

        if (district != null && stats && !nearby && !nearest) {
            Map<String, Object> center = tools.districtCenter(district.get("name"));
            List<Map<String, Object>> rows = tools.districtStats(district.get("name"));
            String answer;
            if (rows.isEmpty()) {
                answer = "没有找到【" + district.get("name") + "】的统计结果。";
            } else {
                Object cnt = rows.get(0).get("poi_cnt");
                answer = "【" + district.get("name") + "】共有 " + cnt + " 个 POI。";
            }
            return result("district", answer, rows, center, null, district.get("name"), true);
        }

        double lon = defaultLon;
        double lat = defaultLat;
        Map<String, Object> dataCenter = tools.defaultCenter();
        if (!dataCenter.isEmpty()) {
            lon = number(dataCenter.get("lon"));
            lat = number(dataCenter.get("lat"));
        }
        String resolvedName = place;
        boolean found = false;

        if (place != null && !place.isBlank()) {
            List<Map<String, Object>> places = tools.searchPlace(place);
            if (!places.isEmpty()) {
                Map<String, Object> first = places.get(0);
                lon = number(first.get("lon"));
                lat = number(first.get("lat"));
                resolvedName = String.valueOf(first.get("name"));
                found = true;
            } else {
                Map<String, Object> center = tools.districtCenter(place);
                if (!center.isEmpty()) {
                    lon = number(center.get("lon"));
                    lat = number(center.get("lat"));
                    resolvedName = String.valueOf(center.get("name"));
                    found = true;
                }
            }
        }

        boolean placeSpecified = place != null && !place.isBlank();
        if (!placeSpecified) {
            resolvedName = "当前区域中心";
        }

        String placeText;
        if (!placeSpecified) {
            placeText = "【当前区域中心】";
        } else if (found) {
            placeText = "【" + resolvedName + "】";
        } else {
            placeText = "【" + resolvedName + "】（未找到该地点，使用默认中心）";
        }
        String categoryText = category == null ? "" : category;

        if (nearest) {
            List<Map<String, Object>> rows =
                    tools.nearestPois(lon, lat, limit, category);
            StringBuilder answer = new StringBuilder();
            answer.append("离").append(placeText).append("最近的")
                    .append(limit).append("个").append(categoryText).append("POI：");
            appendTop(rows, answer, limit);
            if (rows.isEmpty()) {
                answer.append("没有找到符合条件的 POI。");
            }
            return result("nearest", answer.toString(), rows, center(lon, lat), null,
                    resolvedName, found);
        }

        if (stats && nearby) {
            List<Map<String, Object>> rows = tools.bufferStats(lon, lat, radius);
            StringBuilder answer = new StringBuilder();
            answer.append(placeText).append("附近").append(radius).append("米内分类统计：");
            if (rows.isEmpty()) {
                answer.append("没有找到 POI。");
            } else {
                for (Map<String, Object> row : rows) {
                    answer.append(row.get("category")).append(" ")
                            .append(row.get("cnt")).append("、");
                }
                answer.setLength(answer.length() - 1);
                answer.append("。");
            }
            return result("buffer", answer.toString(), List.of(), center(lon, lat), radius,
                    resolvedName, found);
        }

        List<Map<String, Object>> rows =
                tools.poisWithin(lon, lat, radius, category, 500);
        StringBuilder answer = new StringBuilder();
        answer.append(placeText).append("附近").append(radius).append("米内找到 ")
                .append(rows.size()).append(" 个").append(categoryText).append("POI。");
        if (!rows.isEmpty()) {
            answer.append("最近的是");
            Map<String, Object> first = rows.get(0);
            answer.append("【").append(first.get("name")).append("】（")
                    .append(first.get("distance_m")).append(" 米）。");
        }
        return result("within", answer.toString(), rows, center(lon, lat), radius,
                resolvedName, found);
    }

    private void appendTop(List<Map<String, Object>> rows, StringBuilder answer, int limit) {
        int n = Math.min(rows.size(), Math.min(limit, 5));
        if (n == 0) {
            return;
        }
        answer.append(" ");
        for (int i = 0; i < n; i++) {
            Map<String, Object> row = rows.get(i);
            answer.append(i + 1).append(". ").append(row.get("name"))
                    .append("（").append(row.get("distance_m")).append(" 米）");
            if (i < n - 1) {
                answer.append("；");
            }
        }
        answer.append("。");
    }

    private Map<String, Object> result(String intent, String answer,
                                       List<Map<String, Object>> data,
                                       Map<String, Object> center, Integer radius,
                                       String place, boolean placeFound) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intent", intent);
        out.put("answer", answer);
        out.put("data", data);
        out.put("center", center);
        if (radius != null) {
            out.put("radius", radius);
        }
        out.put("place", place);
        out.put("placeFound", placeFound);
        out.put("engine", "rule");
        return out;
    }

    private Map<String, Object> center(double lon, double lat) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("lon", lon);
        c.put("lat", lat);
        return c;
    }

    private String detectCategory(String q) {
        for (Map.Entry<String, String> e : CATEGORY_KEYWORDS.entrySet()) {
            if (q.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    private int detectRadius(String q, int fallback) {
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(公里|千米|km|KM|米|m)")
                .matcher(q);
        if (m.find()) {
            double value = Double.parseDouble(m.group(1));
            String unit = m.group(2);
            boolean km = unit.contains("公") || unit.contains("千") || unit.equalsIgnoreCase("km");
            return (int) Math.round(km ? value * 1000 : value);
        }
        return fallback;
    }

    private int detectLimit(String q, int fallback) {
        Matcher m = Pattern.compile("(?:最近|前|top)\\s*(\\d+)\\s*个", Pattern.CASE_INSENSITIVE)
                .matcher(q);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private String extractPlace(String q) {
        String[] patterns = {
                "离(.+?)最近",
                "在(.+?)(?:附近|周围|周边)",
                "(.+?)(?:附近|周围|周边)",
                "(.+?)的(?:附近|周围|周边)"
        };
        for (String p : patterns) {
            Matcher m = Pattern.compile(p).matcher(q);
            if (m.find()) {
                String s = m.group(1)
                        .replaceAll("^(帮我|帮忙|查一下|看看|看一下|请问|我想知道|找一下|在|的)+", "")
                        .replaceAll("(的|附近|周围|周边|最近|有哪些|有什么|多少个|几家)+$", "")
                        .trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    private Map<String, String> matchDistrict(String q) {
        for (Map<String, Object> d : tools.listDistricts()) {
            String name = String.valueOf(d.get("name"));
            String shortName = name.substring(0, Math.min(2, name.length()));
            if (q.contains(name) || q.contains(shortName)) {
                Map<String, String> out = new LinkedHashMap<>();
                out.put("adcode", String.valueOf(d.get("adcode")));
                out.put("name", name);
                return out;
            }
        }
        return null;
    }

    private double number(Object value) {
        return value == null ? 0 : ((Number) value).doubleValue();
    }
}
