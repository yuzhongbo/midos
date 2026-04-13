package com.zhongbo.mindos.assistant.skill.mcp;

import java.util.List;
import java.util.Optional;

public interface McpToolCatalog {

    record RegisteredTool(McpToolDefinition definition, McpJsonRpcClient client) {
    }

    void register(McpToolDefinition toolDefinition, McpJsonRpcClient client);

    int unregisterByPrefix(String prefix);

    boolean hasTool(String skillName);

    Optional<RegisteredTool> getTool(String skillName);

    List<RegisteredTool> listTools();
}
