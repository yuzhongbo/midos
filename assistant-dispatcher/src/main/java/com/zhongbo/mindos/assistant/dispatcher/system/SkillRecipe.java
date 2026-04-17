package com.zhongbo.mindos.assistant.dispatcher.system;

import java.util.List;

public record SkillRecipe(String id,
                          SkillRecipeSelector selector,
                          String workflowName,
                          String workflowReason,
                          List<SkillRecipeStep> steps,
                          boolean safeAutoRun,
                          int maxDepth) {

    public SkillRecipe {
        id = id == null ? "" : id.trim();
        workflowName = workflowName == null ? "" : workflowName.trim();
        workflowReason = workflowReason == null ? "" : workflowReason.trim();
        steps = steps == null ? List.of() : List.copyOf(steps);
        maxDepth = Math.max(1, maxDepth);
    }

    public String rootDecisionTarget() {
        return selector == null ? "" : selector.decisionTarget();
    }
}
