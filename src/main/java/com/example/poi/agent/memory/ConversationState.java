package com.example.poi.agent.memory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConversationState {

    private static final int MAX_MESSAGES = 12;

    private final String sessionId;
    private final Deque<Map<String, String>> messages = new ArrayDeque<>();
    private Map<String, Object> context = new LinkedHashMap<>();

    public ConversationState(String sessionId) {
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }

    public synchronized List<Map<String, String>> history() {
        return new ArrayList<>(messages);
    }

    public synchronized void add(String role, String content) {
        if (content == null) {
            return;
        }
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        messages.addLast(message);
        while (messages.size() > MAX_MESSAGES) {
            messages.removeFirst();
        }
    }

    public synchronized Map<String, Object> context() {
        return new LinkedHashMap<>(context);
    }

    public synchronized void updateContext(Map<String, Object> next) {
        context = next == null ? new LinkedHashMap<>() : new LinkedHashMap<>(next);
    }

    public synchronized void clear() {
        messages.clear();
        context.clear();
    }
}
