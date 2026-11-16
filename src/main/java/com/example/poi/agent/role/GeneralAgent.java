package com.example.poi.agent.role;

import com.example.poi.agent.LlmAgentClient;
import com.example.poi.agent.RuleBasedAgent;
import com.example.poi.agent.orchestrator.AgentType;
import com.example.poi.agent.tool.ToolRegistry;
import org.springframework.stereotype.Component;

@Component
public class GeneralAgent extends BaseAgent {

    public GeneralAgent(ToolRegistry tools, LlmAgentClient llmClient,
                        RuleBasedAgent ruleBasedAgent) {
        super(tools, llmClient, ruleBasedAgent);
    }

    @Override
    public AgentType type() {
        return AgentType.GENERAL;
    }
}
