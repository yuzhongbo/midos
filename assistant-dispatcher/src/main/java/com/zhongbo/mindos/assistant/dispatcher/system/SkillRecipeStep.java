package com.zhongbo.mindos.assistant.dispatcher.system;

import java.util.Map;

public record SkillRecipeStep(String id,
                              String target,
                              SkillRecipeFailurePolicy failurePolicy,
                              Map<String, SkillRecipeValueRef> bindings) {

    public SkillRecipeStep {
        id = id == null ? "" : id.trim();
        target = target == null ? "" : target.trim();
        failurePolicy = failurePolicy == null ? SkillRecipeFailurePolicy.STOP : failurePolicy;
        bindings = bindings == null ? Map.of() : Map.copyOf(bindings);
    }
}
