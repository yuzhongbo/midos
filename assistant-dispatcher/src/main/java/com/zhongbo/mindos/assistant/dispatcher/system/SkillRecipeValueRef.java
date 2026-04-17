package com.zhongbo.mindos.assistant.dispatcher.system;

public record SkillRecipeValueRef(SkillRecipeValueSource source,
                                  String key,
                                  Object literal) {

    public static SkillRecipeValueRef literal(Object literal) {
        return new SkillRecipeValueRef(SkillRecipeValueSource.LITERAL, "", literal);
    }

    public static SkillRecipeValueRef requestParam(String key) {
        return new SkillRecipeValueRef(SkillRecipeValueSource.REQUEST_PARAM, key == null ? "" : key.trim(), null);
    }

    public static SkillRecipeValueRef stepOutput(String stepId) {
        return new SkillRecipeValueRef(SkillRecipeValueSource.STEP_OUTPUT, stepId == null ? "" : stepId.trim(), null);
    }
}
