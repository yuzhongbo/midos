package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamSchema;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamSchemaRegistry;
import com.zhongbo.mindos.assistant.skill.DecisionCapabilityCatalog;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class HermesToolSchemaCatalog {

    private static final Set<String> RESERVED_AUTO_ROUTE_TARGETS = Set.of(
            "semantic.analyze",
            "llm.orchestrate"
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
            SkillDescriptor rawDescriptor = descriptorsByName.get(normalize(capability.executionSkill()));
            String fallbackDescription = rawDescriptor == null ? "" : rawDescriptor.description();
            schemas.put(capability.decisionTarget(),
                    HermesToolSchema.fromDescriptor(capability.asDescriptor(fallbackDescription), findSchema(capability.executionSkill())));
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
        if (DecisionCapabilityCatalog.findByDecisionTarget(normalized)
                .filter(definition -> isAvailableExecutionSkill(definition.executionSkill()))
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
        return DecisionCapabilityCatalog.findByExecutionSkill(normalized)
                .filter(definition -> isAvailableExecutionSkill(definition.executionSkill()))
                .map(DecisionCapabilityCatalog.CapabilityDefinition::decisionTarget)
                .orElse(normalized);
    }

    String executionTargetForDecision(String decisionTarget) {
        String normalized = normalize(decisionTarget);
        return DecisionCapabilityCatalog.findByDecisionTarget(normalized)
                .filter(definition -> isAvailableExecutionSkill(definition.executionSkill()))
                .map(DecisionCapabilityCatalog.CapabilityDefinition::executionSkill)
                .orElse(normalized);
    }

    boolean isKnownDecisionTarget(String target) {
        String normalized = normalize(target);
        if (normalized.isBlank()) {
            return false;
        }
        if (DecisionCapabilityCatalog.findByDecisionTarget(normalized)
                .filter(definition -> isAvailableExecutionSkill(definition.executionSkill()))
                .isPresent()) {
            return true;
        }
        return availableSkillNames().contains(normalized);
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
        return !normalize(skillName).isBlank() && availableSkillNames().contains(normalize(skillName));
    }

    private record NameDescription(String name, String description) {
    }
}
