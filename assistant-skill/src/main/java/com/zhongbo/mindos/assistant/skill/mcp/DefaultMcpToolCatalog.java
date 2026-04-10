package com.zhongbo.mindos.assistant.skill.mcp;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class DefaultMcpToolCatalog implements McpToolCatalog {

    private record RegisteredTool(McpToolDefinition definition, McpJsonRpcClient client) {
    }

    private final Map<String, RegisteredTool> tools = new LinkedHashMap<>();
    private final McpToolExecutor executor;

    public DefaultMcpToolCatalog() {
        this(new McpToolExecutor());
    }

    @Autowired
    public DefaultMcpToolCatalog(McpToolExecutor executor) {
        this.executor = executor;
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
    public synchronized Optional<String> detectToolName(String input) {
        return detectCandidates(input, 1).stream().findFirst().map(ToolCandidate::skillName);
    }

    @Override
    public synchronized List<ToolCandidate> detectCandidates(String input, int limit) {
        if (input == null || input.isBlank() || limit <= 0) {
            return List.of();
        }
        List<ToolCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, RegisteredTool> entry : tools.entrySet()) {
            int score = executor.routingScore(entry.getValue().definition(), input);
            if (score > 0) {
                candidates.add(new ToolCandidate(entry.getKey(), score));
            }
        }
        candidates.sort(Comparator
                .comparingInt(ToolCandidate::score).reversed()
                .thenComparing(ToolCandidate::skillName));
        int safeLimit = Math.min(limit, candidates.size());
        return safeLimit <= 0 ? List.of() : List.copyOf(candidates.subList(0, safeLimit));
    }

    @Override
    public synchronized Optional<SkillResult> executeTool(String skillName, SkillContext context) {
        RegisteredTool tool = tools.get(skillName);
        if (tool == null) {
            return Optional.empty();
        }
        return Optional.of(executor.execute(tool.definition(), tool.client(), context));
    }

    @Override
    public synchronized List<String> listToolSummaries() {
        return tools.values().stream()
                .map(RegisteredTool::definition)
                .sorted(Comparator.comparing(McpToolDefinition::skillName))
                .map(definition -> definition.skillName() + " - " + (definition.description() == null ? "" : definition.description()))
                .toList();
    }
}
