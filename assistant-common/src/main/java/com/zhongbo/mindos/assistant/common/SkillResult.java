package com.zhongbo.mindos.assistant.common;

public record SkillResult(String skillName, String output, boolean success) {

    public static SkillResult success(String skillName, String output) {
        return new SkillResult(skillName, output, true);
    }

    public static SkillResult failure(String skillName, String output) {
        return new SkillResult(skillName, output, false);
    }
}

