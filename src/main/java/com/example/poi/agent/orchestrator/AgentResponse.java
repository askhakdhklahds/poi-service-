package com.example.poi.agent.orchestrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AgentResponse {

    private String requestId;
    private String sessionId;
    private String intent;
    private String agentType;
    private String answer;
    private boolean success = true;
    private List<String> toolsUsed = new ArrayList<>();
    private List<Map<String, Object>> data = new ArrayList<>();
    private Map<String, Object> center;
    private Integer radius;
    private List<Map<String, Object>> steps = new ArrayList<>();
    private List<Map<String, Object>> cards = new ArrayList<>();
    private List<List<Double>> route;
    private Object routeGeoJson;
    private Long distanceMeters;
    private String place;
    private Boolean placeFound;
    private long latencyMs;
    private String engine;
    private String routePlanUrl;

    public static Builder builder() {
        return new Builder();
    }

    @SuppressWarnings("unchecked")
    public static AgentResponse fromLegacy(Map<String, Object> legacy) {
        if (legacy == null) {
            return builder().success(false).answer("没有返回结果").build();
        }
        AgentResponse.Builder b = builder()
                .intent(string(legacy.get("intent")))
                .answer(string(legacy.get("answer")))
                .engine(string(legacy.get("engine")))
                .place(string(legacy.get("place")))
                .routePlanUrl(string(legacy.get("routePlanUrl")))
                .routeGeoJson(legacy.get("routeGeoJson"));
        if (legacy.get("data") instanceof List<?> list) {
            b.data((List<Map<String, Object>>) list);
        }
        if (legacy.get("center") instanceof Map<?, ?> map) {
            b.center((Map<String, Object>) map);
        }
        if (legacy.get("radius") instanceof Number n) {
            b.radius(n.intValue());
        }
        if (legacy.get("steps") instanceof List<?> list) {
            b.steps((List<Map<String, Object>>) list);
        }
        if (legacy.get("cards") instanceof List<?> list) {
            b.cards((List<Map<String, Object>>) list);
        }
        if (legacy.get("route") instanceof List<?> list) {
            b.route((List<List<Double>>) list);
        }
        if (legacy.get("distanceMeters") instanceof Number n) {
            b.distanceMeters(n.longValue());
        }
        if (legacy.get("placeFound") instanceof Boolean value) {
            b.placeFound(value);
        }
        return b.build();
    }

    public Builder toBuilder() {
        return new Builder()
                .requestId(requestId)
                .sessionId(sessionId)
                .intent(intent)
                .agentType(agentType)
                .answer(answer)
                .success(success)
                .toolsUsed(toolsUsed)
                .data(data)
                .center(center)
                .radius(radius)
                .steps(steps)
                .cards(cards)
                .route(route)
                .routeGeoJson(routeGeoJson)
                .distanceMeters(distanceMeters)
                .place(place)
                .placeFound(placeFound)
                .latencyMs(latencyMs)
                .engine(engine)
                .routePlanUrl(routePlanUrl);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public String getRequestId() {
        return requestId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getIntent() {
        return intent;
    }

    public String getAgentType() {
        return agentType;
    }

    public String getAnswer() {
        return answer;
    }

    public boolean isSuccess() {
        return success;
    }

    public List<String> getToolsUsed() {
        return toolsUsed;
    }

    public List<Map<String, Object>> getData() {
        return data;
    }

    public Map<String, Object> getCenter() {
        return center;
    }

    public Integer getRadius() {
        return radius;
    }

    public List<Map<String, Object>> getSteps() {
        return steps;
    }

    public List<Map<String, Object>> getCards() {
        return cards;
    }

    public List<List<Double>> getRoute() {
        return route;
    }

    public Object getRouteGeoJson() {
        return routeGeoJson;
    }

    public Long getDistanceMeters() {
        return distanceMeters;
    }

    public String getPlace() {
        return place;
    }

    public Boolean getPlaceFound() {
        return placeFound;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public String getEngine() {
        return engine;
    }

    public String getRoutePlanUrl() {
        return routePlanUrl;
    }

    public static class Builder {
        private final AgentResponse response = new AgentResponse();

        public Builder requestId(String value) {
            response.requestId = value;
            return this;
        }

        public Builder sessionId(String value) {
            response.sessionId = value;
            return this;
        }

        public Builder intent(String value) {
            response.intent = value;
            return this;
        }

        public Builder agentType(String value) {
            response.agentType = value;
            return this;
        }

        public Builder answer(String value) {
            response.answer = value;
            return this;
        }

        public Builder success(boolean value) {
            response.success = value;
            return this;
        }

        public Builder toolsUsed(List<String> value) {
            response.toolsUsed = value == null ? new ArrayList<>() : new ArrayList<>(value);
            return this;
        }

        public Builder data(List<Map<String, Object>> value) {
            response.data = value == null ? new ArrayList<>() : value;
            return this;
        }

        public Builder center(Map<String, Object> value) {
            response.center = value;
            return this;
        }

        public Builder radius(Integer value) {
            response.radius = value;
            return this;
        }

        public Builder steps(List<Map<String, Object>> value) {
            response.steps = value == null ? new ArrayList<>() : value;
            return this;
        }

        public Builder cards(List<Map<String, Object>> value) {
            response.cards = value == null ? new ArrayList<>() : value;
            return this;
        }

        public Builder route(List<List<Double>> value) {
            response.route = value;
            return this;
        }

        public Builder routeGeoJson(Object value) {
            response.routeGeoJson = value;
            return this;
        }

        public Builder distanceMeters(Long value) {
            response.distanceMeters = value;
            return this;
        }

        public Builder place(String value) {
            response.place = value;
            return this;
        }

        public Builder placeFound(Boolean value) {
            response.placeFound = value;
            return this;
        }

        public Builder latencyMs(long value) {
            response.latencyMs = value;
            return this;
        }

        public Builder engine(String value) {
            response.engine = value;
            return this;
        }

        public Builder routePlanUrl(String value) {
            response.routePlanUrl = value;
            return this;
        }

        public AgentResponse build() {
            return response;
        }
    }
}
