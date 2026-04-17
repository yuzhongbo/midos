package com.zhongbo.mindos.assistant.dispatcher.system;

import com.zhongbo.mindos.assistant.skill.DecisionCapabilityCatalog;

import java.util.Locale;

public record SkillRecipeSelector(String decisionTarget, String executionTargetPattern) {

    public static final String EXECUTION_TARGET_TOKEN = "$executionTarget";
    public static final String GENERIC_WEB_SEARCH_PATTERN = "$generic-web-search";

    public SkillRecipeSelector {
        decisionTarget = normalize(decisionTarget);
        executionTargetPattern = normalize(executionTargetPattern);
    }

    public boolean matches(String decisionTarget, String executionTarget) {
        String normalizedDecisionTarget = normalize(decisionTarget);
        String normalizedExecutionTarget = normalize(executionTarget);
        if (this.decisionTarget.isBlank() || !this.decisionTarget.equals(normalizedDecisionTarget)) {
            return false;
        }
        if (executionTargetPattern.isBlank() || "*".equals(executionTargetPattern)) {
            return true;
        }
        if (GENERIC_WEB_SEARCH_PATTERN.equals(executionTargetPattern)) {
            return DecisionCapabilityCatalog.isGenericWebSearchExecutionSkill(normalizedExecutionTarget);
        }
        return executionTargetPattern.equals(normalizedExecutionTarget);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
