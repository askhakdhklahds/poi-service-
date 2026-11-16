package com.example.poi.agent.role;

import com.example.poi.agent.LlmAgentClient;
import com.example.poi.agent.ReportService;
import com.example.poi.agent.RuleBasedAgent;
import com.example.poi.agent.intent.IntentResult;
import com.example.poi.agent.orchestrator.AgentRequest;
import com.example.poi.agent.orchestrator.AgentType;
import com.example.poi.agent.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ReportAgent extends BaseAgent {

    private final ReportService reportService;

    public ReportAgent(ToolRegistry tools, LlmAgentClient llmClient,
                       RuleBasedAgent ruleBasedAgent, ReportService reportService) {
        super(tools, llmClient, ruleBasedAgent);
        this.reportService = reportService;
    }

    @Override
    public AgentType type() {
        return AgentType.REPORT;
    }

    @Override
    protected Map<String, Object> executeDeterministic(AgentRequest request,
                                                       IntentResult intent) {
        return reportService.report(request.sessionId());
    }
}
