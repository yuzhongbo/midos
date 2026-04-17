package com.zhongbo.mindos.assistant.dispatcher.system;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.SkillExecutionGateway;
import com.zhongbo.mindos.assistant.skill.search.SearchResultDetailAugmentor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillCompositionServiceTest {

    @Test
    void shouldDelegateCodeAssistRecipeToCodeGenerate() {
        CapturingGateway gateway = new CapturingGateway(Map.of(
                "code.generate", SkillResult.success("code.generate", "代码已处理")
        ));
        SkillCompositionService service = new SkillCompositionService(gateway, new WebLookupDetailHelper(SearchResultDetailAugmentor.disabled()));

        SkillResult result = service.execute(
                "code.assist",
                "code.generate",
                Map.of("task", "修复登录接口空指针"),
                new SkillContext("u1", "帮我修复登录接口空指针", Map.of("language", "java"))
        );

        assertTrue(result.success());
        assertEquals("systemWorkflow=development", service.workflowReasonFor("code.assist", "code.generate"));
        assertEquals(1, gateway.invocations.size());
        Invocation invocation = gateway.invocations.get(0);
        assertEquals("code.generate", invocation.dsl.skill());
        assertEquals("修复登录接口空指针", invocation.dsl.input().get("task"));
        assertEquals("development", invocation.context.attributes().get("systemWorkflow"));
        assertEquals("code.assist.direct", invocation.context.attributes().get("systemWorkflowRecipe"));
        assertEquals("code.assist", invocation.context.attributes().get("systemWorkflowCapability"));
        assertEquals("code.generate", invocation.context.attributes().get("systemWorkflowExecutionTarget"));
    }

    @Test
    void shouldComposeWebLookupWithDetailHelper() {
        CapturingGateway gateway = new CapturingGateway(Map.of(
                "mcp.bravesearch.webSearch", SkillResult.success("mcp.bravesearch.webSearch", "search-results")
        ));
        RecordingDetailHelper helper = new RecordingDetailHelper(
                SkillResult.success(WebLookupDetailHelper.HELPER_TARGET, "detail-brief")
                        .withArtifacts(Map.of(
                                "detailApplied", Boolean.TRUE,
                                "detailTitle", "MindOS 架构详情",
                                "detailLink", "https://example.com/mindos",
                                "detailSummary", "这里是结构化 detail summary"
                        ))
        );
        SkillCompositionService service = new SkillCompositionService(gateway, helper);

        SkillResult result = service.execute(
                "web.lookup",
                "mcp.bravesearch.webSearch",
                Map.of("query", "MindOS 架构"),
                new SkillContext("u1", "帮我查 MindOS 架构", Map.of())
        );

        assertTrue(result.success());
        assertEquals("mcp.bravesearch.webSearch", result.skillName());
        assertEquals("detail-brief", result.output());
        assertEquals("MindOS 架构", result.artifactText("query"));
        assertEquals("search-results", result.artifactText("searchOutput"));
        assertEquals(Boolean.TRUE, result.artifacts().get("detailApplied"));
        assertEquals("MindOS 架构详情", result.artifactText("detailTitle"));
        assertEquals("https://example.com/mindos", result.artifactText("detailLink"));
        assertEquals("这里是结构化 detail summary", result.artifactText("detailSummary"));
        assertEquals("skill-graph", result.metadataText("systemWorkflow"));
        assertEquals("web.lookup.detail", result.metadataText("systemWorkflowRecipe"));
        assertEquals("systemWorkflow=skill-graph:web.lookup.detail", service.workflowReasonFor("web.lookup", "mcp.bravesearch.webSearch"));
        assertEquals(1, gateway.invocations.size());
        Invocation invocation = gateway.invocations.get(0);
        assertEquals("mcp.bravesearch.webSearch", invocation.dsl.skill());
        assertEquals(Boolean.TRUE, invocation.dsl.input().get("internalSkipDetailAugment"));
        assertEquals("skill-graph", invocation.context.attributes().get("systemWorkflow"));
        assertEquals("web.lookup.detail", invocation.context.attributes().get("systemWorkflowRecipe"));
        assertEquals("web.lookup", invocation.context.attributes().get("systemWorkflowCapability"));
        assertEquals("MindOS 架构", helper.lastQuery);
        assertEquals("search-results", helper.lastSearchOutput);
    }

    @Test
    void shouldReturnRootSearchResultWhenDetailHelperFails() {
        CapturingGateway gateway = new CapturingGateway(Map.of(
                "mcp.bravesearch.webSearch", SkillResult.success("mcp.bravesearch.webSearch", "search-results")
        ));
        RecordingDetailHelper helper = new RecordingDetailHelper(SkillResult.failure(WebLookupDetailHelper.HELPER_TARGET, "detail-failed"));
        SkillCompositionService service = new SkillCompositionService(gateway, helper);

        SkillResult result = service.execute(
                "web.lookup",
                "mcp.bravesearch.webSearch",
                Map.of("query", "MindOS 架构"),
                new SkillContext("u1", "帮我查 MindOS 架构", Map.of())
        );

        assertTrue(result.success());
        assertEquals("mcp.bravesearch.webSearch", result.skillName());
        assertEquals("search-results", result.output());
        assertEquals("MindOS 架构", result.artifactText("query"));
        assertEquals("search-results", result.artifactText("searchOutput"));
        assertEquals("skill-graph", result.metadataText("systemWorkflow"));
    }

    @Test
    void shouldComposeDocsLookupWithDetailHelper() {
        CapturingGateway gateway = new CapturingGateway(Map.of(
                "mcp.docs.searchDocs", SkillResult.success("mcp.docs.searchDocs", "docs-search-results")
        ));
        RecordingDetailHelper helper = new RecordingDetailHelper(
                SkillResult.success(WebLookupDetailHelper.HELPER_TARGET, "docs-detail-brief")
                        .withArtifacts(Map.of(
                                "detailApplied", Boolean.TRUE,
                                "detailTitle", "Spring RestClient Reference",
                                "detailLink", "https://docs.spring.io/restclient",
                                "detailSummary", "官方文档参考页"
                        ))
        );
        SkillCompositionService service = new SkillCompositionService(gateway, helper);

        SkillResult result = service.execute(
                "docs.lookup",
                "mcp.docs.searchDocs",
                Map.of("query", "Spring Boot RestClient 官方文档"),
                new SkillContext("u1", "帮我查 Spring Boot RestClient 官方文档", Map.of())
        );

        assertTrue(result.success());
        assertEquals("mcp.docs.searchDocs", result.skillName());
        assertEquals("docs-detail-brief", result.output());
        assertEquals("Spring Boot RestClient 官方文档", result.artifactText("query"));
        assertEquals("docs-search-results", result.artifactText("searchOutput"));
        assertEquals(Boolean.TRUE, result.artifacts().get("detailApplied"));
        assertEquals("Spring RestClient Reference", result.artifactText("detailTitle"));
        assertEquals("https://docs.spring.io/restclient", result.artifactText("detailLink"));
        assertEquals("官方文档参考页", result.artifactText("detailSummary"));
        assertEquals("skill-graph", result.metadataText("systemWorkflow"));
        assertEquals("docs.lookup.detail", result.metadataText("systemWorkflowRecipe"));
        assertEquals("docs.lookup", result.metadataText("systemWorkflowCapability"));
        assertEquals("systemWorkflow=skill-graph:docs.lookup.detail", service.workflowReasonFor("docs.lookup", "mcp.docs.searchDocs"));
        assertEquals(1, gateway.invocations.size());
        Invocation invocation = gateway.invocations.get(0);
        assertEquals("mcp.docs.searchDocs", invocation.dsl.skill());
        assertEquals(Boolean.TRUE, invocation.dsl.input().get("internalSkipDetailAugment"));
        assertEquals("skill-graph", invocation.context.attributes().get("systemWorkflow"));
        assertEquals("docs.lookup.detail", invocation.context.attributes().get("systemWorkflowRecipe"));
        assertEquals("docs.lookup", invocation.context.attributes().get("systemWorkflowCapability"));
        assertEquals("Spring Boot RestClient 官方文档", helper.lastQuery);
        assertEquals("docs-search-results", helper.lastSearchOutput);
    }

    private static final class CapturingGateway implements SkillExecutionGateway {
        private final Map<String, SkillResult> resultsBySkill;
        private final List<Invocation> invocations = new ArrayList<>();

        private CapturingGateway(Map<String, SkillResult> resultsBySkill) {
            this.resultsBySkill = resultsBySkill;
        }

        @Override
        public CompletableFuture<SkillResult> executeDslAsync(SkillDsl dsl, SkillContext context) {
            invocations.add(new Invocation(
                    new SkillDsl(dsl.skill(), new LinkedHashMap<>(dsl.input())),
                    new SkillContext(context.userId(), context.input(), new LinkedHashMap<>(context.attributes()))
            ));
            return CompletableFuture.completedFuture(
                    resultsBySkill.getOrDefault(dsl.skill(), SkillResult.failure(dsl.skill(), "missing test result"))
            );
        }
    }

    private static final class RecordingDetailHelper extends WebLookupDetailHelper {
        private final SkillResult result;
        private String lastQuery = "";
        private String lastSearchOutput = "";

        private RecordingDetailHelper(SkillResult result) {
            super(SearchResultDetailAugmentor.disabled());
            this.result = result;
        }

        @Override
        public SkillResult enrich(String query, String searchOutput) {
            this.lastQuery = query == null ? "" : query;
            this.lastSearchOutput = searchOutput == null ? "" : searchOutput;
            return result;
        }
    }

    private record Invocation(SkillDsl dsl, SkillContext context) {
    }
}
