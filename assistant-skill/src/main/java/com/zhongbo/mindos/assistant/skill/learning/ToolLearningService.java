package com.zhongbo.mindos.assistant.skill.learning;

import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

@Service
public class ToolLearningService {

    private final ToolGenerator toolGenerator;
    private final GeneratedSkillCompiler generatedSkillCompiler;
    private final SkillRegistry skillRegistry;

    public ToolLearningService(ToolGenerator toolGenerator,
                               GeneratedSkillCompiler generatedSkillCompiler,
                               SkillRegistry skillRegistry) {
        this.toolGenerator = toolGenerator;
        this.generatedSkillCompiler = generatedSkillCompiler;
        this.skillRegistry = skillRegistry;
    }

    public GeneratedSkillDeployment generateAndRegister(ToolGenerationRequest request) {
        ToolGenerationRequest safeRequest = request == null
                ? new ToolGenerationRequest("", "", "", Map.of())
                : request;
        ToolGenerationResult artifact = toolGenerator.generate(safeRequest);
        Skill generatedSkill = generatedSkillCompiler.compile(artifact);
        Objects.requireNonNull(generatedSkill, "generatedSkill");
        boolean replaced = skillRegistry.containsSkill(generatedSkill.name());
        skillRegistry.register(generatedSkill);
        return new GeneratedSkillDeployment(artifact, generatedSkill.name(), replaced);
    }
}
