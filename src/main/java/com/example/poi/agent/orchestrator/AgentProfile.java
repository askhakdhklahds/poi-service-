package com.example.poi.agent.orchestrator;

import java.util.List;

public record AgentProfile(
        AgentType agentType,
        String role,
        String mission,
        List<String> workflow,
        List<String> inputContract,
        List<String> outputContract,
        List<String> toolScope,
        boolean llmEnabled,
        double temperature,
        int maxOutputTokens
) {

    public String contract() {
        StringBuilder sb = new StringBuilder();
        sb.append("角色：").append(role).append('\n');
        sb.append("职责：").append(mission).append('\n');
        appendList(sb, "处理流程", workflow);
        appendList(sb, "输入约束", inputContract);
        appendList(sb, "输出约束", outputContract);
        if (toolScope != null && !toolScope.isEmpty()) {
            sb.append("可用工具：").append(String.join(", ", toolScope)).append('\n');
        }
        sb.append("不要编造坐标、数量或设施名称；所有空间数据必须来自工具返回。\n");
        return sb.toString();
    }

    private static void appendList(StringBuilder sb, String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        sb.append(title).append("：\n");
        for (String item : items) {
            sb.append("- ").append(item).append('\n');
        }
    }
}
