package com.example.poi.agent.orchestrator;

import com.example.poi.agent.intent.IntentResult;

import java.util.List;
import java.util.Map;

public record AgentRequest(
        String requestId,
        String message,
        String userId,
        String sessionId,
        IntentResult intent,
        List<Map<String, String>> history,
        Map<String, Object> context
) {
}
