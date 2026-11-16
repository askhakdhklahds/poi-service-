package com.example.poi.agent;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class ConversationService {

    private static final int MAX_MESSAGES = 12;

    private final Map<String, Deque<Map<String, String>>> histories =
            new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> contexts =
            new ConcurrentHashMap<>();

    public List<Map<String, String>> history(String sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        Deque<Map<String, String>> deque = histories.get(sessionId);
        return deque == null ? List.of() : new ArrayList<>(deque);
    }

    public void add(String sessionId, String role, String content) {
        if (sessionId == null || content == null) {
            return;
        }
        Deque<Map<String, String>> deque =
                histories.computeIfAbsent(sessionId, k -> new ConcurrentLinkedDeque<>());
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        deque.addLast(message);
        while (deque.size() > MAX_MESSAGES) {
            deque.removeFirst();
        }
    }

    public Map<String, Object> context(String sessionId) {
        if (sessionId == null) {
            return Map.of();
        }
        return contexts.getOrDefault(sessionId, Map.of());
    }

    public void updateContext(String sessionId, Map<String, Object> context) {
        if (sessionId == null) {
            return;
        }
        contexts.put(sessionId, new LinkedHashMap<>(context));
    }

    public void clear(String sessionId) {
        if (sessionId == null) {
            return;
        }
        histories.remove(sessionId);
        contexts.remove(sessionId);
    }
}
