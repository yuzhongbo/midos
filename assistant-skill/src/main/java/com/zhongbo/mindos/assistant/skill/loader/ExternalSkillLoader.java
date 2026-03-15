package com.zhongbo.mindos.assistant.skill.loader;

import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads external skill JARs from HTTP/HTTPS/file URLs at startup or on demand.
 *
 * Configure in application.properties (comma-separated URLs):
 *   mindos.skills.external-jars=https://example.com/skill-weather.jar,https://example.com/skill-stocks.jar
 *
 * External skill JARs must:
 *   1. Implement {@link Skill} (from assistant-skill).
 *   2. Declare implementations in META-INF/services/com.zhongbo.mindos.assistant.skill.Skill
 *      (standard Java ServiceLoader SPI pattern).
 *
 * Individual JARs can also be loaded at runtime via POST /api/skills/load-jar.
 *
 * Note: classloaders for loaded JARs are intentionally kept alive so that skill
 * class definitions remain reachable throughout the application lifetime.
 */
@Component
public class ExternalSkillLoader {

    private static final Logger LOGGER = Logger.getLogger(ExternalSkillLoader.class.getName());

    private final SkillRegistry skillRegistry;
    private final String externalJarUrls;

    // Keep classloaders alive to prevent unloading of skill classes.
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    private final List<URLClassLoader> activeClassLoaders = new ArrayList<>();

    public ExternalSkillLoader(SkillRegistry skillRegistry,
                               @Value("${mindos.skills.external-jars:}") String externalJarUrls) {
        this.skillRegistry = skillRegistry;
        this.externalJarUrls = externalJarUrls;
    }

    @PostConstruct
    public void loadOnStartup() {
        if (externalJarUrls == null || externalJarUrls.isBlank()) {
            return;
        }
        String[] urls = externalJarUrls.split(",");
        int total = 0;
        for (String rawUrl : urls) {
            String url = rawUrl.trim();
            if (!url.isBlank()) {
                total += loadFromJar(url);
            }
        }
        LOGGER.info("ExternalSkillLoader: loaded " + total + " skill(s) from " + urls.length + " JAR(s).");
    }

    /**
     * Downloads a JAR from the given URL, discovers all {@link Skill} implementations
     * via ServiceLoader, and registers them in the {@link SkillRegistry}.
     *
     * @param jarUrl HTTP/HTTPS/file URL pointing to the skill JAR
     * @return number of skills registered from this JAR
     */
    public int loadFromJar(String jarUrl) {
        LOGGER.info("ExternalSkillLoader: loading skills from JAR: " + jarUrl);
        try {
            Path tempJar = downloadJar(jarUrl);
            return registerSkillsFromJar(tempJar, jarUrl);
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING,
                    "ExternalSkillLoader: failed to load JAR from " + jarUrl + " — " + ex.getMessage(), ex);
            return 0;
        }
    }

    private Path downloadJar(String jarUrl) throws IOException {
        Path tempFile = Files.createTempFile("mindos-ext-skill-", ".jar");
        tempFile.toFile().deleteOnExit();
        try (InputStream in = URI.create(jarUrl).toURL().openStream()) {
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }
        LOGGER.info("ExternalSkillLoader: JAR downloaded to " + tempFile + " (source: " + jarUrl + ")");
        return tempFile;
    }

    private int registerSkillsFromJar(Path jarPath, String sourceUrl) throws IOException {
        URL jarFileUrl = jarPath.toUri().toURL();
        // Parent classloader = current context, so the Skill interface type is shared.
        URLClassLoader classLoader = new URLClassLoader(
                new URL[]{jarFileUrl}, Thread.currentThread().getContextClassLoader());

        // Keep the classloader alive so loaded skill instances remain usable.
        activeClassLoaders.add(classLoader);

        List<String> registered = new ArrayList<>();
        ServiceLoader<Skill> loader = ServiceLoader.load(Skill.class, classLoader);
        for (Skill skill : loader) {
            skillRegistry.register(skill);
            registered.add(skill.name());
            LOGGER.info("ExternalSkillLoader: registered skill '" + skill.name()
                    + "' [" + skill.getClass().getName() + "] from " + sourceUrl);
        }

        if (registered.isEmpty()) {
            LOGGER.warning("ExternalSkillLoader: no skills found in JAR " + sourceUrl
                    + ". Ensure META-INF/services/com.zhongbo.mindos.assistant.skill.Skill is present.");
        }
        return registered.size();
    }
}

