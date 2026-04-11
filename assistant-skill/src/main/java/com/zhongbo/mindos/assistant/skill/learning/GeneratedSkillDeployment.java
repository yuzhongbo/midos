package com.zhongbo.mindos.assistant.skill.learning;

public record GeneratedSkillDeployment(ToolGenerationResult artifact,
                                       String registeredSkillName,
                                       boolean replaced) {

    public GeneratedSkillDeployment {
        registeredSkillName = registeredSkillName == null ? "" : registeredSkillName.trim();
    }
}
