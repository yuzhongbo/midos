package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

public record Rule(String ruleId,
                   String description,
                   RuleType type,
                   double threshold,
                   boolean active) {

    public Rule {
        ruleId = ruleId == null ? "" : ruleId.trim();
        description = description == null ? "" : description.trim();
        type = type == null ? RuleType.TASK_COST_REQUIRED : type;
        threshold = clamp(threshold);
    }

    public Rule withThreshold(double nextThreshold) {
        return new Rule(ruleId, description, type, nextThreshold, active);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, value);
    }
}
