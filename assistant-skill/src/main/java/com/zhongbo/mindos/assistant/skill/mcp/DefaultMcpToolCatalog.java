package com.zhongbo.mindos.assistant.skill.mcp;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class DefaultMcpToolCatalog implements McpToolCatalog {

    private final Map<String, RegisteredTool> tools = new LinkedHashMap<>();

    public DefaultMcpToolCatalog() {
    }

    @Override
    public synchronized void register(McpToolDefinition toolDefinition, McpJsonRpcClient client) {
        if (toolDefinition == null || client == null) {
            return;
        }
        tools.put(toolDefinition.skillName(), new RegisteredTool(toolDefinition, client));
    }

    @Override
    public synchronized int unregisterByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return 0;
        }
        int removed = 0;
        for (String key : List.copyOf(tools.keySet())) {
            if (key.startsWith(prefix) && tools.remove(key) != null) {
                removed++;
            }
        }
        return removed;
    }

    @Override
    public synchronized boolean hasTool(String skillName) {
        return tools.containsKey(skillName);
    }

    @Override
    public synchronized Optional<RegisteredTool> getTool(String skillName) {
        return Optional.ofNullable(tools.get(skillName));
    }

    @Override
    public synchronized List<RegisteredTool> listTools() {
        return List.copyOf(tools.values());
    }
}
