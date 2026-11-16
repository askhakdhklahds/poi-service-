package com.example.poi.agent.tool;

import com.example.poi.agent.orchestrator.AgentType;

import java.util.Set;

public record ToolDefinition(
        String name,
        String description,
        Set<String> requiredArgs,
        Set<AgentType> allowedAgents,
        long timeoutMs
) {
}
