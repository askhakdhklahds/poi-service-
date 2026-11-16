package com.example.poi.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LlmAgentClient {

    private static final Logger log = LoggerFactory.getLogger(LlmAgentClient.class);

    private static final String SYSTEM_PROMPT = """
            你是城市 POI 空间分析助手。用户用中文提问。
            工作流程：
            1. 如果问题没有坐标，先调用 search_place 一次获取地点坐标。
            2. 拿到坐标后，立即用 pois_within / nearest_pois / buffer_stats / district_stats 获取真实数据。
            3. search_place 只调用一次；不要重复搜索同一地点。
            4. 最多调用 2 次工具，拿到结果后必须直接用中文回答，不要再调用工具。
            不要编造坐标或数量。回答简洁，给出数量、地点名和距离。
            """;

    private final GisTools tools;
    private final ObjectMapper mapper;
    private final RestClient client;
    private final String model;
    private final boolean enabled;
    private final String apiKey;

    public LlmAgentClient(GisTools tools, ObjectMapper mapper,
                          @Value("${agent.llm.enabled:false}") boolean enabled,
                          @Value("${agent.llm.base-url:https://api.deepseek.com/v1}") String baseUrl,
                          @Value("${agent.llm.model:deepseek-chat}") String model,
                          @Value("${agent.llm.api-key:}") String apiKey,
                          @Value("${agent.llm.timeout-seconds:60}") int timeoutSeconds) {
        this.tools = tools;
        this.mapper = mapper;
        this.model = model;
        this.enabled = enabled;
        this.apiKey = apiKey;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(timeoutSeconds * 1000);
        this.client = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .defaultHeaders(h -> {
                    if (apiKey != null && !apiKey.isBlank()) {
                        h.setBearerAuth(apiKey);
                    }
                })
                .build();
    }

    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    public Map<String, Object> ask(String question) throws Exception {
        return ask(question, List.of());
    }

    public Map<String, Object> ask(String question,
                                   List<Map<String, String>> history) throws Exception {
        ArrayNode messages = mapper.createArrayNode();
        messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        if (history != null) {
            for (Map<String, String> item : history) {
                String role = item.get("role");
                if (!"user".equals(role) && !"assistant".equals(role)) {
                    continue;
                }
                messages.addObject().put("role", role)
                        .put("content", item.get("content"));
            }
        }
        messages.addObject().put("role", "user").put("content", question);

        ArrayNode toolDefs = buildTools();
        List<Map<String, Object>> collected = new ArrayList<>();
        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, Object> center = null;
        Integer radius = null;

        int maxRounds = 2;
        for (int round = 0; round < maxRounds; round++) {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.set("messages", messages);
            body.set("tools", toolDefs);
            body.put("tool_choice", "auto");
            body.put("temperature", 0.1);

            JsonNode resp = client.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode msg = resp.path("choices").path(0).path("message");
            JsonNode toolCalls = msg.path("tool_calls");
            String roundContent = msg.path("content").asText("");
            log.info("LLM round {} contentLen={} toolCalls={}",
                    round, roundContent.length(),
                    toolCalls.isArray() ? toolCalls.size() : 0);

            if (toolCalls.isArray() && toolCalls.size() > 0) {
                ObjectNode assistant = mapper.createObjectNode();
                assistant.put("role", "assistant");
                assistant.set("tool_calls", toolCalls);
                if (msg.hasNonNull("content")) {
                    assistant.put("content", msg.get("content").asText());
                }
                messages.add(assistant);

                for (JsonNode call : toolCalls) {
                    String id = call.path("id").asText();
                    String name = call.path("function").path("name").asText();
                    String argsText = call.path("function").path("arguments").asText("{}");
                    JsonNode args = mapper.readTree(argsText);
                    long toolStart = System.nanoTime();
                    Map<String, Object> result = executeTool(name, args, collected);
                    Map<String, Object> step = new LinkedHashMap<>();
                    step.put("tool", name);
                    step.put("input", argsText);
                    step.put("output", summarizeToolResult(result));
                    step.put("ms", (System.nanoTime() - toolStart) / 1_000_000);
                    steps.add(step);

                    if (result.get("center") instanceof Map<?, ?> c) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> cast = (Map<String, Object>) c;
                        center = cast;
                    }
                    if (result.get("radius") instanceof Number n) {
                        radius = n.intValue();
                    }

                    ObjectNode toolMsg = mapper.createObjectNode();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", id);
                    toolMsg.put("content", mapper.writeValueAsString(trimForLlm(result)));
                    messages.add(toolMsg);
                }
                continue;
            }

            String answer = roundContent;
            if (answer.isBlank()) {
                answer = finalAnswerWithoutTools(question, collected);
            }
            return buildResult(answer, collected, center, radius, steps);
        }
        String answer = finalAnswerWithoutTools(question, collected);
        return buildResult(answer, collected, center, radius, steps);
    }

    private Map<String, Object> buildResult(String answer,
                                            List<Map<String, Object>> collected,
                                            Map<String, Object> center,
                                            Integer radius,
                                            List<Map<String, Object>> steps) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intent", "llm");
        out.put("answer", answer);
        out.put("data", collected);
        if (center != null) {
            out.put("center", center);
        }
        if (radius != null) {
            out.put("radius", radius);
        }
        out.put("steps", steps);
        out.put("engine", "llm");
        return out;
    }

    private String summarizeToolResult(Map<String, Object> result) {
        Object data = result.get("data");
        if (data instanceof List<?> list) {
            return list.size() + " results";
        }
        return "ok";
    }

    private String finalAnswerWithoutTools(String question,
                                           List<Map<String, Object>> collected) {
        try {
            ArrayNode msgs = mapper.createArrayNode();
            msgs.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
            List<Map<String, Object>> trimmed = collected.size() > 20
                    ? collected.subList(0, 20) : collected;
            msgs.addObject().put("role", "user").put("content",
                    "用户问题：" + question
                            + "\n已获取的工具结果（JSON）："
                            + mapper.writeValueAsString(trimmed)
                            + "\n请直接用中文回答用户问题，不要再调用工具。");
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.set("messages", msgs);
            body.put("temperature", 0.1);
            JsonNode resp = client.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(JsonNode.class);
            String content = resp.path("choices").path(0).path("message")
                    .path("content").asText("");
            if (!content.isBlank()) {
                return content;
            }
        } catch (Exception e) {
            log.warn("final answer without tools failed: {}", e.getMessage());
        }
        if (collected.isEmpty()) {
            return "已收到问题，但没有获取到 POI 数据。";
        }
        Map<String, Object> first = collected.get(0);
        return "已获取 " + collected.size() + " 个 POI，最近的为【"
                + first.get("name") + "】（" + first.get("distance_m") + " 米）。";
    }

    public String complete(String systemPrompt, String userPrompt) throws Exception {
        ArrayNode messages = mapper.createArrayNode();
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.set("messages", messages);
        body.put("temperature", 0.3);
        JsonNode resp = client.post().uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(JsonNode.class);
        return resp.path("choices").path(0).path("message")
                .path("content").asText("");
    }

    private Map<String, Object> executeTool(String name, JsonNode args,
                                            List<Map<String, Object>> collected) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (name) {
            case "search_place" -> {
                List<Map<String, Object>> rows =
                        tools.searchPlace(args.path("keyword").asText(""));
                out.put("data", rows);
                if (!rows.isEmpty()) {
                    out.put("center", Map.of(
                            "lon", rows.get(0).get("lon"),
                            "lat", rows.get(0).get("lat")));
                }
            }
            case "pois_within" -> {
                double lon = args.path("lon").asDouble();
                double lat = args.path("lat").asDouble();
                int radius = args.path("radius").asInt(1000);
                String category = args.hasNonNull("category")
                        ? args.get("category").asText() : null;
                int limit = args.path("limit").asInt(100);
                List<Map<String, Object>> rows =
                        tools.poisWithin(lon, lat, radius, category, limit);
                collected.addAll(rows);
                out.put("data", rows);
                out.put("center", Map.of("lon", lon, "lat", lat));
                out.put("radius", radius);
            }
            case "nearest_pois" -> {
                double lon = args.path("lon").asDouble();
                double lat = args.path("lat").asDouble();
                int limit = args.path("limit").asInt(10);
                String category = args.hasNonNull("category")
                        ? args.get("category").asText() : null;
                List<Map<String, Object>> rows =
                        tools.nearestPois(lon, lat, limit, category);
                collected.addAll(rows);
                out.put("data", rows);
                out.put("center", Map.of("lon", lon, "lat", lat));
            }
            case "buffer_stats" -> {
                double lon = args.path("lon").asDouble();
                double lat = args.path("lat").asDouble();
                int radius = args.path("radius").asInt(1000);
                List<Map<String, Object>> rows = tools.bufferStats(lon, lat, radius);
                out.put("data", rows);
                out.put("center", Map.of("lon", lon, "lat", lat));
                out.put("radius", radius);
            }
            case "district_stats" -> {
                List<Map<String, Object>> rows =
                        tools.districtStats(args.path("name_or_code").asText(""));
                out.put("data", rows);
            }
            case "list_districts" -> out.put("data", tools.listDistricts());
            default -> out.put("error", "unknown tool: " + name);
        }
        return out;
    }

    private Map<String, Object> trimForLlm(Map<String, Object> result) {
        Map<String, Object> copy = new LinkedHashMap<>(result);
        Object data = copy.get("data");
        if (data instanceof List<?> list && list.size() > 20) {
            copy.put("data", list.subList(0, 20));
            copy.put("dataTruncated", true);
            copy.put("dataTotal", list.size());
        }
        return copy;
    }

    private ArrayNode buildTools() {
        ArrayNode arr = mapper.createArrayNode();
        arr.add(function("search_place", "按名称搜索地点，返回名称、分类、经纬度",
                schema(Map.of("keyword", "string"), List.of("keyword"))));
        arr.add(function("pois_within", "查询某个坐标附近一定半径内的 POI",
                schema(Map.of(
                        "lon", "number", "lat", "number",
                        "radius", "integer", "category", "string", "limit", "integer"),
                        List.of("lon", "lat", "radius"))));
        arr.add(function("nearest_pois", "查询离某个坐标最近的 POI",
                schema(Map.of(
                        "lon", "number", "lat", "number",
                        "limit", "integer", "category", "string"),
                        List.of("lon", "lat"))));
        arr.add(function("buffer_stats", "统计某个坐标附近半径内各分类 POI 数量",
                schema(Map.of("lon", "number", "lat", "number", "radius", "integer"),
                        List.of("lon", "lat", "radius"))));
        arr.add(function("district_stats", "查询行政区 POI 总数",
                schema(Map.of("name_or_code", "string"), List.of("name_or_code"))));
        arr.add(function("list_districts", "列出所有行政区",
                schema(Map.of(), List.of())));
        return arr;
    }

    private ObjectNode function(String name, String description, ObjectNode parameters) {
        ObjectNode f = mapper.createObjectNode();
        f.put("type", "function");
        ObjectNode fn = f.putObject("function");
        fn.put("name", name);
        fn.put("description", description);
        fn.set("parameters", parameters);
        return f;
    }

    private ObjectNode schema(Map<String, String> props, List<String> required) {
        ObjectNode s = mapper.createObjectNode();
        s.put("type", "object");
        ObjectNode properties = s.putObject("properties");
        for (Map.Entry<String, String> e : props.entrySet()) {
            properties.putObject(e.getKey()).put("type", e.getValue());
        }
        ArrayNode req = s.putArray("required");
        required.forEach(req::add);
        return s;
    }
}
