package com.zhongbo.mindos.assistant.skill.learning;

import com.sun.net.httpserver.HttpServer;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
