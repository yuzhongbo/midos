package com.zhongbo.mindos.assistant.skill.mcp;

public interface ToolAdapter {

    McpToolDefinition definition();

    McpJsonRpcClient client();
}


