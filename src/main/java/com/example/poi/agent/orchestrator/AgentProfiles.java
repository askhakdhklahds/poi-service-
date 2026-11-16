package com.example.poi.agent.orchestrator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentProfiles {

    private final Map<AgentType, AgentProfile> profiles;

    public AgentProfiles(ObjectMapper mapper) throws IOException {
        try (InputStream in = new ClassPathResource("agents/profiles.json").getInputStream()) {
            List<AgentProfile> list = mapper.readValue(
                    in, new TypeReference<List<AgentProfile>>() { });
            Map<AgentType, AgentProfile> map = new EnumMap<>(AgentType.class);
            for (AgentProfile profile : list) {
                map.put(profile.agentType(), profile);
            }
            for (AgentType type : AgentType.values()) {
                if (!map.containsKey(type)) {
                    throw new IllegalStateException("missing agent profile: " + type);
                }
            }
            this.profiles = Collections.unmodifiableMap(map);
        }
    }

    public AgentProfile get(AgentType type) {
        return profiles.get(type);
    }

    public Collection<AgentProfile> all() {
        return profiles.values();
    }
}
