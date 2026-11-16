package com.example.poi.agent.orchestrator;

import com.example.poi.agent.compose.ResponseComposer;
import com.example.poi.agent.intent.IntentCategory;
import com.example.poi.agent.intent.IntentRecognizer;
import com.example.poi.agent.intent.IntentResult;
import com.example.poi.agent.memory.ConversationStore;
import com.example.poi.agent.monitor.AgentMonitor;
import com.example.poi.agent.role.BaseAgent;
import com.example.poi.agent.trace.TraceStore;
import com.example.poi.agent.verify.AnswerVerifier;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AgentOrchestrator {

    private final IntentRecognizer intentRecognizer;
    private final AgentProfiles agentProfiles;
    private final ConversationStore conversationStore;
    private final AnswerVerifier answerVerifier;
    private final ResponseComposer responseComposer;
    private final TraceStore traceStore;
    private final AgentMonitor agentMonitor;
    private final Map<AgentType, BaseAgent> agents;

    public AgentOrchestrator(IntentRecognizer intentRecognizer,
                             AgentProfiles agentProfiles,
                             ConversationStore conversationStore,
                             AnswerVerifier answerVerifier,
                             ResponseComposer responseComposer,
                             TraceStore traceStore,
                             AgentMonitor agentMonitor,
                             List<BaseAgent> agentList) {
        this.intentRecognizer = intentRecognizer;
        this.agentProfiles = agentProfiles;
        this.conversationStore = conversationStore;
        this.answerVerifier = answerVerifier;
        this.responseComposer = responseComposer;
        this.traceStore = traceStore;
        this.agentMonitor = agentMonitor;
        Map<AgentType, BaseAgent> map = new EnumMap<>(AgentType.class);
        for (BaseAgent agent : agentList) {
            map.put(agent.type(), agent);
        }
        for (AgentType type : AgentType.values()) {
            if (!map.containsKey(type)) {
                throw new IllegalStateException("missing agent implementation: " + type);
            }
        }
        this.agents = map;
    }

    public AgentResponse run(String question, String sessionId) {
        String sid = sessionId == null || sessionId.isBlank()
                ? UUID.randomUUID().toString()
                : sessionId;
        String safeQuestion = question == null ? "" : question.trim();
        String rewritten = conversationStore.rewrite(safeQuestion, sid);
        IntentResult intent = intentRecognizer.recognize(rewritten);
        AgentType type = route(intent);
        AgentProfile profile = agentProfiles.get(type);
        AgentRequest request = new AgentRequest(
                UUID.randomUUID().toString(),
                rewritten,
                "anonymous",
                sid,
                intent,
                conversationStore.history(sid),
                conversationStore.context(sid));

        AgentResponse response = agents.get(type).run(request, profile, intent);
        response = answerVerifier.verify(response, intent);
        response = responseComposer.compose(response);

        conversationStore.add(sid, "user", safeQuestion);
        conversationStore.add(sid, "assistant", response.getAnswer());
        updateContext(sid, safeQuestion, response);
        traceStore.record(response.getRequestId(), response.getSteps());
        agentMonitor.record(response);
        return response;
    }

    public AgentResponse report(String sessionId) {
        return run("生成空间诊断报告", sessionId);
    }

    public void clear(String sessionId) {
        conversationStore.clear(sessionId);
    }

    public List<Map<String, String>> history(String sessionId) {
        return conversationStore.history(sessionId);
    }

    public Map<String, Object> context(String sessionId) {
        return conversationStore.context(sessionId);
    }

    private AgentType route(IntentResult intent) {
        return switch (intent.category()) {
            case QUERY -> AgentType.SPATIAL_QUERY;
            case ROUTE -> AgentType.ROUTE_PLANNING;
            case REPORT -> AgentType.REPORT;
            case GENERAL -> AgentType.GENERAL;
        };
    }

    private void updateContext(String sessionId, String question, AgentResponse response) {
        Map<String, Object> context = new LinkedHashMap<>(
                conversationStore.context(sessionId));
        Map<String, Object> center = response.getCenter();
        if (center != null) {
            Object lon = center.get("lon");
            Object lat = center.get("lat");
            if (lon instanceof Number number) {
                context.put("lon", number.doubleValue());
            }
            if (lat instanceof Number number) {
                context.put("lat", number.doubleValue());
            }
        }
        if (response.getRadius() != null) {
            context.put("radius", response.getRadius());
        }
        String place = response.getPlace();
        if (place == null) {
            place = extractPlace(question);
        }
        if (place != null) {
            context.put("place", place);
        }
        if (!response.getData().isEmpty()) {
            Map<String, Object> first = response.getData().get(0);
            if (first.get("category") != null) {
                context.put("category", String.valueOf(first.get("category")));
            }
        }
        conversationStore.updateContext(sessionId, context);
    }

    private String extractPlace(String question) {
        if (question == null) {
            return null;
        }
        Matcher matcher = Pattern
                .compile("([\\u4e00-\\u9fa5A-Za-z0-9]{2,20})(?:附近|周围|周边)")
                .matcher(question);
        return matcher.find() ? matcher.group(1) : null;
    }
}
