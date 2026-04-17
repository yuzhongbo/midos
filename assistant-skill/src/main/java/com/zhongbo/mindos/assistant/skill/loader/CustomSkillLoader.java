package com.zhongbo.mindos.assistant.skill.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.skill.SkillGovernanceValidator;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads JSON-defined custom skills from a local directory at startup.
 *
 * Configure in application.properties:
 *   mindos.skills.custom-dir=/path/to/my-skills
 *
 * Each .json file in the directory is parsed as a {@link ScriptSkillDefinition}
 * and registered as a {@link ScriptSkill}.
 *
 * Hot-reload is supported via POST /api/skills/reload (calls {@link #reload()}).
 */
@Component
public class CustomSkillLoader {

    private static final Logger LOGGER = Logger.getLogger(CustomSkillLoader.class.getName());

    private final SkillRegistry skillRegistry;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String customSkillDir;
    private final SkillGovernanceValidator skillGovernanceValidator;

    @Autowired
    public CustomSkillLoader(SkillRegistry skillRegistry,
                             LlmClient llmClient,
                             SkillGovernanceValidator skillGovernanceValidator,
                             @Value("${mindos.skills.custom-dir:}") String customSkillDir) {
        this(skillRegistry, llmClient, customSkillDir, skillGovernanceValidator);
    }

    CustomSkillLoader(SkillRegistry skillRegistry,
                      LlmClient llmClient,
                      @Value("${mindos.skills.custom-dir:}") String customSkillDir) {
        this(skillRegistry, llmClient, customSkillDir, new SkillGovernanceValidator());
    }

    CustomSkillLoader(SkillRegistry skillRegistry,
                      LlmClient llmClient,
                      String customSkillDir,
                      SkillGovernanceValidator skillGovernanceValidator) {
        this.skillRegistry = skillRegistry;
        this.llmClient = llmClient;
        this.customSkillDir = customSkillDir;
        this.skillGovernanceValidator = skillGovernanceValidator;
    }

    @PostConstruct
    public void loadOnStartup() {
        if (customSkillDir == null || customSkillDir.isBlank()) {
            LOGGER.info("CustomSkillLoader: mindos.skills.custom-dir not set, skipping custom skill loading.");
            return;
        }
        int count = reload();
        LOGGER.info("CustomSkillLoader: loaded " + count + " custom skill(s) from " + customSkillDir);
    }

    /**
     * Scans the configured directory and re-registers all valid JSON-defined skills.
     * Existing skills with the same name are overwritten in the registry.
     *
     * @return number of skills loaded
     */
    public int reload() {
        if (customSkillDir == null || customSkillDir.isBlank()) {
            return 0;
        }
        Path dir = Path.of(customSkillDir);
        if (!Files.isDirectory(dir)) {
            LOGGER.warning("CustomSkillLoader: directory not found: " + customSkillDir);
            return 0;
        }

        List<String> loaded = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .forEach(jsonPath -> {
                        try {
                            ScriptSkillDefinition def = objectMapper.readValue(
                                    jsonPath.toFile(), ScriptSkillDefinition.class);
                            skillGovernanceValidator.validateScriptDefinition(def, jsonPath.toString());
                            ScriptSkill skill = createSkill(def);
                            skillGovernanceValidator.validateRuntimeSkill(skill, jsonPath.toString());
                            skillRegistry.register(skill);
                            loaded.add(def.name());
                            LOGGER.info("CustomSkillLoader: registered custom skill '" + def.name()
                                    + "' from " + jsonPath.getFileName());
                        } catch (IOException | IllegalArgumentException ex) {
                            LOGGER.log(Level.WARNING,
                                    "CustomSkillLoader: failed to load skill definition from " + jsonPath, ex);
                        }
                    });
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "CustomSkillLoader: failed to list directory " + customSkillDir, ex);
        }

        LOGGER.info("CustomSkillLoader: registered skills: " + loaded);
        return loaded.size();
    }

    public String getCustomSkillDir() {
        return customSkillDir;
    }

    private ScriptSkill createSkill(ScriptSkillDefinition definition) {
        if ("llm".equalsIgnoreCase(definition.response())) {
            return new LlmScriptSkill(definition, llmClient);
        }
        return new TemplateScriptSkill(definition);
    }
}
