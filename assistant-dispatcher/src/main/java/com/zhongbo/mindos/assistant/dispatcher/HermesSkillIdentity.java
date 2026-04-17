package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillResult;

import java.util.Map;

final record HermesSkillIdentity(String decisionTarget,
                                 String executionTarget,
                                 String canonicalSkill) {

    static final String DECISION_TARGET_METADATA_KEY = "hermesDecisionTarget";
    static final String EXECUTION_TARGET_METADATA_KEY = "hermesExecutionTarget";
    static final String CANONICAL_SKILL_METADATA_KEY = "hermesCanonicalSkill";

    HermesSkillIdentity {
        decisionTarget = normalize(decisionTarget);
        executionTarget = normalize(firstNonBlank(executionTarget, decisionTarget));
        canonicalSkill = normalize(firstNonBlank(canonicalSkill, executionTarget, decisionTarget));
        if (decisionTarget.isBlank()) {
            decisionTarget = canonicalSkill;
        }
        if (executionTarget.isBlank()) {
            executionTarget = canonicalSkill;
        }
    }

    static HermesSkillIdentity resolve(String target,
                                       HermesToolSchemaCatalog toolSchemaCatalog,
                                       Map<String, Object> contextAttributes) {
        String normalizedTarget = normalize(target);
        if (normalizedTarget.isBlank()) {
            return new HermesSkillIdentity("", "", "");
        }
        if (toolSchemaCatalog == null) {
            return new HermesSkillIdentity(normalizedTarget, normalizedTarget, normalizedTarget);
        }
        Map<String, Object> safeContextAttributes = contextAttributes == null ? Map.of() : contextAttributes;
        String decisionTarget = toolSchemaCatalog.decisionTargetForSkill(normalizedTarget, safeContextAttributes);
        if (decisionTarget.isBlank()) {
            decisionTarget = normalizedTarget;
        }
        String executionTarget = toolSchemaCatalog.executionTargetForDecision(decisionTarget, safeContextAttributes);
        if (executionTarget.isBlank()) {
            executionTarget = decisionTarget;
        }
        return new HermesSkillIdentity(decisionTarget, executionTarget, executionTarget);
    }

    static HermesSkillIdentity fromRecordedOutcome(SkillResult result, String attemptedSkill) {
        String decisionTarget = firstNonBlank(
                result == null ? "" : result.metadataText(DECISION_TARGET_METADATA_KEY),
                result == null ? "" : result.metadataText("systemWorkflowDecisionTarget"),
                attemptedSkill,
                result == null ? "" : result.skillName()
        );
        String executionTarget = firstNonBlank(
                result == null ? "" : result.metadataText(EXECUTION_TARGET_METADATA_KEY),
                result == null ? "" : result.metadataText("systemWorkflowExecutionTarget"),
                result == null ? "" : result.skillName(),
                decisionTarget
        );
        String canonicalSkill = firstNonBlank(
                result == null ? "" : result.metadataText(CANONICAL_SKILL_METADATA_KEY),
                executionTarget,
                decisionTarget
        );
        return new HermesSkillIdentity(decisionTarget, executionTarget, canonicalSkill);
    }

    SkillResult attachTo(SkillResult result) {
        SkillResult safeResult = result == null
                ? SkillResult.failure(recordableSkill(), "skill execution failed")
                : result;
        return safeResult.withMetadata(Map.of(
                DECISION_TARGET_METADATA_KEY, decisionTarget,
                EXECUTION_TARGET_METADATA_KEY, executionTarget,
                CANONICAL_SKILL_METADATA_KEY, canonicalSkill
        ));
    }

    String recordableSkill() {
        return firstNonBlank(canonicalSkill, executionTarget, decisionTarget);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
