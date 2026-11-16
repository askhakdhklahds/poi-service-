package com.example.poi.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final RuleBasedAgent ruleBasedAgent;
    private final AdvancedAgent advancedAgent;
    private final LlmAgentClient llmAgentClient;
    private final ConversationService conversationService;

    @Value("${agent.llm.enabled:false}")
    private boolean llmEnabled;

    public AgentService(RuleBasedAgent ruleBasedAgent,
                        AdvancedAgent advancedAgent,
                        LlmAgentClient llmAgentClient,
                        ConversationService conversationService) {
        this.ruleBasedAgent = ruleBasedAgent;
        this.advancedAgent = advancedAgent;
        this.llmAgentClient = llmAgentClient;
        this.conversationService = conversationService;
    }

    public Map<String, Object> ask(String question) {
        return ask(question, "anonymous");
    }

    public Map<String, Object> ask(String question, String sessionId) {
        String sid = sessionId == null || sessionId.isBlank()
                ? UUID.randomUUID().toString() : sessionId;
        String rewritten = rewrite(question, sid);
        List<Map<String, String>> history = conversationService.history(sid);

        Map<String, Object> response = null;

        // 对比/路线/排名等空间计算走确定性 Agent，避免 LLM 算错。
        if (isComplexSpatial(rewritten)) {
            response = advancedAgent.tryAsk(rewritten);
        }

        // 简单自然语言问答优先走 LLM（带多轮历史）。
        if (response == null && llmEnabled && llmAgentClient.isConfigured()) {
            try {
                response = llmAgentClient.ask(rewritten, history);
            } catch (Exception e) {
                log.warn("LLM agent failed, falling back to rule agent: {}", e.getMessage());
            }
        }

        if (response == null) {
            response = advancedAgent.tryAsk(rewritten);
        }
        if (response == null) {
            response = ruleBasedAgent.ask(rewritten);
        }

        response.put("sessionId", sid);
        conversationService.add(sid, "user", question);
        conversationService.add(sid, "assistant", String.valueOf(response.get("answer")));
        updateContext(sid, question, response);
        return response;
    }

    private String rewrite(String question, String sessionId) {
        Map<String, Object> context = conversationService.context(sessionId);
        Object place = context.get("place");
        Object category = context.get("category");
        if (place == null) {
            return question;
        }
        String lastPlace = String.valueOf(place);
        if (question.contains(lastPlace)) {
            return question;
        }
        boolean followUp = question.startsWith("那") || question.startsWith("这")
                || question.contains("呢") || question.contains("还有")
                || question.contains("再") || question.contains("换成")
                || question.startsWith("附近") || question.startsWith("最近");
        if (!followUp) {
            return question;
        }
        StringBuilder rewritten = new StringBuilder()
                .append("在").append(lastPlace).append("附近 ").append(question);
        if (category != null && !containsCategory(question)) {
            rewritten.append(" ").append(category);
        }
        return rewritten.toString();
    }

    private boolean containsCategory(String question) {
        String[] words = {"餐", "医院", "诊所", "药", "学校", "大学", "交通", "地铁",
                "公交", "景点", "公园", "购物", "商场", "超市", "商业", "写字楼"};
        for (String word : words) {
            if (question.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private boolean isComplexSpatial(String question) {
        return question.contains("比较") || question.contains("对比")
                || question.contains("路线") || question.contains("规划")
                || question.contains("一日游") || question.contains("顺路")
                || question.contains("排名") || question.contains("哪个区")
                || question.contains("最多") || question.contains("哪类");
    }

    private void updateContext(String sessionId, String question,
                               Map<String, Object> response) {
        Map<String, Object> context =
                new LinkedHashMap<>(conversationService.context(sessionId));
        Object center = response.get("center");
        if (center instanceof Map<?, ?> c) {
            Object lon = c.get("lon");
            Object lat = c.get("lat");
            if (lon instanceof Number n) context.put("lon", n.doubleValue());
            if (lat instanceof Number n) context.put("lat", n.doubleValue());
        }
        Object radius = response.get("radius");
        if (radius instanceof Number n) {
            context.put("radius", n.intValue());
        }
        Object place = response.get("place");
        if (place == null) {
            place = extractPlace(question);
        }
        if (place != null) {
            context.put("place", String.valueOf(place));
        }
        Object data = response.get("data");
        if (data instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> row && row.get("category") != null) {
                context.put("category", String.valueOf(row.get("category")));
            }
        }
        conversationService.updateContext(sessionId, context);
    }

    private String extractPlace(String question) {
        if (question == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([\\u4e00-\\u9fa5A-Za-z0-9]{2,20})(?:附近|周围|周边)")
                .matcher(question);
        return matcher.find() ? matcher.group(1) : null;
    }
}
