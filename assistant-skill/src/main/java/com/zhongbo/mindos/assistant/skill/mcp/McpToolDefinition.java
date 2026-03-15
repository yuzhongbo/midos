package com.zhongbo.mindos.assistant.skill.mcp;

public record McpToolDefinition(
        String serverAlias,
        String serverUrl,
        String name,
        String description
) {
    public String skillName() {
        return "mcp." + serverAlias + "." + name;
    }
}

