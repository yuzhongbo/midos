package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record Department(String departmentId,
                         String name,
                         DepartmentType type,
                         List<OrganizationAgent> agents,
                         Map<String, Object> metadata) {

    public Department {
        departmentId = departmentId == null ? "" : departmentId.trim();
        name = name == null ? "" : name.trim();
        type = type == null ? DepartmentType.EXECUTION : type;
        agents = agents == null ? List.of() : List.copyOf(agents);
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public List<OrganizationAgent> activeAgents() {
        return agents.stream()
                .filter(agent -> agent != null && agent.active())
                .toList();
    }

    public List<String> activeAgentIds() {
        return activeAgents().stream()
                .map(OrganizationAgent::agentId)
                .filter(agentId -> agentId != null && !agentId.isBlank())
                .toList();
    }

    public int headcount() {
        return agents.size();
    }

    public int activeHeadcount() {
        return activeAgents().size();
    }

    public Department withAgents(List<OrganizationAgent> nextAgents) {
        return new Department(departmentId, name, type, nextAgents, metadata);
    }

    public Department withMetadata(Map<String, Object> additions) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(metadata);
        if (additions != null) {
            merged.putAll(additions);
        }
        return new Department(departmentId, name, type, agents, merged);
    }

    public Department withMetadata(String key, Object value) {
        if (key == null || key.isBlank()) {
            return this;
        }
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(metadata);
        merged.put(key, value);
        return new Department(departmentId, name, type, agents, merged);
    }

    public Department setAgentActive(String agentId, boolean active) {
        if (agentId == null || agentId.isBlank()) {
            return this;
        }
        List<OrganizationAgent> nextAgents = new ArrayList<>();
        for (OrganizationAgent agent : agents) {
            if (agent == null) {
                continue;
            }
            nextAgents.add(agentId.equalsIgnoreCase(agent.agentId()) ? agent.withActive(active) : agent);
        }
        return new Department(departmentId, name, type, nextAgents, metadata);
    }

    public Department appendAgent(OrganizationAgent agent) {
        if (agent == null || agent.agentId().isBlank()) {
            return this;
        }
        List<OrganizationAgent> nextAgents = new ArrayList<>(agents);
        nextAgents.add(agent);
        return new Department(departmentId, name, type, nextAgents, metadata);
    }

    public String stringMetadata(String key, String fallback) {
        Object value = metadata.get(key);
        if (value == null) {
            return fallback;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? fallback : normalized.toLowerCase(Locale.ROOT);
    }

    public int intMetadata(String key, int fallback) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignore) {
            return fallback;
        }
    }

    public double doubleMetadata(String key, double fallback) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ignore) {
            return fallback;
        }
    }
}
