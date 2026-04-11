package com.zhongbo.mindos.assistant.skill.learning;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultToolGeneratorTest {

    @Test
    void shouldGenerateWebScraperSkillForScrapingRequests() {
        DefaultToolGenerator generator = new DefaultToolGenerator("com.zhongbo.mindos.assistant.skill.generated");
        ToolGenerationResult result = generator.generate(new ToolGenerationRequest(
                "u1",
                "抓取某网站数据",
                null,
                Map.of()
        ));

        assertEquals(ToolGenerationKind.WEB_SCRAPER, result.kind());
        assertTrue(result.skillName().startsWith("generated.web.scrape."));
        assertTrue(result.routingKeywords().contains("抓取"));
        assertTrue(result.sourceCode().contains("HttpClient"));
        assertTrue(result.sourceCode().contains("网页抓取完成"));
    }
}
