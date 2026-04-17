package com.zhongbo.mindos.assistant.skill.learning;

import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillGovernanceValidator;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

@Service
public class ToolLearningService {

    private final ToolGenerator toolGenerator;
    private final GeneratedSkillCompiler generatedSkillCompiler;
    private final SkillRegistry skillRegistry;
    private final SkillGovernanceValidator skillGovernanceValidator;

    ToolLearningService(ToolGenerator toolGenerator,
                        GeneratedSkillCompiler generatedSkillCompiler,
                        SkillRegistry skillRegistry) {
        this(toolGenerator, generatedSkillCompiler, skillRegistry, new SkillGovernanceValidator());
    }

    @Autowired
    public ToolLearningService(ToolGenerator toolGenerator,
                               GeneratedSkillCompiler generatedSkillCompiler,
                               SkillRegistry skillRegistry,
                               SkillGovernanceValidator skillGovernanceValidator) {
        this.toolGenerator = toolGenerator;
        this.generatedSkillCompiler = generatedSkillCompiler;
        this.skillRegistry = skillRegistry;
        this.skillGovernanceValidator = skillGovernanceValidator;
    }

    public GeneratedSkillDeployment generateAndRegister(ToolGenerationRequest request) {
        ToolGenerationResult artifact = generateDraft(request);
        Skill generatedSkill = generatedSkillCompiler.compile(artifact);
        Objects.requireNonNull(generatedSkill, "generatedSkill");
        skillGovernanceValidator.validateGeneratedRuntimeSkill(generatedSkill, artifact.skillName());
        boolean replaced = skillRegistry.containsSkill(generatedSkill.name());
        skillRegistry.register(generatedSkill);
        return new GeneratedSkillDeployment(artifact, generatedSkill.name(), replaced);
    }

    public ToolGenerationResult generateDraft(ToolGenerationRequest request) {
        ToolGenerationRequest safeRequest = request == null
                ? new ToolGenerationRequest("", "", "", Map.of())
                : request;
        ToolGenerationResult artifact = toolGenerator.generate(safeRequest);
        skillGovernanceValidator.validateGeneratedArtifact(artifact);
        return artifact;
    }
}
