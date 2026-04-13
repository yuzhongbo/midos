package com.zhongbo.mindos.assistant.skill.cloudapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.skill.DefaultSkillCatalog;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CloudApiSkillLoaderTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void loadsValidDefinitionsFromDirectory(@TempDir Path dir) throws IOException {
        writeDefinition(dir, "weather.json", Map.of(
                "name", "weather.query",
                "description", "Weather skill",
                "keywords", List.of("天气", "weather"),
                "url", "https://api.example.com/weather",
                "method", "GET"
        ));
        writeDefinition(dir, "translate.json", Map.of(
                "name", "translate.text",
                "description", "Translate skill",
                "keywords", List.of("翻译"),
                "url", "https://api.example.com/translate",
                "method", "POST"
        ));

        SkillRegistry registry = new SkillRegistry(List.of());
        CloudApiSkillLoader loader = new CloudApiSkillLoader(registry, dir.toString());

        int loaded = loader.reload();

        assertEquals(2, loaded);
        assertTrue(registry.getSkill("weather.query").isPresent());
        assertTrue(registry.getSkill("translate.text").isPresent());
    }

    @Test
    void skipsDefinitionsWithMissingNameOrUrl(@TempDir Path dir) throws IOException {
        writeDefinition(dir, "no-name.json", Map.of(
                "description", "No name",
                "url", "https://api.example.com/no-name"
        ));
        writeDefinition(dir, "no-url.json", Map.of(
                "name", "no.url.skill",
                "description", "No URL"
        ));
        writeDefinition(dir, "valid.json", Map.of(
                "name", "valid.skill",
                "description", "Valid",
                "url", "https://api.example.com/valid"
        ));

        SkillRegistry registry = new SkillRegistry(List.of());
        CloudApiSkillLoader loader = new CloudApiSkillLoader(registry, dir.toString());

        int loaded = loader.reload();

        assertEquals(1, loaded);
        assertTrue(registry.getSkill("valid.skill").isPresent());
        assertFalse(registry.getSkill("no.url.skill").isPresent());
    }

    @Test
    void returnsZeroWhenConfigDirNotSet() {
        SkillRegistry registry = new SkillRegistry(List.of());
        CloudApiSkillLoader loader = new CloudApiSkillLoader(registry, "");

        assertEquals(0, loader.reload());
    }

    @Test
    void returnsZeroWhenDirectoryDoesNotExist() {
        SkillRegistry registry = new SkillRegistry(List.of());
        CloudApiSkillLoader loader = new CloudApiSkillLoader(registry, "/nonexistent/path/cloud-skills");

        assertEquals(0, loader.reload());
    }

    @Test
    void overwritesExistingSkillOnReload(@TempDir Path dir) throws IOException {
        writeDefinition(dir, "skill.json", Map.of(
                "name", "my.skill",
                "description", "v1",
                "url", "https://api.example.com/v1"
        ));

        SkillRegistry registry = new SkillRegistry(List.of());
        CloudApiSkillLoader loader = new CloudApiSkillLoader(registry, dir.toString());
        loader.reload();

        Skill v1 = registry.getSkill("my.skill").orElseThrow();
        assertEquals("v1", v1.description());

        // Update the definition
        writeDefinition(dir, "skill.json", Map.of(
                "name", "my.skill",
                "description", "v2",
                "url", "https://api.example.com/v2"
        ));
        loader.reload();

        Skill v2 = registry.getSkill("my.skill").orElseThrow();
        assertEquals("v2", v2.description());
    }

    @Test
    void ignoresNonJsonFiles(@TempDir Path dir) throws IOException {
        writeDefinition(dir, "skill.json", Map.of(
                "name", "json.skill",
                "description", "JSON skill",
                "url", "https://api.example.com/json"
        ));
        Files.writeString(dir.resolve("not-a-skill.txt"), "ignored");

        SkillRegistry registry = new SkillRegistry(List.of());
        CloudApiSkillLoader loader = new CloudApiSkillLoader(registry, dir.toString());

        int loaded = loader.reload();

        assertEquals(1, loaded);
        assertTrue(registry.getSkill("json.skill").isPresent());
    }

    @Test
    void registeredSkillHasCorrectSemanticKeywords(@TempDir Path dir) throws IOException {
        writeDefinition(dir, "weather.json", Map.of(
                "name", "weather.query",
                "description", "Weather",
                "keywords", List.of("天气", "weather", "温度"),
                "url", "https://api.example.com/weather"
        ));

        SkillRegistry registry = new SkillRegistry(List.of());
        CloudApiSkillLoader loader = new CloudApiSkillLoader(registry, dir.toString());
        loader.reload();
        DefaultSkillCatalog catalog = new DefaultSkillCatalog(registry, null, new com.zhongbo.mindos.assistant.skill.SkillRoutingProperties());

        assertEquals("weather.query", catalog.detectSkillName("查一下今天的天气").orElse(""));
        assertEquals("weather.query", catalog.detectSkillName("check the weather").orElse(""));
        assertEquals("weather.query", catalog.detectSkillName("当前温度是多少").orElse(""));
        assertTrue(catalog.detectSkillName("翻译这句话").isEmpty());
    }

    @Test
    void getConfigDirReturnsConfiguredPath(@TempDir Path dir) {
        SkillRegistry registry = new SkillRegistry(List.of());
        CloudApiSkillLoader loader = new CloudApiSkillLoader(registry, dir.toString());

        assertEquals(dir.toString(), loader.getConfigDir());
    }

    private void writeDefinition(Path dir, String filename, Map<String, Object> content) throws IOException {
        Files.writeString(dir.resolve(filename), objectMapper.writeValueAsString(content));
    }
}
