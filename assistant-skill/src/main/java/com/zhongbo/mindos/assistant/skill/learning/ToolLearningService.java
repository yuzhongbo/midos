package com.zhongbo.mindos.assistant.skill.learning;

import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillGovernanceValidator;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ToolLearningService {

    private final ToolGenerator toolGenerator;
    private final GeneratedSkillCompiler generatedSkillCompiler;
    private final SkillRegistry skillRegistry;
    private final SkillGovernanceValidator skillGovernanceValidator;
    private final GeneratedSkillArtifactStore generatedSkillArtifactStore;

    ToolLearningService(ToolGenerator toolGenerator,
                        GeneratedSkillCompiler generatedSkillCompiler,
                        SkillRegistry skillRegistry) {
        this(toolGenerator, generatedSkillCompiler, skillRegistry,
                new SkillGovernanceValidator(), new GeneratedSkillArtifactStore());
    }

    ToolLearningService(ToolGenerator toolGenerator,
                        GeneratedSkillCompiler generatedSkillCompiler,
                        SkillRegistry skillRegistry,
                        SkillGovernanceValidator skillGovernanceValidator) {
        this(toolGenerator, generatedSkillCompiler, skillRegistry,
                skillGovernanceValidator, new GeneratedSkillArtifactStore());
    }

    @Autowired
    public ToolLearningService(ToolGenerator toolGenerator,
                               GeneratedSkillCompiler generatedSkillCompiler,
                               SkillRegistry skillRegistry,
                               SkillGovernanceValidator skillGovernanceValidator,
                               GeneratedSkillArtifactStore generatedSkillArtifactStore) {
        this.toolGenerator = toolGenerator;
        this.generatedSkillCompiler = generatedSkillCompiler;
        this.skillRegistry = skillRegistry;
        this.skillGovernanceValidator = skillGovernanceValidator;
        this.generatedSkillArtifactStore = generatedSkillArtifactStore == null
                ? new GeneratedSkillArtifactStore()
                : generatedSkillArtifactStore;
    }

    public GeneratedSkillDeployment generateAndRegister(ToolGenerationRequest request) {
        ToolGenerationResult artifact = generateDraft(request);
        Skill generatedSkill = compileAndValidateRuntimeSkill(artifact);
        boolean replaced = skillRegistry.containsSkill(generatedSkill.name());
        skillRegistry.register(generatedSkill);
        return new GeneratedSkillDeployment(artifact, generatedSkill.name(), replaced);
    }

    public GeneratedSkillReview reviewDraft(ToolGenerationRequest request) {
        ToolGenerationResult artifact = generateDraft(request);
        Skill generatedSkill = compileAndValidateRuntimeSkill(artifact);
        return new GeneratedSkillReview(
                artifact,
                generatedSkill.name(),
                true,
                List.of(
                        "generated-artifact-validated",
                        "compiled",
                        "runtime-skill-validated"
                )
        );
    }

    public GeneratedSkillReview evolveReviewedDraft(String userId, String skillName, String evolutionGoal) {
        ToolGenerationResult sourceArtifact = generatedSkillArtifactStore.find(skillName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown generated skill: " + skillName));
        String baseRequest = stringMetadata(sourceArtifact, "request");
        if (baseRequest.isBlank()) {
            baseRequest = sourceArtifact.description();
        }
        String baseName = stringMetadata(sourceArtifact, "baseName");
        if (baseName.isBlank()) {
            baseName = sourceArtifact.skillName();
        }
        Map<String, Object> hints = new java.util.LinkedHashMap<>(sourceArtifact.metadata());
        hints.put("parentSkill", sourceArtifact.skillName());
        hints.put("evolutionGoal", normalizeText(evolutionGoal, "improve reliability and error reporting"));
        hints.put("evolutionStage", "reviewed-draft");
        String evolvedRequest = baseRequest + "\n\n优化要求：" + normalizeText(
                evolutionGoal,
                "Improve stability, input validation, and clearer failure reporting while preserving the original intent."
        );
        return reviewDraft(new ToolGenerationRequest(
                userId == null || userId.isBlank() ? stringMetadata(sourceArtifact, "userId") : userId,
                evolvedRequest,
                baseName,
                hints
        ));
    }

    public ToolGenerationResult generateDraft(ToolGenerationRequest request) {
        ToolGenerationRequest safeRequest = request == null
                ? new ToolGenerationRequest("", "", "", Map.of())
                : request;
        ToolGenerationResult artifact = toolGenerator.generate(safeRequest);
        skillGovernanceValidator.validateGeneratedArtifact(artifact);
        generatedSkillArtifactStore.remember(artifact);
        return artifact;
    }

    private Skill compileAndValidateRuntimeSkill(ToolGenerationResult artifact) {
        Skill generatedSkill = generatedSkillCompiler.compile(artifact);
        Objects.requireNonNull(generatedSkill, "generatedSkill");
        skillGovernanceValidator.validateGeneratedRuntimeSkill(generatedSkill, artifact.skillName());
        return generatedSkill;
    }

    private String stringMetadata(ToolGenerationResult artifact, String key) {
        if (artifact == null || key == null || key.isBlank()) {
            return "";
        }
        Object value = artifact.metadata().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String normalizeText(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isBlank() ? fallback : normalized;
    }
}
