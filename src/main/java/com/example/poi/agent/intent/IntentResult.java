package com.example.poi.agent.intent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record IntentResult(
        IntentCategory category,
        double confidence,
        Map<String, Object> entities
) {

    public IntentResult {
        entities = entities == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(entities));
    }
}
