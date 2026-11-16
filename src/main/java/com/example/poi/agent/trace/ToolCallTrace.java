package com.example.poi.agent.trace;

public record ToolCallTrace(
        String tool,
        String input,
        String output,
        long ms,
        boolean success
) {
}
