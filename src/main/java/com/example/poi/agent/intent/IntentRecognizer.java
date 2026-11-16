package com.example.poi.agent.intent;

import com.example.poi.agent.LlmAgentClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class IntentRecognizer {

    private static final Logger log = LoggerFactory.getLogger(IntentRecognizer.class);

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
        for (String keyword : keywords) {
            CATEGORY_KEYWORDS.put(keyword, category);
        }
    }

    private final LlmAgentClient llmClient;

    @Value("${agent.intent.llm-enabled:false}")
    private boolean llmEnabled;

    public IntentRecognizer(LlmAgentClient llmClient) {
        this.llmClient = llmClient;
    }

    public IntentResult recognize(String question) {
        IntentResult ruleResult = recognizeByKeywords(question);
        if (!llmEnabled || !llmClient.isConfigured()) {
            return ruleResult;
        }
        try {
            String prompt = """
                    你是空间问句意图分类器。只输出一个英文标签，不要解释：
                    QUERY, ROUTE, REPORT, GENERAL
                    规则：
                    - 附近/最近/统计/排名/对比/某区有多少 -> QUERY
                    - 从 A 到 B 的路线/导航/怎么走/规划 -> ROUTE
                    - 报告/诊断 -> REPORT
                    - 问候/其他 -> GENERAL
                    """;
            String answer = llmClient.complete(prompt, question);
            IntentCategory category = parseCategory(answer);
            if (category != null) {
                return new IntentResult(category,
                        Math.max(0.8, ruleResult.confidence()),
                        ruleResult.entities());
            }
        } catch (Exception e) {
            log.warn("LLM intent recognition failed, fallback to keywords: {}",
                    e.getMessage());
        }
        return ruleResult;
    }

    private IntentCategory parseCategory(String answer) {
        if (answer == null) {
            return null;
        }
        String upper = answer.trim().toUpperCase();
        for (IntentCategory category : IntentCategory.values()) {
            if (upper.contains(category.name())) {
                return category;
            }
        }
        return null;
    }

    private IntentResult recognizeByKeywords(String question) {
        String q = question == null ? "" : question.trim();
        Map<String, Object> entities = new LinkedHashMap<>();

        String category = detectCategory(q);
        if (category != null) {
            entities.put("category", category);
        }
        int radius = detectRadius(q, 1000);
        entities.put("radius", radius);
        int limit = detectLimit(q, 10);
        entities.put("limit", limit);

        String place = extractPlace(q);
        if (place != null) {
            entities.put("place", place);
        }
        String[] fromTo = extractFromTo(q);
        if (fromTo != null) {
            entities.put("from", fromTo[0]);
            entities.put("to", fromTo[1]);
        }

        boolean nearby = containsAny(q, "附近", "周围", "周边", "离我");
        boolean stats = containsAny(q, "统计", "分布", "多少", "数量", "各类", "几个", "几家");
        boolean district = q.contains("行政区") || q.contains("哪个区")
                || q.matches(".*[\\u4e00-\\u9fa5]{2,10}区.*");
        boolean routeWords = containsAny(q, "路线", "怎么走", "导航", "行程", "顺路")
                || fromTo != null;
        boolean report = containsAny(q, "报告", "诊断", "评估报告");
        boolean queryWords = nearby || stats || district
                || containsAny(q, "最近", "排名", "排行", "哪个区", "最多", "哪类",
                "比较", "对比", "相比")
                || category != null || place != null
                || q.contains("POI") || q.contains("poi");

        IntentCategory intent;
        String scene = null;
        if (report) {
            intent = IntentCategory.REPORT;
        } else if (routeWords) {
            intent = IntentCategory.ROUTE;
        } else if (queryWords) {
            intent = IntentCategory.QUERY;
            if (containsAny(q, "比较", "对比", "相比")) {
                scene = "COMPARE";
            } else if (containsAny(q, "排名", "排行", "哪个区", "最多", "哪类")) {
                scene = "RANKING";
            } else if (nearby && stats) {
                scene = "BUFFER_STATS";
            } else if (district && stats) {
                scene = "DISTRICT_STATS";
            } else if (q.contains("最近")) {
                scene = "NEAREST";
            } else {
                scene = "NEARBY";
            }
            entities.put("scene", scene);
        } else {
            intent = IntentCategory.GENERAL;
        }

        double confidence = switch (intent) {
            case REPORT, ROUTE -> 0.95;
            case QUERY -> 0.9;
            case GENERAL -> 0.35;
        };
        return new IntentResult(intent, confidence, entities);
    }

    private String detectCategory(String q) {
        for (Map.Entry<String, String> entry : CATEGORY_KEYWORDS.entrySet()) {
            if (q.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private int detectRadius(String q, int fallback) {
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(公里|千米|km|KM|米|m)")
                .matcher(q);
        if (matcher.find()) {
            double value = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2);
            boolean km = unit.contains("公") || unit.contains("千")
                    || unit.equalsIgnoreCase("km");
            return (int) Math.round(km ? value * 1000 : value);
        }
        return fallback;
    }

    private int detectLimit(String q, int fallback) {
        Matcher matcher = Pattern.compile("(?:最近|前|top)\\s*(\\d+)\\s*个",
                Pattern.CASE_INSENSITIVE).matcher(q);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    private String extractPlace(String q) {
        String[] patterns = {
                "从(.+?)出发",
                "离(.+?)最近",
                "在(.+?)(?:附近|周围|周边)",
                "(.+?)(?:附近|周围|周边)",
                "([\\u4e00-\\u9fa5]{2,10}区)"
        };
        for (String pattern : patterns) {
            Matcher matcher = Pattern.compile(pattern).matcher(q);
            if (matcher.find()) {
                String place = cleanPlace(matcher.group(1));
                if (!place.isEmpty()) {
                    return place;
                }
            }
        }
        return null;
    }

    private String[] extractFromTo(String q) {
        Matcher matcher = Pattern.compile(
                        "(?:从)?(.+?)(?:到|→|->)(.+?)(?:怎么走|路线|规划|导航|的|$|[，。,.])")
                .matcher(q);
        if (matcher.find()) {
            String from = cleanPlace(matcher.group(1));
            String to = cleanPlace(matcher.group(2));
            if (!from.isEmpty() && !to.isEmpty()) {
                return new String[]{from, to};
            }
        }
        return null;
    }

    private String cleanPlace(String raw) {
        return raw
                .replaceAll("^(帮我|帮忙|查一下|看看|看一下|请问|我想知道|找一下|在|从|的)+", "")
                .replaceAll("[\\d.]+\\s*(公里|千米|km|KM|米|m)", "")
                .replaceAll("(的|附近|周围|周边|内|里|出发|最近|怎么走|路线|规划|导航|有哪些|有什么|多少个|几家)+$", "")
                .trim();
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
