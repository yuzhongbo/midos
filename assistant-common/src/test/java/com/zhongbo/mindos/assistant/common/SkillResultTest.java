package com.zhongbo.mindos.assistant.common;

import org.testng.annotations.Test;

import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class SkillResultTest {

    @Test
    void shouldKeepLegacyConstructorCompatible() {
        SkillResult result = new SkillResult("todo.create", "已创建", true);

        assertEquals("todo.create", result.skillName());
        assertEquals("已创建", result.output());
        assertTrue(result.success());
        assertTrue(result.artifacts().isEmpty());
        assertTrue(result.metadata().isEmpty());
    }

    @Test
    void shouldSupportStructuredArtifactsAndMetadataWithoutMutatingOriginal() {
        SkillResult base = SkillResult.success("helper:web.lookup.detail", "detail-brief")
                .withArtifacts(Map.of("query", "MindOS 架构"))
                .withMetadata(Map.of("systemWorkflow", "skill-graph"));

        SkillResult relabeled = base.relabel("mcp.bravesearch.webSearch")
                .withArtifact("searchOutput", "search-results")
                .withMetadata("systemWorkflowRecipe", "web.lookup.detail");

        assertEquals("helper:web.lookup.detail", base.skillName());
        assertEquals("mcp.bravesearch.webSearch", relabeled.skillName());
        assertEquals("MindOS 架构", relabeled.artifactText("query"));
        assertEquals("search-results", relabeled.artifactText("searchOutput"));
        assertEquals("skill-graph", relabeled.metadataText("systemWorkflow"));
        assertEquals("web.lookup.detail", relabeled.metadataText("systemWorkflowRecipe"));
        assertTrue(base.artifact("searchOutput").isEmpty());
    }
}
