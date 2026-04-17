package com.zhongbo.mindos.assistant.skill.learning;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.memory.MemoryStateStore;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolLearningServiceTest {

    @Test
    void shouldCompileRegisterAndRunGeneratedScraperSkill() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/page", exchange -> {
            byte[] response = """
                    <html>
                      <head><title>MindOS Demo</title></head>
                      <body>
                        <a href="https://example.com/a">A</a>
                        <p>Hello generated skill</p>
                      </body>
                    </html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            SkillRegistry skillRegistry = new SkillRegistry(List.of());
            ToolLearningService service = new ToolLearningService(
                    new DefaultToolGenerator("com.zhongbo.mindos.assistant.skill.generated"),
                    new GeneratedSkillCompiler(),
                    skillRegistry
            );

            GeneratedSkillDeployment deployment = service.generateAndRegister(new ToolGenerationRequest(
                    "u1",
                    "抓取某网站数据",
                    "web.scrape",
                    Map.of()
            ));

            assertTrue(skillRegistry.containsSkill(deployment.registeredSkillName()));

            Skill generatedSkill = skillRegistry.getSkill(deployment.registeredSkillName()).orElseThrow();
            SkillResult result = generatedSkill.run(new SkillContext(
                    "u1",
                    "帮我抓取这个网页",
                    Map.of("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/page")
            ));

            assertTrue(result.success());
            assertTrue(result.output().contains("MindOS Demo"));
            assertTrue(result.output().contains("https://example.com/a"));
            assertTrue(skillRegistry.unregister(deployment.registeredSkillName()));
            assertFalse(skillRegistry.containsSkill(deployment.registeredSkillName()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldReviewDraftWithoutRegisteringIt() {
        SkillRegistry skillRegistry = new SkillRegistry(List.of());
        GeneratedSkillArtifactStore artifactStore = new GeneratedSkillArtifactStore();
        ToolLearningService service = new ToolLearningService(
                new DefaultToolGenerator("com.zhongbo.mindos.assistant.skill.generated"),
                new GeneratedSkillCompiler(),
                skillRegistry,
                new com.zhongbo.mindos.assistant.skill.SkillGovernanceValidator(),
                artifactStore
        );

        GeneratedSkillReview review = service.reviewDraft(new ToolGenerationRequest(
                "u1",
                "抓取某网站数据",
                "web.scrape",
                Map.of()
        ));

        assertTrue(review.valid());
        assertTrue(review.runtimeSkillName().startsWith("generated.web.scrape."));
        assertEquals(List.of("generated-artifact-validated", "compiled", "runtime-skill-validated"), review.checks());
        assertFalse(skillRegistry.containsSkill(review.runtimeSkillName()));
        assertTrue(artifactStore.find(review.artifact().skillName()).isPresent());
    }

    @Test
    void shouldCreateReviewedEvolutionDraftFromStoredArtifact() {
        SkillRegistry skillRegistry = new SkillRegistry(List.of());
        GeneratedSkillArtifactStore artifactStore = new GeneratedSkillArtifactStore();
        ToolLearningService service = new ToolLearningService(
                new DefaultToolGenerator("com.zhongbo.mindos.assistant.skill.generated"),
                new GeneratedSkillCompiler(),
                skillRegistry,
                new com.zhongbo.mindos.assistant.skill.SkillGovernanceValidator(),
                artifactStore
        );

        GeneratedSkillReview initial = service.reviewDraft(new ToolGenerationRequest(
                "u1",
                "抓取某网站数据",
                "web.scrape",
                Map.of()
        ));

        GeneratedSkillReview evolved = service.evolveReviewedDraft(
                "u1",
                initial.artifact().skillName(),
                "提升稳定性和错误提示"
        );

        assertTrue(evolved.valid());
        assertTrue(evolved.runtimeSkillName().startsWith("generated.web.scrape."));
        assertTrue(evolved.runtimeSkillName().contains("."));
        assertTrue(evolved.artifact().metadata().containsKey("parentSkill"));
        assertEquals(initial.artifact().skillName(), evolved.artifact().metadata().get("parentSkill"));
        assertTrue(artifactStore.find(evolved.artifact().skillName()).isPresent());
        assertTrue(artifactStore.knownSkillNames().containsAll(Set.of(
                initial.artifact().skillName(),
                evolved.artifact().skillName()
        )));
    }

    @Test
    void shouldRestorePersistedGeneratedArtifacts() {
        MapBackedMemoryStateStore stateStore = new MapBackedMemoryStateStore();
        GeneratedSkillArtifactStore first = new GeneratedSkillArtifactStore(stateStore);
        ToolLearningService service = new ToolLearningService(
                new DefaultToolGenerator("com.zhongbo.mindos.assistant.skill.generated"),
                new GeneratedSkillCompiler(),
                new SkillRegistry(List.of()),
                new com.zhongbo.mindos.assistant.skill.SkillGovernanceValidator(),
                first
        );
        GeneratedSkillReview review = service.reviewDraft(new ToolGenerationRequest(
                "u1",
                "抓取某网站数据",
                "web.scrape",
                Map.of()
        ));

        GeneratedSkillArtifactStore restored = new GeneratedSkillArtifactStore(stateStore);

        assertTrue(restored.find(review.artifact().skillName()).isPresent());
        assertTrue(restored.knownSkillNames().contains(review.artifact().skillName()));
    }

    private static final class MapBackedMemoryStateStore implements MemoryStateStore {
        private final Map<String, Object> values = new LinkedHashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T readState(String fileName, TypeReference<T> typeReference, java.util.function.Supplier<T> fallbackSupplier) {
            Object value = values.get(fileName);
            return value == null ? fallbackSupplier.get() : (T) value;
        }

        @Override
        public void writeState(String fileName, Object value) {
            values.put(fileName, value);
        }
    }
}
