package com.zhongbo.mindos.assistant.skill.cloudapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import jakarta.annotation.PostConstruct;
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
 * Loads JSON-defined cloud API skills from a configured local directory at startup.
 *
 * Configure in application.properties:
 * <pre>
 *   mindos.skills.cloud-api.config-dir=/path/to/cloud-skills
 * </pre>
 *
 * Each {@code .json} file in the directory is parsed as a {@link CloudApiSkillDefinition}
 * and registered as a {@link CloudApiSkill} in the {@link SkillRegistry}.
 *
 * Hot-reload is supported via {@code POST /api/skills/reload-cloud} (calls {@link #reload()}).
 */
@Component
public class CloudApiSkillLoader {

    private static final Logger LOGGER = Logger.getLogger(CloudApiSkillLoader.class.getName());

    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final String configDir;

    public CloudApiSkillLoader(SkillRegistry skillRegistry,
                               @Value("${mindos.skills.cloud-api.config-dir:}") String configDir) {
        this.skillRegistry = skillRegistry;
        this.configDir = configDir;
    }

    @PostConstruct
    public void loadOnStartup() {
        if (configDir == null || configDir.isBlank()) {
            LOGGER.info("CloudApiSkillLoader: mindos.skills.cloud-api.config-dir not set, skipping cloud API skill loading.");
            return;
        }
        int count = reload();
        LOGGER.info("CloudApiSkillLoader: loaded " + count + " cloud API skill(s) from " + configDir);
    }

    /**
     * Scans the configured directory and re-registers all valid cloud API skill definitions.
     * Existing skills with the same name are overwritten in the registry.
     *
     * @return number of skills successfully loaded
     */
    public int reload() {
        if (configDir == null || configDir.isBlank()) {
            return 0;
        }
        Path dir = Path.of(configDir);
        if (!Files.isDirectory(dir)) {
            LOGGER.warning("CloudApiSkillLoader: directory not found: " + configDir);
            return 0;
        }

        List<String> loaded = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .forEach(jsonPath -> {
                        try {
                            CloudApiSkillDefinition def = objectMapper.readValue(
                                    jsonPath.toFile(), CloudApiSkillDefinition.class);
                            if (def.name() == null || def.name().isBlank()) {
                                LOGGER.warning("CloudApiSkillLoader: skipping definition with empty name in " + jsonPath);
                                return;
                            }
                            if (def.url() == null || def.url().isBlank()) {
                                LOGGER.warning("CloudApiSkillLoader: skipping definition with empty url in " + jsonPath);
                                return;
                            }
                            skillRegistry.register(new CloudApiSkill(def));
                            loaded.add(def.name());
                            LOGGER.info("CloudApiSkillLoader: registered cloud API skill '"
                                    + def.name() + "' -> " + def.url()
                                    + " from " + jsonPath.getFileName());
                        } catch (IOException ex) {
                            LOGGER.log(Level.WARNING,
                                    "CloudApiSkillLoader: failed to parse cloud API skill definition from " + jsonPath, ex);
                        }
                    });
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "CloudApiSkillLoader: failed to list directory " + configDir, ex);
        }

        LOGGER.info("CloudApiSkillLoader: registered cloud API skills: " + loaded);
        return loaded.size();
    }

    public String getConfigDir() {
        return configDir;
    }
}
