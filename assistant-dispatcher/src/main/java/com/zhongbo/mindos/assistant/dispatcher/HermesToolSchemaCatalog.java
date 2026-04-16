package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamSchema;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamSchemaRegistry;
import com.zhongbo.mindos.assistant.skill.DecisionCapabilityCatalog;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import com.zhongbo.mindos.assistant.skill.SkillCandidate;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class HermesToolSchemaCatalog {

    private static final List<String> DEFAULT_WEB_SEARCH_PRIORITY = List.of(
            "mcp.qwensearch.websearch",
            "mcp.bravesearch.websearch",
            "mcp.serper.websearch",
            "mcp.serpapi.websearch"
    );

    private static final Set<String> RESERVED_AUTO_ROUTE_TARGETS = Set.of(
            "semantic.analyze",
            "llm.orchestrate"
    );

    private static final Set<String> WEB_LOOKUP_NEWS_KEYWORDS = Set.of(
            "今天新闻",
            "最新新闻",
            "新闻",
            "news",
            "头条",
            "热点"
    );

    private final SkillCatalogFacade skillCatalog;
    private final ParamSchemaRegistry paramSchemaRegistry;

    HermesToolSchemaCatalog(SkillCatalogFacade skillCatalog, ParamSchemaRegistry paramSchemaRegistry) {
        this.skillCatalog = skillCatalog;
        this.paramSchemaRegistry = paramSchemaRegistry;
    }

    List<HermesToolSchema> listSchemas() {
        if (skillCatalog == null) {
            return List.of();
        }
        Map<String, HermesToolSchema> schemas = new LinkedHashMap<>();
        Map<String, SkillDescriptor> descriptorsByName = new LinkedHashMap<>();
        for (SkillDescriptor descriptor : skillCatalog.listSkillDescriptors()) {
            if (descriptor == null || normalize(descriptor.name()).isBlank()) {
                continue;
            }
            descriptorsByName.put(normalize(descriptor.name()), descriptor);
        }
        Set<String> availableSkillNames = availableSkillNames();
        for (DecisionCapabilityCatalog.CapabilityDefinition capability : DecisionCapabilityCatalog.availableCapabilities(availableSkillNames)) {
            String executionSkill = resolveCapabilityExecutionSkill(capability, availableSkillNames, Map.of());
            SkillDescriptor rawDescriptor = descriptorsByName.get(normalize(executionSkill));
            String fallbackDescription = rawDescriptor == null ? "" : rawDescriptor.description();
            SkillDescriptor capabilityDescriptor = capabilityDescriptor(capability, fallbackDescription, availableSkillNames);
            schemas.put(capability.decisionTarget(),
                    HermesToolSchema.fromDescriptor(capabilityDescriptor, findSchema(executionSkill)));
        }
        for (SkillDescriptor descriptor : skillCatalog.listSkillDescriptors()) {
            if (descriptor == null) {
                continue;
            }
            String decisionTarget = decisionTargetForSkill(descriptor.name());
            if (!decisionTarget.equals(normalize(descriptor.name())) || !isDecisionEligible(decisionTarget)) {
                continue;
            }
            schemas.put(decisionTarget, HermesToolSchema.fromDescriptor(descriptor, findSchema(descriptor.name())));
        }
        for (String summary : skillCatalog.listAvailableSkillSummaries()) {
            NameDescription parsed = parseSummary(summary);
            String decisionTarget = decisionTargetForSkill(parsed.name());
            if (!decisionTarget.equals(normalize(parsed.name())) || !isDecisionEligible(decisionTarget)) {
                continue;
            }
            schemas.putIfAbsent(decisionTarget, HermesToolSchema.of(parsed.name(), parsed.description(), findSchema(parsed.name())));
        }
        return List.copyOf(schemas.values());
    }

    boolean isDecisionEligible(String skillName) {
        String normalized = normalize(skillName);
        Set<String> available = availableSkillNames();
        if (DecisionCapabilityCatalog.WEB_LOOKUP_DECISION_TARGET.equals(normalized)) {
            return hasGenericWebSearchSkill(available);
        }
        if (DecisionCapabilityCatalog.findByDecisionTarget(normalized)
                .filter(definition -> isCapabilityAvailable(definition, available))
                .isPresent()) {
            return true;
        }
        return !normalized.isBlank()
                && !RESERVED_AUTO_ROUTE_TARGETS.contains(normalized)
                && DecisionCapabilityCatalog.findByExecutionSkill(normalized).isEmpty()
                && !normalized.startsWith("im.")
                && !normalized.startsWith("internal.")
                && !normalized.startsWith("skills.");
    }

    String decisionTargetForSkill(String skillName) {
        String normalized = normalize(skillName);
        Set<String> available = availableSkillNames();
        return DecisionCapabilityCatalog.findByExecutionSkill(normalized)
                .filter(definition -> isCapabilityAvailable(definition, available))
                .map(DecisionCapabilityCatalog.CapabilityDefinition::decisionTarget)
                .orElse(normalized);
    }

    String executionTargetForDecision(String decisionTarget) {
        return executionTargetForDecision(decisionTarget, Map.of());
    }

    String executionTargetForDecision(String decisionTarget, Map<String, Object> contextAttributes) {
        String normalized = normalize(decisionTarget);
        Set<String> available = availableSkillNames();
        return DecisionCapabilityCatalog.findByDecisionTarget(normalized)
                .filter(definition -> isCapabilityAvailable(definition, available))
                .map(definition -> resolveCapabilityExecutionSkill(definition, available, contextAttributes))
                .filter(resolved -> !resolved.isBlank())
                .orElse(normalized);
    }

    boolean isKnownDecisionTarget(String target) {
        String normalized = normalize(target);
        if (normalized.isBlank()) {
            return false;
        }
        Set<String> available = availableSkillNames();
        if (DecisionCapabilityCatalog.findByDecisionTarget(normalized)
                .filter(definition -> isCapabilityAvailable(definition, available))
                .isPresent()) {
            return true;
        }
        return available.contains(normalized);
    }

    List<SkillCandidate> detectDecisionCandidates(String input, int limit) {
        if (input == null || input.isBlank() || limit <= 0) {
            return List.of();
        }
        List<SkillCandidate> candidates = new ArrayList<>();
        for (HermesToolSchema schema : listSchemas()) {
            if (schema == null || schema.name().isBlank()) {
                continue;
            }
            int score = schema.routingScore(input);
            if (score > 0) {
                candidates.add(new SkillCandidate(schema.name(), score));
            }
        }
        candidates.sort(Comparator.comparingInt(SkillCandidate::score).reversed()
                .thenComparing(SkillCandidate::skillName));
        int safeLimit = Math.min(limit, candidates.size());
        return safeLimit <= 0 ? List.of() : List.copyOf(candidates.subList(0, safeLimit));
    }

    private ParamSchema findSchema(String skillName) {
        if (paramSchemaRegistry == null) {
            return null;
        }
        return paramSchemaRegistry.find(skillName).orElse(null);
    }

    private NameDescription parseSummary(String rawSummary) {
        String normalized = normalize(rawSummary);
        if (normalized.isBlank()) {
            return new NameDescription("", "");
        }
        int separator = normalized.indexOf(" - ");
        if (separator < 0) {
            return new NameDescription(normalized, "");
        }
        return new NameDescription(
                normalized.substring(0, separator).trim(),
                normalized.substring(separator + 3).trim()
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private Set<String> availableSkillNames() {
        if (skillCatalog == null) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (SkillDescriptor descriptor : skillCatalog.listSkillDescriptors()) {
            if (descriptor != null && descriptor.name() != null && !descriptor.name().isBlank()) {
                names.add(normalize(descriptor.name()));
            }
        }
        for (String summary : skillCatalog.listAvailableSkillSummaries()) {
            String name = parseSummary(summary).name();
            if (!name.isBlank()) {
                names.add(normalize(name));
            }
        }
        return Set.copyOf(names);
    }

    private boolean isAvailableExecutionSkill(String skillName) {
        return isAvailableExecutionSkill(skillName, availableSkillNames());
    }

    private boolean isAvailableExecutionSkill(String skillName, Set<String> availableSkillNames) {
        return !normalize(skillName).isBlank() && availableSkillNames.contains(normalize(skillName));
    }

    private boolean isCapabilityAvailable(DecisionCapabilityCatalog.CapabilityDefinition definition,
                                          Set<String> availableSkillNames) {
        if (definition == null) {
            return false;
        }
        if (DecisionCapabilityCatalog.WEB_LOOKUP_DECISION_TARGET.equals(normalize(definition.decisionTarget()))) {
            return hasGenericWebSearchSkill(availableSkillNames);
        }
        return isAvailableExecutionSkill(definition.executionSkill(), availableSkillNames);
    }

    private String resolveCapabilityExecutionSkill(DecisionCapabilityCatalog.CapabilityDefinition definition,
                                                   Set<String> availableSkillNames,
                                                   Map<String, Object> contextAttributes) {
        if (definition == null) {
            return "";
        }
        if (DecisionCapabilityCatalog.WEB_LOOKUP_DECISION_TARGET.equals(normalize(definition.decisionTarget()))) {
            return resolvePreferredGenericWebSearchSkill(contextAttributes, availableSkillNames);
        }
        return definition.executionSkill();
    }

    private SkillDescriptor capabilityDescriptor(DecisionCapabilityCatalog.CapabilityDefinition capability,
                                                 String fallbackDescription,
                                                 Set<String> availableSkillNames) {
        SkillDescriptor descriptor = capability.asDescriptor(fallbackDescription);
        if (!DecisionCapabilityCatalog.WEB_LOOKUP_DECISION_TARGET.equals(normalize(capability.decisionTarget()))
                || !isAvailableExecutionSkill("news_search", availableSkillNames)) {
            return descriptor;
        }
        List<String> filteredKeywords = descriptor.routingKeywords().stream()
                .filter(keyword -> !WEB_LOOKUP_NEWS_KEYWORDS.contains(keyword.toLowerCase(Locale.ROOT)))
                .toList();
        return new SkillDescriptor(descriptor.name(), descriptor.description(), filteredKeywords);
    }

    private String resolvePreferredGenericWebSearchSkill(Map<String, Object> contextAttributes,
                                                         Set<String> availableSkillNames) {
        List<String> actualGenericSkills = availableSkillNames.stream()
                .filter(DecisionCapabilityCatalog::isGenericWebSearchExecutionSkill)
                .sorted()
                .toList();
        if (actualGenericSkills.isEmpty()) {
            return "";
        }
        for (String desired : parseSearchPriorityOrder(contextAttributes)) {
            for (String actual : actualGenericSkills) {
                if (actual.equalsIgnoreCase(desired)) {
                    return actual;
                }
            }
        }
        for (String desired : DEFAULT_WEB_SEARCH_PRIORITY) {
            for (String actual : actualGenericSkills) {
                if (actual.equalsIgnoreCase(desired)) {
                    return actual;
                }
            }
        }
        return actualGenericSkills.get(0);
    }

    private boolean hasGenericWebSearchSkill(Set<String> availableSkillNames) {
        if (availableSkillNames == null || availableSkillNames.isEmpty()) {
            return false;
        }
        return availableSkillNames.stream().anyMatch(DecisionCapabilityCatalog::isGenericWebSearchExecutionSkill);
    }

    private List<String> parseSearchPriorityOrder(Map<String, Object> contextAttributes) {
        if (contextAttributes == null || contextAttributes.isEmpty()) {
            return List.of();
        }
        Object raw = contextAttributes.get("searchPriorityOrder");
        if (raw instanceof List<?> values) {
            return values.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
        }
        if (raw == null) {
            return List.of();
        }
        String csv = String.valueOf(raw).trim();
        if (csv.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String token : csv.split(",")) {
            String value = token == null ? "" : token.trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values.isEmpty() ? List.of() : List.copyOf(values);
    }

    private record NameDescription(String name, String description) {
    }
}
