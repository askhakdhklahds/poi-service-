package com.example.poi.agent.memory;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConversationStore {

    private final Map<String, ConversationState> states = new ConcurrentHashMap<>();

    public ConversationState state(String sessionId) {
        String key = sessionId == null || sessionId.isBlank() ? "anonymous" : sessionId;
        return states.computeIfAbsent(key, ConversationState::new);
    }

    public List<Map<String, String>> history(String sessionId) {
        return state(sessionId).history();
    }

    public void add(String sessionId, String role, String content) {
        state(sessionId).add(role, content);
    }

    public Map<String, Object> context(String sessionId) {
        return state(sessionId).context();
    }

    public void updateContext(String sessionId, Map<String, Object> context) {
        state(sessionId).updateContext(context);
    }

    public void clear(String sessionId) {
        ConversationState state = states.remove(sessionId);
        if (state != null) {
            state.clear();
        }
    }

    public String rewrite(String question, String sessionId) {
        Map<String, Object> context = context(sessionId);
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
}
