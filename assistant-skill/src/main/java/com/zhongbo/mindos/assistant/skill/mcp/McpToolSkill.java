package com.zhongbo.mindos.assistant.skill.mcp;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

public class McpToolSkill implements Skill {

    private final McpToolDefinition toolDefinition;
    private final McpJsonRpcClient mcpClient;

    public McpToolSkill(McpToolDefinition toolDefinition, McpJsonRpcClient mcpClient) {
        this.toolDefinition = toolDefinition;
        this.mcpClient = mcpClient;
    }

    @Override
    public String name() {
        return toolDefinition.skillName();
    }

    @Override
    public String description() {
        return toolDefinition.description();
    }

    @Override
    public boolean supports(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }

        String normalizedInput = input.toLowerCase(Locale.ROOT);
        if (normalizedInput.startsWith(name().toLowerCase(Locale.ROOT) + " ")
                || normalizedInput.equals(name().toLowerCase(Locale.ROOT))) {
            return true;
        }

        List<String> phrases = new ArrayList<>();
        phrases.add(toolDefinition.serverAlias());
        phrases.add(splitCamelCase(toolDefinition.name()));
        phrases.add((toolDefinition.serverAlias() + " " + splitCamelCase(toolDefinition.name())).trim());
        if (toolDefinition.description() != null && !toolDefinition.description().isBlank()) {
            phrases.add(toolDefinition.description());
        }

        int matched = 0;
        for (String phrase : phrases) {
            String normalizedPhrase = normalizePhrase(phrase);
            if (normalizedPhrase.isBlank()) {
                continue;
            }
            if (normalizedInput.contains(normalizedPhrase)) {
                return true;
            }
            if (containsAllSignificantWords(normalizedInput, normalizedPhrase)) {
                matched++;
            }
        }
        return matched >= 1;
    }

    @Override
    public SkillResult run(SkillContext context) {
        try {
            Map<String, Object> arguments = new LinkedHashMap<>(context.attributes());
            if (!arguments.containsKey("input") && context.input() != null && !context.input().isBlank()) {
                arguments.put("input", context.input());
            }
            String output = mcpClient.callTool(toolDefinition.serverUrl(), toolDefinition.name(), arguments);
            return SkillResult.success(name(), output);
        } catch (RuntimeException ex) {
            return SkillResult.failure(name(), "MCP tool call failed: " + ex.getMessage());
        }
    }

    private String splitCamelCase(String value) {
        return value == null ? "" : value.replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    private String normalizePhrase(String value) {
        return value == null ? "" : value
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private boolean containsAllSignificantWords(String input, String phrase) {
        String[] words = phrase.split(" ");
        int significantWords = 0;
        for (String word : words) {
            if (word.length() < 3) {
                continue;
            }
            significantWords++;
            if (!input.contains(word)) {
                return false;
            }
        }
        return significantWords > 0;
    }
}

