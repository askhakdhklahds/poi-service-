package com.example.poi.agent.trace;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class TraceStore {

    private static final int MAX_REQUESTS = 200;

    private final Map<String, List<ToolCallTrace>> traces = new ConcurrentHashMap<>();
    private final Deque<String> order = new ConcurrentLinkedDeque<>();

    public synchronized void record(String requestId, List<Map<String, Object>> steps) {
        if (requestId == null) {
            return;
        }
        List<ToolCallTrace> list = new ArrayList<>();
        if (steps != null) {
            for (Map<String, Object> step : steps) {
                list.add(new ToolCallTrace(
                        String.valueOf(step.get("tool")),
                        String.valueOf(step.get("input")),
                        String.valueOf(step.get("output")),
                        step.get("ms") instanceof Number number ? number.longValue() : 0,
                        true));
            }
        }
        traces.put(requestId, list);
        order.addLast(requestId);
        while (order.size() > MAX_REQUESTS) {
            String oldest = order.removeFirst();
            traces.remove(oldest);
        }
    }

    public List<ToolCallTrace> get(String requestId) {
        return traces.getOrDefault(requestId, List.of());
    }

    public Map<String, List<ToolCallTrace>> recent(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        List<String> ids = new ArrayList<>(order);
        Map<String, List<ToolCallTrace>> out = new LinkedHashMap<>();
        int from = Math.max(0, ids.size() - safeLimit);
        for (int i = ids.size() - 1; i >= from; i--) {
            String id = ids.get(i);
            out.put(id, traces.getOrDefault(id, List.of()));
        }
        return out;
    }
}
