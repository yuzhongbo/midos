package com.zhongbo.mindos.assistant.skill.search;

import com.zhongbo.mindos.assistant.skill.mcp.ToolAdapter;

import java.util.Map;
import java.util.Optional;

public interface SearchProvider {

    boolean supports(SearchSourceConfig source);

    Optional<ToolAdapter> adapt(SearchSourceConfig source, Map<String, String> headers);
}

