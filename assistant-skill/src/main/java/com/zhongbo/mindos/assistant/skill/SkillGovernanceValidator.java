package com.zhongbo.mindos.assistant.skill;

import com.zhongbo.mindos.assistant.skill.cloudapi.CloudApiSkillDefinition;
import com.zhongbo.mindos.assistant.skill.learning.ToolGenerationResult;
import com.zhongbo.mindos.assistant.skill.loader.ScriptSkillDefinition;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SkillGovernanceValidator {

    private static final Pattern SKILL_NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Set<String> RESERVED_PREFIXES = Set.of(
            "internal.",
            "skills.",
            "semantic.",
            "llm.",
            "mcp."
    );
    private static final Set<String> ALLOWED_HTTP_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final String ENV_PLACEHOLDER = "${env.";

    public void validateScriptDefinition(ScriptSkillDefinition definition, String source) {
        if (definition == null) {
            throw invalid(source, "Skill definition is required");
        }
        validateSkillName(definition.name(), source, false);
        requireNonBlank(definition.description(), source, "description");
        requireAtLeastOne(definition.triggers(), source, "triggers");
        requireNonBlank(definition.response(), source, "response");
    }

    public void validateCloudDefinition(CloudApiSkillDefinition definition, String source) {
        if (definition == null) {
            throw invalid(source, "Cloud skill definition is required");
        }
        validateSkillName(definition.name(), source, false);
        requireNonBlank(definition.description(), source, "description");
        requireAtLeastOne(definition.keywords(), source, "keywords");
        validateHttpUrl(definition.url(), source, "url");
        String method = normalize(definition.method());
        if (!ALLOWED_HTTP_METHODS.contains(method)) {
            throw invalid(source, "Unsupported HTTP method: " + definition.method());
        }
        rejectEnvPlaceholder(definition.url(), source, "url");
        rejectEnvPlaceholder(definition.bodyTemplate(), source, "bodyTemplate");
        rejectEnvPlaceholder(definition.apiKey(), source, "apiKey");
        rejectEnvPlaceholder(definition.resultTemplate(), source, "resultTemplate");
        validateTemplateMap(definition.headers(), source, "headers");
        validateTemplateMap(definition.queryParams(), source, "queryParams");
    }

    public void validateGeneratedArtifact(ToolGenerationResult artifact) {
        if (artifact == null) {
            throw invalid("generated-skill", "Generated skill artifact is required");
        }
        validateSkillName(artifact.skillName(), "generated-skill", true);
        requireNonBlank(artifact.description(), "generated-skill", "description");
        requireAtLeastOne(artifact.routingKeywords(), "generated-skill", "routingKeywords");
        requireNonBlank(artifact.sourceCode(), "generated-skill", "sourceCode");
    }

    public void validateRuntimeSkill(Skill skill, String source) {
        validateRuntimeSkill(skill, source, false);
    }

    public void validateGeneratedRuntimeSkill(Skill skill, String source) {
        validateRuntimeSkill(skill, source, true);
    }

    private void validateRuntimeSkill(Skill skill, String source, boolean allowGeneratedPrefix) {
        if (skill == null) {
            throw invalid(source, "Skill instance is required");
        }
        validateSkillName(skill.name(), source, allowGeneratedPrefix);
        requireNonBlank(skill.description(), source, "description");
        if (skill instanceof SkillDescriptorProvider descriptorProvider) {
            SkillDescriptor descriptor = descriptorProvider.skillDescriptor();
            if (descriptor == null) {
                throw invalid(source, "Descriptor provider returned null descriptor");
            }
            validateSkillName(descriptor.name(), source, allowGeneratedPrefix);
            if (!skill.name().equals(descriptor.name())) {
                throw invalid(source, "Descriptor name must match skill name");
            }
        }
    }

    private void validateTemplateMap(Map<String, String> values, String source, String fieldName) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw invalid(source, fieldName + " contains an empty key");
            }
            rejectEnvPlaceholder(entry.getKey(), source, fieldName + " key");
            rejectEnvPlaceholder(entry.getValue(), source, fieldName + " value");
        }
    }

    private void validateHttpUrl(String rawUrl, String source, String fieldName) {
        requireNonBlank(rawUrl, source, fieldName);
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception ex) {
            throw invalid(source, "Invalid URL in " + fieldName);
        }
        String scheme = normalize(uri.getScheme());
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw invalid(source, "Only http/https URLs are allowed in " + fieldName);
        }
        if (normalize(uri.getHost()).isBlank()) {
            throw invalid(source, "URL host is required in " + fieldName);
        }
    }

    private void validateSkillName(String skillName, String source, boolean allowGeneratedPrefix) {
        requireNonBlank(skillName, source, "name");
        String normalized = skillName.trim();
        if (!SKILL_NAME_PATTERN.matcher(normalized).matches()) {
            throw invalid(source, "Skill name contains unsupported characters: " + skillName);
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String prefix : RESERVED_PREFIXES) {
            if (lower.startsWith(prefix)) {
                throw invalid(source, "Reserved skill namespace: " + skillName);
            }
        }
        if (!allowGeneratedPrefix && lower.startsWith("generated.")) {
            throw invalid(source, "Reserved generated.* namespace: " + skillName);
        }
    }

    private void requireNonBlank(String value, String source, String fieldName) {
        if (value == null || value.isBlank()) {
            throw invalid(source, "Field '" + fieldName + "' is required");
        }
    }

    private void requireAtLeastOne(List<String> values, String source, String fieldName) {
        if (values == null || values.stream().noneMatch(value -> value != null && !value.isBlank())) {
            throw invalid(source, "At least one non-empty " + fieldName + " entry is required");
        }
    }

    private void rejectEnvPlaceholder(String value, String source, String fieldName) {
        if (value != null && value.contains(ENV_PLACEHOLDER)) {
            throw invalid(source, "Environment placeholders are not allowed in " + fieldName);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private IllegalArgumentException invalid(String source, String message) {
        String label = source == null || source.isBlank() ? "skill-governance" : source;
        return new IllegalArgumentException(label + ": " + message);
    }
}
