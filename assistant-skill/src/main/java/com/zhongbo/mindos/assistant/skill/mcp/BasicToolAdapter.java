package com.zhongbo.mindos.assistant.skill.mcp;

@SuppressWarnings("unused")
record BasicToolAdapter(McpToolDefinition definition, McpJsonRpcClient client) implements ToolAdapter {
}


