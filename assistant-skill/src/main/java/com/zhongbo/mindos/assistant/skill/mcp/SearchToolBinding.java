package com.zhongbo.mindos.assistant.skill.mcp;

public record SearchToolBinding(McpToolDefinition definition,
                                McpJsonRpcClient client,
                                SearchRequestStyle requestStyle,
                                String responseLabel,
                                String newsUrl,
                                String apiKeyQueryParameterName) implements ToolAdapter {

    public SearchToolBinding {
        requestStyle = requestStyle == null ? SearchRequestStyle.POST_JSON : requestStyle;
        responseLabel = responseLabel == null || responseLabel.isBlank() ? "Search" : responseLabel.trim();
        newsUrl = newsUrl == null ? "" : newsUrl.trim();
        apiKeyQueryParameterName = apiKeyQueryParameterName == null ? "" : apiKeyQueryParameterName.trim();
    }
}

