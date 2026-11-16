package com.example.poi.agent.role;

import com.example.poi.agent.AdvancedAgent;
import com.example.poi.agent.LlmAgentClient;
import com.example.poi.agent.RuleBasedAgent;
import com.example.poi.agent.intent.IntentResult;
import com.example.poi.agent.orchestrator.AgentRequest;
import com.example.poi.agent.orchestrator.AgentType;
import com.example.poi.agent.tool.ToolRegistry;
import com.example.poi.agent.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class SpatialQueryAgent extends BaseAgent {

    private final AdvancedAgent advancedAgent;

    public SpatialQueryAgent(ToolRegistry tools, LlmAgentClient llmClient,
                             RuleBasedAgent ruleBasedAgent, AdvancedAgent advancedAgent) {
        super(tools, llmClient, ruleBasedAgent);
        this.advancedAgent = advancedAgent;
    }

    @Override
    public AgentType type() {
        return AgentType.SPATIAL_QUERY;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> executeDeterministic(AgentRequest request,
                                                       IntentResult intent) {
        Map<String, Object> entities = intent.entities();
        String place = str(entities.get("place"));
        String category = str(entities.get("category"));
        int radius = integer(entities.get("radius"), 1000);
        int limit = integer(entities.get("limit"), 10);

        String scene = str(entities.get("scene"));
        if (scene == null) {
            scene = "NEARBY";
        }
        if ("DISTRICT_STATS".equals(scene) && place == null) {
            scene = "RANKING";
        }

        List<Map<String, Object>> steps = new ArrayList<>();
        boolean preferDistrict = "DISTRICT_STATS".equals(scene)
                || ("RANKING".equals(scene) && place != null
                && !containsAny(request.message(), "附近", "周围", "周边"));
        Place resolved = preferDistrict && place != null
                ? resolveDistrict(place, steps)
                : resolvePlace(place, steps);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("lon", resolved.lon);
        args.put("lat", resolved.lat);
        args.put("radius", radius);
        args.put("category", category);
        args.put("limit", limit);

        List<Map<String, Object>> data = new ArrayList<>();
        List<Map<String, Object>> cards = new ArrayList<>();
        String answer;

        switch (scene) {
            case "NEAREST" -> {
                ToolResult result = tools.execute("nearest_pois", type(), args);
                addStep(steps, "nearest_pois", input(resolved, category, limit), result);
                data = list(result.data());
                answer = result.success()
                        ? nearestAnswer(resolved, data, category, limit)
                        : failAnswer(result);
            }
            case "BUFFER_STATS" -> {
                ToolResult result = tools.execute("buffer_stats", type(), args);
                addStep(steps, "buffer_stats", input(resolved, null, radius), result);
                data = list(result.data());
                cards = statsCards("周边分类统计", data, "category", "cnt");
                answer = result.success()
                        ? bufferAnswer(resolved, data, radius)
                        : failAnswer(result);
            }
            case "DISTRICT_STATS" -> {
                String nameOrCode = resolved.name != null ? resolved.name : place;
                ToolResult result = tools.execute("district_stats", type(),
                        args("nameOrCode", nameOrCode));
                addStep(steps, "district_stats", "nameOrCode=" + nameOrCode, result);
                data = list(result.data());
                answer = result.success()
                        ? districtAnswer(data, nameOrCode)
                        : failAnswer(result);
            }
            case "RANKING" -> {
                boolean nearby = containsAny(request.message(), "附近", "周围", "周边");
                if (nearby) {
                    ToolResult result = tools.execute("buffer_stats", type(), args);
                    addStep(steps, "buffer_stats", input(resolved, null, radius), result);
                    data = list(result.data());
                    cards = statsCards("周边分类排名", data, "category", "cnt");
                    answer = result.success()
                            ? bufferRankingAnswer(resolved, data, radius)
                            : failAnswer(result);
                } else {
                    ToolResult result = tools.execute("district_ranking", type(),
                            args("limit", 10));
                    addStep(steps, "district_ranking", "limit=10", result);
                    data = list(result.data());
                    cards = statsCards("行政区 POI 排名", data, "name", "poi_cnt");
                    answer = result.success()
                            ? districtRankingAnswer(data)
                            : failAnswer(result);
                }
            }
            case "COMPARE" -> {
                Map<String, Object> compared = advancedAgent.tryAsk(request.message());
                if (compared != null) {
                    return compared;
                }
                ToolResult result = tools.execute("pois_within", type(), args);
                addStep(steps, "pois_within", input(resolved, category, radius), result);
                data = list(result.data());
                answer = result.success()
                        ? withinAnswer(resolved, data, category, radius)
                        : failAnswer(result);
            }
            default -> {
                ToolResult result = tools.execute("pois_within", type(), args);
                addStep(steps, "pois_within", input(resolved, category, radius), result);
                data = list(result.data());
                answer = result.success()
                        ? withinAnswer(resolved, data, category, radius)
                        : failAnswer(result);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intent", scene.toLowerCase(Locale.ROOT));
        out.put("answer", answer);
        out.put("data", data);
        out.put("center", center(resolved.lon, resolved.lat));
        out.put("radius", radius);
        out.put("steps", steps);
        out.put("cards", cards);
        out.put("place", resolved.name);
        out.put("placeFound", resolved.found);
        out.put("engine", "tool-registry");
        return out;
    }

    private Place resolveDistrict(String place, List<Map<String, Object>> steps) {
        Place district = districtCenterLookup(place, steps);
        if (district != null) {
            return district;
        }
        Place fallback = new Place();
        fallback.name = place;
        fallback.found = false;
        applyDefaultCenter(fallback, steps);
        return fallback;
    }

    private Place resolvePlace(String place, List<Map<String, Object>> steps) {
        Place resolved = new Place();
        if (place != null && !place.isBlank()) {
            if (looksLikeDistrict(place)) {
                Place district = districtCenterLookup(place, steps);
                if (district != null) {
                    return district;
                }
            }

            ToolResult search = tools.execute("search_place", type(),
                    args("keyword", place));
            addStep(steps, "search_place", "keyword=" + place, search);
            if (search.success()) {
                List<Map<String, Object>> rows = list(search.data());
                if (!rows.isEmpty()) {
                    Map<String, Object> first = rows.get(0);
                    resolved.lon = number(first.get("lon"));
                    resolved.lat = number(first.get("lat"));
                    resolved.name = str(first.get("name"));
                    resolved.found = true;
                    return resolved;
                }
            }

            Place district = districtCenterLookup(place, steps);
            if (district != null) {
                return district;
            }
            resolved.name = place;
            resolved.found = false;
        }

        applyDefaultCenter(resolved, steps);
        return resolved;
    }

    private Place districtCenterLookup(String place, List<Map<String, Object>> steps) {
        ToolResult district = tools.execute("district_center", type(),
                args("nameOrCode", place));
        addStep(steps, "district_center", "nameOrCode=" + place, district);
        if (district.success()) {
            Map<String, Object> row = map(district.data());
            if (!row.isEmpty()) {
                Place result = new Place();
                result.lon = number(row.get("lon"));
                result.lat = number(row.get("lat"));
                result.name = str(row.get("name"));
                result.found = true;
                return result;
            }
        }
        return null;
    }

    private void applyDefaultCenter(Place place, List<Map<String, Object>> steps) {
        ToolResult fallback = tools.execute("default_center", type(), Map.of());
        addStep(steps, "default_center", "", fallback);
        Map<String, Object> row = map(fallback.data());
        place.lon = row.isEmpty() ? 121.4737 : number(row.get("lon"));
        place.lat = row.isEmpty() ? 31.2304 : number(row.get("lat"));
        if (place.name == null) {
            place.name = "当前区域中心";
            place.found = true;
        }
    }

    private boolean looksLikeDistrict(String place) {
        return place.endsWith("区") || place.endsWith("县");
    }

    private String nearestAnswer(Place place, List<Map<String, Object>> data,
                                 String category, int limit) {
        StringBuilder sb = new StringBuilder("离【").append(place.name).append("】最近的")
                .append(limit).append("个")
                .append(category == null ? "" : category).append("POI：");
        if (data.isEmpty()) {
            sb.append("没有找到符合条件的 POI。");
            return sb.toString();
        }
        int count = Math.min(data.size(), Math.min(limit, 5));
        for (int i = 0; i < count; i++) {
            Map<String, Object> row = data.get(i);
            sb.append(i + 1).append(". ").append(row.get("name"))
                    .append("（").append(row.get("distance_m")).append(" 米）");
            if (i < count - 1) {
                sb.append("；");
            }
        }
        return sb.append("。").toString();
    }

    private String withinAnswer(Place place, List<Map<String, Object>> data,
                                String category, int radius) {
        StringBuilder sb = new StringBuilder("【").append(place.name).append("】附近")
                .append(radius).append("米内找到 ").append(data.size()).append(" 个")
                .append(category == null ? "" : category).append("POI。");
        if (!data.isEmpty()) {
            Map<String, Object> first = data.get(0);
            sb.append("最近的是【").append(first.get("name")).append("】（")
                    .append(first.get("distance_m")).append(" 米）。");
        }
        return sb.toString();
    }

    private String bufferAnswer(Place place, List<Map<String, Object>> data, int radius) {
        StringBuilder sb = new StringBuilder("【").append(place.name).append("】附近")
                .append(radius).append("米内分类统计：");
        if (data.isEmpty()) {
            return sb.append("没有找到 POI。").toString();
        }
        for (Map<String, Object> row : data) {
            sb.append(row.get("category")).append(" ").append(row.get("cnt")).append("、");
        }
        sb.setLength(sb.length() - 1);
        return sb.append("。").toString();
    }

    private String districtAnswer(List<Map<String, Object>> data, String nameOrCode) {
        if (data.isEmpty()) {
            return "没有找到【" + nameOrCode + "】的统计结果。";
        }
        Map<String, Object> first = data.get(0);
        return "【" + first.getOrDefault("name", nameOrCode) + "】共有 "
                + first.get("poi_cnt") + " 个 POI。";
    }

    private String districtRankingAnswer(List<Map<String, Object>> data) {
        StringBuilder sb = new StringBuilder("行政区 POI 数量排名：");
        int count = Math.min(data.size(), 3);
        for (int i = 0; i < count; i++) {
            Map<String, Object> row = data.get(i);
            sb.append(i + 1).append(". ").append(row.get("name"))
                    .append("（").append(row.get("poi_cnt")).append(" 个）");
            if (i < count - 1) {
                sb.append("；");
            }
        }
        return sb.append("。").toString();
    }

    private String bufferRankingAnswer(Place place, List<Map<String, Object>> data,
                                       int radius) {
        if (data.isEmpty()) {
            return "【" + place.name + "】附近 " + radius + " 米内没有 POI。";
        }
        Map<String, Object> top = data.get(0);
        return "【" + place.name + "】附近 " + radius + " 米内，最多的是 "
                + top.get("category") + "（" + top.get("cnt") + " 个）。";
    }

    private String failAnswer(ToolResult result) {
        return "工具调用失败：" + (result.error() == null ? "未知错误" : result.error());
    }

    private List<Map<String, Object>> statsCards(String title,
                                                 List<Map<String, Object>> data,
                                                 String labelKey, String valueKey) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int count = Math.min(data.size(), 8);
        for (int i = 0; i < count; i++) {
            Map<String, Object> item = data.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", item.get(labelKey));
            row.put("value", number(item.get(valueKey)));
            rows.add(row);
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("title", title);
        card.put("type", "stats");
        card.put("rows", rows);
        return List.of(card);
    }

    private void addStep(List<Map<String, Object>> steps, String tool,
                         String input, ToolResult result) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("tool", tool);
        step.put("input", input);
        step.put("output", result.success() ? summarize(result.data()) : result.error());
        step.put("ms", result.latencyMs());
        steps.add(step);
    }

    private String summarize(Object data) {
        if (data instanceof List<?> list) {
            return list.size() + " results";
        }
        if (data instanceof Map<?, ?> map) {
            return map.isEmpty() ? "empty" : "ok";
        }
        return data == null ? "null" : "ok";
    }

    private String input(Place place, String category, int value) {
        return "place=" + place.name + " / category=" + category + " / value=" + value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Object data) {
        return data instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object data) {
        return data instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
    }

    private Map<String, Object> center(double lon, double lat) {
        Map<String, Object> center = new LinkedHashMap<>();
        center.put("lon", lon);
        center.put("lat", lat);
        return center;
    }

    private Map<String, Object> args(Object... keyValues) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            args.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return args;
    }

    private String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0 : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean containsAny(String text, String... words) {
        if (text == null) {
            return false;
        }
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static class Place {
        private String name;
        private double lon;
        private double lat;
        private boolean found;
    }
}
