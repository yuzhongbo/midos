package com.zhongbo.mindos.assistant.skill.learning;

import java.util.List;

public record GeneratedSkillReview(ToolGenerationResult artifact,
                                   String runtimeSkillName,
                                   boolean valid,
                                   List<String> checks) {

    public GeneratedSkillReview {
        runtimeSkillName = runtimeSkillName == null ? "" : runtimeSkillName.trim();
        checks = checks == null ? List.of() : List.copyOf(checks);
    }
}
