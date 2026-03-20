package com.zhongbo.mindos.assistant.skill.mcp;

public record McpToolDefinition(
        String serverAlias,
        String serverUrl,
        String name,
        String description,
        java.util.Map<String, String> headers
) {
    public McpToolDefinition(String serverAlias, String serverUrl, String name, String description) {
        this(serverAlias, serverUrl, name, description, java.util.Map.of());
    }

    public McpToolDefinition {
        headers = headers == null ? java.util.Map.of() : java.util.Map.copyOf(headers);
    }

    public String skillName() {
        return "mcp." + serverAlias + "." + name;
    }
}

