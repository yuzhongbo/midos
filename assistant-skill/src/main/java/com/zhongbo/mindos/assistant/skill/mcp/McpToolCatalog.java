package com.zhongbo.mindos.assistant.skill.mcp;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;

import java.util.List;
import java.util.Optional;

public interface McpToolCatalog {

    record ToolCandidate(String skillName, int score) {
    }

    void register(McpToolDefinition toolDefinition, McpJsonRpcClient client);

    int unregisterByPrefix(String prefix);

    boolean hasTool(String skillName);

    Optional<String> detectToolName(String input);

    List<ToolCandidate> detectCandidates(String input, int limit);

    Optional<SkillResult> executeTool(String skillName, SkillContext context);

    List<String> listToolSummaries();
}
