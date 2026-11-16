package com.example.poi.controller;

import com.example.poi.agent.monitor.AgentMonitor;
import com.example.poi.agent.orchestrator.AgentOrchestrator;
import com.example.poi.agent.orchestrator.AgentResponse;
import com.example.poi.agent.trace.TraceStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentOrchestrator orchestrator;
    private final AgentMonitor agentMonitor;
    private final TraceStore traceStore;

    public AgentController(AgentOrchestrator orchestrator,
                           AgentMonitor agentMonitor,
                           TraceStore traceStore) {
        this.orchestrator = orchestrator;
        this.agentMonitor = agentMonitor;
        this.traceStore = traceStore;
    }

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody AskRequest request) {
        String question = request.question() == null ? "" : request.question().trim();
        if (question.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question required");
        }
        String sessionId = sessionId(request.sessionId());
        AgentResponse data = orchestrator.run(question, sessionId);
        return Map.of("code", 0, "message", "ok", "data", data);
    }

    @PostMapping("/report")
    public Map<String, Object> report(@RequestBody(required = false) AskRequest request) {
        String sessionId = sessionId(request == null ? null : request.sessionId());
        AgentResponse data = orchestrator.report(sessionId);
        return Map.of("code", 0, "message", "ok", "data", data);
    }

    @PostMapping("/session/{id}/clear")
    public Map<String, Object> clear(@PathVariable String id) {
        orchestrator.clear(id);
        return Map.of("code", 0, "message", "cleared");
    }

    @GetMapping("/session/{id}")
    public Map<String, Object> session(@PathVariable String id) {
        return Map.of("code", 0, "message", "ok", "data", Map.of(
                "sessionId", id,
                "history", orchestrator.history(id),
                "context", orchestrator.context(id)));
    }

    @GetMapping("/monitor")
    public Map<String, Object> monitor() {
        return Map.of("code", 0, "message", "ok", "data", agentMonitor.snapshot());
    }

    @GetMapping("/trace/{requestId}")
    public Map<String, Object> trace(@PathVariable String requestId) {
        return Map.of("code", 0, "message", "ok", "data", Map.of(
                "requestId", requestId,
                "tools", traceStore.get(requestId)));
    }

    @GetMapping("/trace")
    public Map<String, Object> recentTraces(@RequestParam(defaultValue = "20") int limit) {
        return Map.of("code", 0, "message", "ok", "data", traceStore.recent(limit));
    }

    private String sessionId(String provided) {
        return provided == null || provided.isBlank()
                ? UUID.randomUUID().toString()
                : provided;
    }

    public record AskRequest(String question, String sessionId) {
    }
}
