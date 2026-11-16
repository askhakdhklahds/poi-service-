package com.example.poi.agent.monitor;

import com.example.poi.agent.orchestrator.AgentResponse;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AgentMonitor {

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong successes = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();
    private final Map<String, AtomicLong> byAgent = new ConcurrentHashMap<>();

    public void record(AgentResponse response) {
        requests.incrementAndGet();
        if (response.isSuccess()) {
            successes.incrementAndGet();
        } else {
            failures.incrementAndGet();
        }
        totalLatencyMs.addAndGet(response.getLatencyMs());
        String type = response.getAgentType() == null ? "UNKNOWN" : response.getAgentType();
        byAgent.computeIfAbsent(type, key -> new AtomicLong()).incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        long total = requests.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requests", total);
        out.put("successes", successes.get());
        out.put("failures", failures.get());
        out.put("successRate", total == 0 ? 0.0 : (double) successes.get() / total);
        out.put("avgLatencyMs", total == 0 ? 0.0 : (double) totalLatencyMs.get() / total);
        Map<String, Long> agents = new LinkedHashMap<>();
        byAgent.forEach((key, value) -> agents.put(key, value.get()));
        out.put("byAgent", agents);
        return out;
    }
}
