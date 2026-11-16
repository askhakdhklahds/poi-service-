package com.example.poi.agent.role;

import com.example.poi.agent.AdvancedAgent;
import com.example.poi.agent.LlmAgentClient;
import com.example.poi.agent.RuleBasedAgent;
import com.example.poi.agent.intent.IntentResult;
import com.example.poi.agent.orchestrator.AgentRequest;
import com.example.poi.agent.orchestrator.AgentType;
import com.example.poi.agent.tool.ToolRegistry;
import com.example.poi.agent.tool.ToolResult;
import com.example.poi.service.route.RoutePoint;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RoutePlanningAgent extends BaseAgent {

    private final AdvancedAgent advancedAgent;

    public RoutePlanningAgent(ToolRegistry tools, LlmAgentClient llmClient,
                              RuleBasedAgent ruleBasedAgent, AdvancedAgent advancedAgent) {
        super(tools, llmClient, ruleBasedAgent);
        this.advancedAgent = advancedAgent;
    }

    @Override
    public AgentType type() {
        return AgentType.ROUTE_PLANNING;
    }

    @Override
    protected Map<String, Object> executeDeterministic(AgentRequest request,
                                                       IntentResult intent) {
        String fromName = str(intent.entities().get("from"));
        String toName = str(intent.entities().get("to"));

        if (fromName != null && toName != null) {
            RoutePoint from = resolvePoint(fromName);
            RoutePoint to = resolvePoint(toName);
            if (from != null && to != null) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("fromLon", from.lon());
                args.put("fromLat", from.lat());
                args.put("fromName", from.name());
                args.put("toLon", to.lon());
                args.put("toLat", to.lat());
                args.put("toName", to.name());
                args.put("modes", List.of("car", "walk"));
                args.put("alternatives", 3);

                ToolResult result = tools.execute("route_compare", type(), args);
                if (result.success()) {
                    return buildRouteResult(result, from, to);
                }
            }
        }

        Map<String, Object> result = advancedAgent.tryAsk(request.message());
        return result != null ? result : super.executeDeterministic(request, intent);
    }

    private RoutePoint resolvePoint(String name) {
        ToolResult search = tools.execute("search_place", type(),
                args("keyword", name));
        if (search.success()) {
            List<Map<String, Object>> rows = list(search.data());
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                return new RoutePoint(number(row.get("lon")), number(row.get("lat")),
                        str(row.get("name")));
            }
        }
        ToolResult district = tools.execute("district_center", type(),
                args("nameOrCode", name));
        if (district.success()) {
            Map<String, Object> row = map(district.data());
            if (!row.isEmpty()) {
                return new RoutePoint(number(row.get("lon")), number(row.get("lat")),
                        str(row.get("name")));
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildRouteResult(ToolResult result,
                                                 RoutePoint from, RoutePoint to) {
        Map<String, Object> data = result.data() instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        List<Map<String, Object>> routes = data.get("routes") instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();

        if (routes.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("intent", "route");
            out.put("answer", "没有找到可用路线，请换一个起点或终点。");
            out.put("data", List.of());
            out.put("engine", "route-plan");
            return out;
        }

        long carCount = routes.stream()
                .filter(route -> "car".equals(route.get("mode"))).count();
        long walkCount = routes.stream()
                .filter(route -> "walk".equals(route.get("mode"))).count();
        Map<String, Object> fastestCar = routes.stream()
                .filter(route -> "car".equals(route.get("mode")))
                .min(Comparator.comparingDouble(
                        route -> number(route.get("durationSeconds"))))
                .orElse(null);
        Map<String, Object> fastestWalk = routes.stream()
                .filter(route -> "walk".equals(route.get("mode")))
                .min(Comparator.comparingDouble(
                        route -> number(route.get("durationSeconds"))))
                .orElse(null);

        StringBuilder answer = new StringBuilder("从【").append(from.name())
                .append("】到【").append(to.name()).append("】：已为你规划 ")
                .append(carCount).append(" 条驾车路线和 ")
                .append(walkCount).append(" 条步行路线。");
        if (fastestCar != null) {
            answer.append("驾车最快约 ").append(km(fastestCar))
                    .append(" / ").append(minutes(fastestCar)).append("；");
        }
        if (fastestWalk != null) {
            answer.append("步行推荐约 ").append(km(fastestWalk))
                    .append(" / ").append(minutes(fastestWalk)).append("。");
        }
        answer.append("打开多路线对比查看地图和沿途特点。");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < routes.size(); i++) {
            Map<String, Object> route = routes.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("order", i + 1);
            row.put("name", route.get("label"));
            row.put("distance_m", route.get("distanceMeters"));
            row.put("duration_s", route.get("durationSeconds"));
            rows.add(row);
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("title", "路线对比：" + from.name() + " → " + to.name());
        card.put("type", "route");
        card.put("rows", rows);

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("tool", "route_compare");
        step.put("input", "from=" + from.name() + " to=" + to.name());
        step.put("output", routes.size() + " routes");
        step.put("ms", result.latencyMs());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intent", "route");
        out.put("answer", answer.toString());
        out.put("data", List.of());
        out.put("center", center((from.lon() + to.lon()) / 2.0,
                (from.lat() + to.lat()) / 2.0));
        out.put("steps", List.of(step));
        out.put("cards", List.of(card));
        out.put("routePlanUrl", routeUrl(from, to));
        out.put("engine", "route-plan");
        return out;
    }

    private String routeUrl(RoutePoint from, RoutePoint to) {
        return "/route.html?fromLon=" + from.lon()
                + "&fromLat=" + from.lat()
                + "&fromName=" + encode(from.name())
                + "&toLon=" + to.lon()
                + "&toLat=" + to.lat()
                + "&toName=" + encode(to.name())
                + "&modes=car,walk&alternatives=3";
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String km(Map<String, Object> route) {
        return String.format("%.1f km",
                number(route.get("distanceMeters")) / 1000.0);
    }

    private String minutes(Map<String, Object> route) {
        return Math.max(1, Math.round(number(route.get("durationSeconds")) / 60.0))
                + " 分钟";
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

    private String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }
}
