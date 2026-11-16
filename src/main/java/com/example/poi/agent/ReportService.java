package com.example.poi.agent;

import com.example.poi.agent.memory.ConversationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReportService {

    private static final String SYSTEM_PROMPT = """
            你是 GeoAgent，一名城市空间分析师，专注 POI 空间分布、设施可达性和城市服务评估。
            请基于用户提供的真实统计数据生成一份中文 Markdown 报告，结构如下：
            # 城市空间诊断报告
            ## 1. 数据概览
            ## 2. 行政区空间结构
            ## 3. 周边设施结构
            ## 4. 趋势与建议
            ## 5. 数据说明
            要求：引用具体数字；不要编造数据；建议要结合空间分布、半径、行政区。
            """;

    private final GisTools tools;
    private final ConversationStore conversationStore;
    private final LlmAgentClient llmAgentClient;
    private final ObjectMapper mapper;

    public ReportService(GisTools tools,
                         ConversationStore conversationStore,
                         LlmAgentClient llmAgentClient,
                         ObjectMapper mapper) {
        this.tools = tools;
        this.conversationStore = conversationStore;
        this.llmAgentClient = llmAgentClient;
        this.mapper = mapper;
    }

    public Map<String, Object> report(String sessionId) {
        Map<String, Object> context = conversationStore.context(sessionId);
        String place = context.get("place") == null
                ? "当前区域" : String.valueOf(context.get("place"));
        double lon = context.get("lon") instanceof Number n ? n.doubleValue() : 121.4737;
        double lat = context.get("lat") instanceof Number n ? n.doubleValue() : 31.2304;
        int radius = context.get("radius") instanceof Number n ? n.intValue() : 1000;

        List<Map<String, Object>> ranking = tools.districtRanking(10);
        List<Map<String, Object>> buffer = tools.bufferStats(lon, lat, radius);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("center", place);
        summary.put("radiusMeters", radius);
        summary.put("districtRanking", ranking);
        summary.put("bufferStats", buffer);

        String answer;
        if (llmAgentClient.isConfigured()) {
            try {
                String prompt = "分析中心：" + place + "，半径：" + radius + " 米。\n"
                        + "真实统计数据（JSON）：\n"
                        + mapper.writeValueAsString(summary)
                        + "\n请生成报告。";
                answer = llmAgentClient.complete(SYSTEM_PROMPT, prompt);
            } catch (Exception e) {
                answer = template(place, radius, ranking, buffer);
            }
        } else {
            answer = template(place, radius, ranking, buffer);
        }
        if (answer == null || answer.isBlank()) {
            answer = template(place, radius, ranking, buffer);
        }

        List<Map<String, Object>> cards = new ArrayList<>();
        cards.add(card("行政区 POI 排名 Top 5", ranking, 5));
        cards.add(card("周边设施结构", buffer, 8));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intent", "report");
        out.put("answer", answer);
        out.put("data", List.of());
        out.put("center", center(lon, lat));
        out.put("radius", radius);
        out.put("place", place);
        out.put("cards", cards);
        out.put("steps", List.of(Map.of(
                "tool", "report",
                "input", place + " / " + radius + "m",
                "output", "district ranking + category stats",
                "ms", 0)));
        out.put("engine", "report");
        return out;
    }

    private String template(String place, int radius,
                            List<Map<String, Object>> ranking,
                            List<Map<String, Object>> buffer) {
        StringBuilder sb = new StringBuilder("# 城市空间诊断报告\n\n");
        sb.append("## 1. 数据概览\n\n");
        sb.append("分析中心：**").append(place).append("**，分析半径：**")
                .append(radius).append(" 米**。\n\n");
        sb.append("## 2. 行政区空间结构\n\n");
        for (int i = 0; i < Math.min(ranking.size(), 5); i++) {
            Map<String, Object> row = ranking.get(i);
            sb.append("- ").append(row.get("name")).append("：")
                    .append(row.get("poi_cnt")).append(" 个 POI\n");
        }
        sb.append("\n## 3. 周边设施结构\n\n");
        for (Map<String, Object> row : buffer) {
            sb.append("- ").append(row.get("category")).append("：")
                    .append(row.get("cnt")).append(" 个\n");
        }
        sb.append("\n## 4. 趋势与建议\n\n");
        sb.append("- 优先关注 POI 密度最高的行政区，结合人口与交通数据做进一步评估。\n");
        sb.append("- 周边设施结构可作为商业选址、公共服务补点与可达性分析的输入。\n");
        sb.append("- 建议在目标区域补充时间维度数据，观察 POI 增长与迁移趋势。\n");
        sb.append("\n## 5. 数据说明\n\n");
        sb.append("数据来自本地 PostGIS 的真实 OSM POI；坐标以 WGS84 存储，")
                .append("地图展示为高德 GCJ-02，已做纠偏。\n");
        return sb.toString();
    }

    private Map<String, Object> card(String title,
                                     List<Map<String, Object>> rows, int limit) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < Math.min(rows.size(), limit); i++) {
            Map<String, Object> row = rows.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", row.get("name") != null
                    ? row.get("name") : row.get("category"));
            item.put("value", row.get("poi_cnt") != null
                    ? row.get("poi_cnt") : row.get("cnt"));
            items.add(item);
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("title", title);
        card.put("type", "stats");
        card.put("rows", items);
        return card;
    }

    private Map<String, Object> center(double lon, double lat) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("lon", lon);
        c.put("lat", lat);
        return c;
    }
}
