package com.zhongbo.mindos.assistant.skill.mcp;

import com.zhongbo.mindos.assistant.skill.search.SearchSourceConfig;

import java.util.Map;
import java.util.Optional;

public interface SearchToolAdapter {

    boolean supports(SearchSourceConfig source);

    Optional<com.zhongbo.mindos.assistant.skill.mcp.SearchToolBinding> adapt(SearchSourceConfig source, Map<String, String> headers);
}



