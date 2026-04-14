package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamSchema;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamSchemaRegistry;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;

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
        for (SkillDescriptor descriptor : skillCatalog.listSkillDescriptors()) {
            if (descriptor == null || !isDecisionEligible(descriptor.name())) {
                continue;
            }
            schemas.put(descriptor.name(), HermesToolSchema.fromDescriptor(descriptor, findSchema(descriptor.name())));
        }
        for (String summary : skillCatalog.listAvailableSkillSummaries()) {
            NameDescription parsed = parseSummary(summary);
            if (!isDecisionEligible(parsed.name())) {
                continue;
            }
            schemas.putIfAbsent(parsed.name(), HermesToolSchema.of(parsed.name(), parsed.description(), findSchema(parsed.name())));
        }
        return List.copyOf(schemas.values());
    }

    boolean isDecisionEligible(String skillName) {
        String normalized = normalize(skillName);
        return !normalized.isBlank()
                && !RESERVED_AUTO_ROUTE_TARGETS.contains(normalized)
                && !normalized.startsWith("im.")
                && !normalized.startsWith("internal.");
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

    private record NameDescription(String name, String description) {
    }
}
