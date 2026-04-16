package com.zhongbo.mindos.assistant.skill;

import com.zhongbo.mindos.assistant.skill.mcp.McpToolCatalog;
import com.zhongbo.mindos.assistant.skill.mcp.McpToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DefaultSkillCatalog implements SkillCatalogFacade {

    private static final List<String> MCP_SEARCH_INTENT_CUES = List.of(
            "search", "find", "lookup", "query", "搜", "搜索", "查询", "查一下", "查找", "帮我查"
    );
    private static final List<String> MCP_DOC_INTENT_CUES = List.of(
            "docs", "doc", "documentation", "manual", "guide", "文档", "手册", "指南", "说明"
    );
    private static final List<String> MCP_REALTIME_INTENT_CUES = List.of(
            "news", "latest", "realtime", "real time", "current", "today", "headline",
            "新闻", "最新", "实时", "今天", "头条", "热点", "热搜", "天气", "汇率", "股价"
    );

    private final SkillRegistry skillRegistry;
    private final McpToolCatalog mcpToolCatalog;
    private final SkillRoutingProperties routingProperties;

    public DefaultSkillCatalog(SkillRegistry skillRegistry) {
        this(skillRegistry, null, new SkillRoutingProperties());
    }

    @Autowired
    public DefaultSkillCatalog(SkillRegistry skillRegistry,
                               @Nullable McpToolCatalog mcpToolCatalog,
                               @Nullable SkillRoutingProperties routingProperties) {
        this.skillRegistry = skillRegistry;
        this.mcpToolCatalog = mcpToolCatalog;
        this.routingProperties = routingProperties == null ? new SkillRoutingProperties() : routingProperties;
    }

    @Override
    public Optional<String> detectSkillName(String input) {
        return detectSkillCandidates(input, 1).stream().findFirst().map(SkillCandidate::skillName);
    }

    @Override
    public List<SkillCandidate> detectSkillCandidates(String input, int limit) {
        if (input == null || input.isBlank() || limit <= 0) {
            return List.of();
        }
        List<SkillCandidate> candidates = new ArrayList<>();
        String normalized = input.trim();
        String firstToken = normalized.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        for (Skill skill : skillRegistry.getAllSkills()) {
            int score = routingScore(skill, normalized);
            if (skill.name().equalsIgnoreCase(firstToken)) {
                score = Math.max(score, 1000);
            }
            if (score > 0) {
                candidates.add(new SkillCandidate(skill.name(), score));
            }
        }
        if (mcpToolCatalog != null) {
            for (McpToolCatalog.RegisteredTool tool : mcpToolCatalog.listTools()) {
                int score = routingScore(tool.definition(), normalized);
                if (score > 0) {
                    candidates.add(new SkillCandidate(tool.definition().skillName(), score));
                }
            }
        }
        candidates.sort(Comparator.comparingInt(SkillCandidate::score).reversed()
                .thenComparing(SkillCandidate::skillName));
        int safeLimit = Math.min(limit, candidates.size());
        return safeLimit <= 0 ? List.of() : List.copyOf(candidates.subList(0, safeLimit));
    }

    @Override
    public Optional<SkillDescriptor> describeSkill(String skillName) {
        return skillRegistry.get(skillName)
                .map(skill -> new SkillDescriptor(skill.name(), skill.description(), resolvedRoutingKeywords(skill)));
    }

    @Override
    public List<SkillDescriptor> listSkillDescriptors() {
        return skillRegistry.getAllSkills().stream()
                .sorted(Comparator.comparing(Skill::name))
                .map(skill -> new SkillDescriptor(skill.name(), skill.description(), resolvedRoutingKeywords(skill)))
                .toList();
    }

    @Override
    public String describeAvailableSkills() {
        return listAvailableSkillSummaries().stream().collect(Collectors.joining(", "));
    }

    @Override
    public List<String> listAvailableSkillSummaries() {
        List<String> summaries = new ArrayList<>(skillRegistry.getAllSkills().stream()
                .sorted(Comparator.comparing(Skill::name))
                .map(skill -> skill.name() + " - " + (skill.description() == null ? "" : skill.description()))
                .toList());
        if (mcpToolCatalog != null) {
            summaries.addAll(mcpToolCatalog.listTools().stream()
                    .map(McpToolCatalog.RegisteredTool::definition)
                    .sorted(Comparator.comparing(McpToolDefinition::skillName))
                    .map(definition -> definition.skillName() + " - " + (definition.description() == null ? "" : definition.description()))
                    .toList());
        }
        summaries.sort(String::compareTo);
        return List.copyOf(summaries);
    }

    public List<String> resolvedRoutingKeywords(String skillName) {
        return skillRegistry.get(skillName)
                .map(this::resolvedRoutingKeywords)
                .orElse(List.of());
    }

    public int routingScore(String skillName, String input) {
        return skillRegistry.get(skillName)
                .map(skill -> routingScore(skill, input))
                .orElse(Integer.MIN_VALUE);
    }

    private List<String> resolvedRoutingKeywords(Skill skill) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.add(skill.name());
        merged.add(splitSkillName(skill.name()));
        merged.addAll(descriptorFor(skill).routingKeywords());
        merged.addAll(configuredKeywords(skill.name()));
        return merged.stream()
                .map(this::normalize)
                .filter(keyword -> !keyword.isBlank())
                .toList();
    }

    private List<String> configuredKeywords(String skillName) {
        if (routingProperties.getKeywords().isEmpty()) {
            return List.of();
        }
        String raw = routingProperties.getKeywords().get(skillName);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String normalized = token == null ? "" : token.trim();
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }
        return List.copyOf(values);
    }

    private SkillDescriptor descriptorFor(Skill skill) {
        if (skill instanceof SkillDescriptorProvider provider && provider.skillDescriptor() != null) {
            return provider.skillDescriptor();
        }
        return new SkillDescriptor(skill.name(), skill.description(), List.of());
    }

    private int routingScore(Skill skill, String input) {
        String normalizedInput = normalize(input);
        if (skill == null || normalizedInput.isBlank()) {
            return Integer.MIN_VALUE;
        }
        int bestScore = Integer.MIN_VALUE;
        for (String keyword : resolvedRoutingKeywords(skill)) {
            if (keyword.isBlank()) {
                continue;
            }
            if (normalizedInput.equals(keyword)) {
                bestScore = Math.max(bestScore, 900 + keyword.length());
                continue;
            }
            if (normalizedInput.contains(keyword)) {
                bestScore = Math.max(bestScore, 600 + keyword.length());
                continue;
            }
            int overlap = countMatchedWords(normalizedInput, keyword);
            if (overlap > 0) {
                bestScore = Math.max(bestScore, 300 + overlap * 40);
            }
        }
        return bestScore;
    }

    private int routingScore(McpToolDefinition toolDefinition, String input) {
        if (toolDefinition == null || input == null || input.isBlank()) {
            return Integer.MIN_VALUE;
        }
        String normalizedInput = normalize(input);
        String normalizedSkillName = normalize(toolDefinition.skillName());
        String normalizedToolName = normalize(toolDefinition.name());
        if (normalizedInput.equals(normalizedSkillName)
                || normalizedInput.startsWith(normalizedSkillName + " ")) {
            return 1000;
        }
        if (!normalizedToolName.isBlank() && (normalizedInput.equals(normalizedToolName)
                || normalizedInput.startsWith(normalizedToolName + " "))) {
            return 950;
        }

        List<String> phrases = new ArrayList<>(routingKeywords(toolDefinition));
        phrases.add(0, toolDefinition.skillName());

        int bestScore = Integer.MIN_VALUE;
        for (String phrase : phrases) {
            String normalizedPhrase = normalize(phrase);
            if (normalizedPhrase.isBlank()) {
                continue;
            }
            if (normalizedInput.contains(normalizedPhrase)) {
                bestScore = Math.max(bestScore, 700 + normalizedPhrase.length());
                continue;
            }
            int overlap = countMatchedWords(normalizedInput, normalizedPhrase);
            if (overlap > 0) {
                bestScore = Math.max(bestScore, 300 + overlap * 40);
            }
        }

        String capabilityText = normalize(String.join(" ", phrases));
        boolean docsTool = containsAny(capabilityText, MCP_DOC_INTENT_CUES)
                && containsAny(capabilityText, MCP_SEARCH_INTENT_CUES);
        boolean realtimeSearchTool = containsAny(capabilityText, MCP_REALTIME_INTENT_CUES)
                || containsAny(capabilityText, List.of("websearch", "web search", "searchweb", "internet", "网页", "联网"));
        boolean generalSearchTool = realtimeSearchTool || containsAny(capabilityText, MCP_SEARCH_INTENT_CUES);

        if (docsTool && containsAny(normalizedInput, MCP_DOC_INTENT_CUES)) {
            bestScore = Math.max(bestScore, 520 + countCueMatches(normalizedInput, MCP_DOC_INTENT_CUES) * 20);
        }
        if (generalSearchTool && containsAny(normalizedInput, MCP_SEARCH_INTENT_CUES)) {
            bestScore = Math.max(bestScore, 420 + countCueMatches(normalizedInput, MCP_SEARCH_INTENT_CUES) * 20);
        }
        if (realtimeSearchTool && containsAny(normalizedInput, MCP_REALTIME_INTENT_CUES)) {
            bestScore = Math.max(bestScore, 560 + countCueMatches(normalizedInput, MCP_REALTIME_INTENT_CUES) * 20);
        }

        return bestScore > 0 ? bestScore : Integer.MIN_VALUE;
    }

    private List<String> routingKeywords(McpToolDefinition toolDefinition) {
        List<String> keywords = new ArrayList<>();
        keywords.add(toolDefinition.serverAlias());
        keywords.add(splitCamelCase(toolDefinition.name()));
        keywords.add((toolDefinition.serverAlias() + " " + splitCamelCase(toolDefinition.name())).trim());
        if (toolDefinition.description() != null && !toolDefinition.description().isBlank()) {
            keywords.add(toolDefinition.description());
        }
        String capabilityText = normalize(String.join(" ", keywords));
        if (containsAny(capabilityText, MCP_SEARCH_INTENT_CUES)) {
            keywords.addAll(MCP_SEARCH_INTENT_CUES);
        }
        if (containsAny(capabilityText, MCP_DOC_INTENT_CUES)) {
            keywords.addAll(MCP_DOC_INTENT_CUES);
        }
        if (containsAny(capabilityText, MCP_REALTIME_INTENT_CUES)
                || containsAny(capabilityText, List.of("websearch", "web search", "searchweb", "internet", "网页", "联网"))) {
            keywords.addAll(MCP_REALTIME_INTENT_CUES);
            keywords.addAll(List.of("web search", "websearch", "internet", "联网", "网页"));
        }
        return List.copyOf(keywords);
    }

    private int countMatchedWords(String input, String keyword) {
        int matches = 0;
        for (String part : keyword.split(" ")) {
            if (!isSignificant(part)) {
                continue;
            }
            if (input.contains(part)) {
                matches++;
            }
        }
        return matches;
    }

    private int countCueMatches(String text, List<String> cues) {
        int matches = 0;
        for (String cue : cues) {
            String normalizedCue = normalize(cue);
            if (!normalizedCue.isBlank() && text.contains(normalizedCue)) {
                matches++;
            }
        }
        return matches;
    }

    private boolean containsAny(String text, List<String> cues) {
        if (text == null || text.isBlank() || cues == null || cues.isEmpty()) {
            return false;
        }
        for (String cue : cues) {
            String normalizedCue = normalize(cue);
            if (!normalizedCue.isBlank() && text.contains(normalizedCue)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSignificant(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        long count = value.codePoints().filter(Character::isLetterOrDigit).count();
        return count >= 2;
    }

    private String splitSkillName(String skillName) {
        return skillName == null ? "" : skillName.replaceAll("[._-]+", " ");
    }

    private String splitCamelCase(String value) {
        return value == null ? "" : value.replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }
}
