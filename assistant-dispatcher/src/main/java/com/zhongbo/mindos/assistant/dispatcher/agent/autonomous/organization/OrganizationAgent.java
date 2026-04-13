package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization;

import java.util.LinkedHashMap;
import java.util.Map;

public record OrganizationAgent(String agentId,
                                AgentRole role,
                                boolean active,
                                Map<String, Object> metadata) {

    public OrganizationAgent {
        agentId = agentId == null ? "" : agentId.trim();
        role = role == null ? AgentRole.EXECUTOR : role;
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public OrganizationAgent withActive(boolean nextActive) {
        return new OrganizationAgent(agentId, role, nextActive, metadata);
    }

    public OrganizationAgent withMetadata(Map<String, Object> additions) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(metadata);
        if (additions != null) {
            merged.putAll(additions);
        }
        return new OrganizationAgent(agentId, role, active, merged);
    }
}
