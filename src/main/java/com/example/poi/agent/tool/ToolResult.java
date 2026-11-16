package com.example.poi.agent.tool;

public record ToolResult(boolean success, Object data, String error, long latencyMs) {

    public static ToolResult ok(Object data, long latencyMs) {
        return new ToolResult(true, data, null, latencyMs);
    }

    public static ToolResult fail(String error, long latencyMs) {
        return new ToolResult(false, null, error, latencyMs);
    }
}
