package com.example.poi.agent.role;

import com.example.poi.agent.LlmAgentClient;
import com.example.poi.agent.RuleBasedAgent;
import com.example.poi.agent.intent.IntentResult;
import com.example.poi.agent.orchestrator.AgentProfile;
import com.example.poi.agent.orchestrator.AgentRequest;
import com.example.poi.agent.orchestrator.AgentResponse;
import com.example.poi.agent.orchestrator.AgentType;
import com.example.poi.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class BaseAgent {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final ToolRegistry tools;
    protected final LlmAgentClient llmClient;
    protected final RuleBasedAgent ruleBasedAgent;

    protected BaseAgent(ToolRegistry tools, LlmAgentClient llmClient,
                        RuleBasedAgent ruleBasedAgent) {
        this.tools = tools;
        this.llmClient = llmClient;
        this.ruleBasedAgent = ruleBasedAgent;
    }

    public abstract AgentType type();

    public AgentResponse run(AgentRequest request, AgentProfile profile, IntentResult intent) {
        long start = System.nanoTime();
        AgentResponse base;
        if (profile.llmEnabled() && llmClient.isConfigured()) {
            try {
                base = AgentResponse.fromLegacy(
                        llmClient.ask(request.message(), request.history()));
            } catch (Exception e) {
                log.warn("{} LLM path failed, fallback to deterministic: {}",
                        type(), e.getMessage());
                base = AgentResponse.fromLegacy(executeDeterministic(request, intent));
            }
        } else {
            base = AgentResponse.fromLegacy(executeDeterministic(request, intent));
        }
        if (base.getAnswer() == null || base.getAnswer().isBlank()) {
            base = AgentResponse.builder()
                    .answer("没有找到符合条件的 POI，请换个地点或分类再试。")
                    .success(false)
                    .build();
        }
        return decorate(base, request, intent, start);
    }

    protected Map<String, Object> executeDeterministic(AgentRequest request,
                                                       IntentResult intent) {
        return ruleBasedAgent.ask(request.message());
    }

    protected AgentResponse decorate(AgentResponse base, AgentRequest request,
                                     IntentResult intent, long startNanos) {
        List<String> toolsUsed = new ArrayList<>();
        for (Map<String, Object> step : base.getSteps()) {
            Object tool = step.get("tool");
            if (tool != null) {
                toolsUsed.add(String.valueOf(tool));
            }
        }
        return AgentResponse.builder()
                .requestId(request.requestId())
                .sessionId(request.sessionId())
                .intent(intent.category().name())
                .agentType(type().name())
                .answer(base.getAnswer())
                .success(base.isSuccess())
                .toolsUsed(toolsUsed)
                .data(base.getData())
                .center(base.getCenter())
                .radius(base.getRadius())
                .steps(base.getSteps())
                .cards(base.getCards())
                .route(base.getRoute())
                .routeGeoJson(base.getRouteGeoJson())
                .distanceMeters(base.getDistanceMeters())
                .place(base.getPlace())
                .placeFound(base.getPlaceFound())
                .latencyMs((System.nanoTime() - startNanos) / 1_000_000)
                .engine(base.getEngine() == null ? "deterministic" : base.getEngine())
                .routePlanUrl(base.getRoutePlanUrl())
                .build();
    }
}
