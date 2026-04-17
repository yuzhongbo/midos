package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillCostTelemetry;
import com.zhongbo.mindos.assistant.common.dto.CostModel;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.system.SkillRecipeRegistry;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.graph.MemoryEdge;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemory;
import com.zhongbo.mindos.assistant.memory.graph.MemoryNode;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import com.zhongbo.mindos.assistant.skill.SkillGovernanceValidator;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import com.zhongbo.mindos.assistant.skill.learning.DefaultToolGenerator;
import com.zhongbo.mindos.assistant.skill.learning.GeneratedSkillArtifactStore;
import com.zhongbo.mindos.assistant.skill.learning.GeneratedSkillCompiler;
import com.zhongbo.mindos.assistant.skill.learning.GeneratedSkillReview;
import com.zhongbo.mindos.assistant.skill.learning.ToolLearningService;
import com.zhongbo.mindos.assistant.skill.learning.ToolGenerationRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HermesEvolutionLoopServiceTest {

    @Test
    void shouldDeployRuntimePolicySnapshotFromObservedPerformance() {
        HermesDecisionPolicy decisionPolicy = new HermesDecisionPolicy(null, null);
        SkillRecipeRegistry recipeRegistry = new SkillRecipeRegistry();
        HermesEvolutionLoopService service = new HermesEvolutionLoopService(
                new TestDispatcherMemoryFacade(
                        List.of(
                                new SkillUsageStats("todo.create", 4, 4, 0),
                                new SkillUsageStats("file.search", 4, 1, 3)
                        ),
                        List.of()
                ),
                new FixedSkillCostTelemetry(Map.of(
                        "todo.create", new CostModel(0.20d, 0.25d, 0.95d),
                        "file.search", new CostModel(0.85d, 0.90d, 0.30d)
                )),
                decisionPolicy,
                recipeRegistry,
                5L,
                1L,
                6,
                0.40d
        );

        HermesEvolutionLoopService.EvolutionReport report = service.evolveUser("u1");
        HermesRuntimePolicySnapshot snapshot = decisionPolicy.deployedRuntimePolicy("u1").orElseThrow();

        assertTrue(report.policyUpdated());
        assertTrue(snapshot.memorySuccessBoostWeight() > 0.20d);
        assertTrue(snapshot.skillScoreAdjustments().get("todo.create") > 0.0d);
        assertTrue(snapshot.skillScoreAdjustments().get("file.search") < 0.0d);
        assertTrue(snapshot.strategySummary().contains("self-reconstruction"));
        assertTrue(snapshot.reconstructionActions().stream().anyMatch(action -> action.contains("boost todo.create")));
        assertTrue(snapshot.adjustmentReason(new HermesSkillIdentity("todo.create", "todo.create", "todo.create"))
                .contains("lower-cost higher-success"));
    }

    @Test
    void shouldDeployUserScopedRecipeFallbackWhenDetailYieldIsLow() {
        HermesDecisionPolicy decisionPolicy = new HermesDecisionPolicy(null, null);
        SkillRecipeRegistry recipeRegistry = new SkillRecipeRegistry();
        HermesEvolutionLoopService service = new HermesEvolutionLoopService(
                new TestDispatcherMemoryFacade(
                        List.of(new SkillUsageStats("mcp.docs.searchDocs", 3, 2, 1)),
                        List.of(
                                executionNode("docs-1", "docs.lookup.detail", ""),
                                executionNode("docs-2", "docs.lookup.detail", ""),
                                executionNode("docs-3", "docs.lookup.detail", "")
                        )
                ),
                new FixedSkillCostTelemetry(Map.of(
                        "mcp.docs.searchDocs", new CostModel(0.40d, 0.45d, 0.66d)
                )),
                decisionPolicy,
                recipeRegistry,
                5L,
                1L,
                6,
                0.40d
        );

        HermesEvolutionLoopService.EvolutionReport report = service.evolveUser("u1");

        assertTrue(report.recipesUpdated());
        assertEquals(1, recipeRegistry.resolve("u1", "docs.lookup", "mcp.docs.searchDocs").orElseThrow().steps().size());
        assertTrue(recipeRegistry.resolve("u1", "docs.lookup", "mcp.docs.searchDocs")
                .orElseThrow()
                .workflowReason()
                .endsWith(":degraded"));
        assertEquals(2, recipeRegistry.resolve("docs.lookup", "mcp.docs.searchDocs").orElseThrow().steps().size());
    }

    @Test
    void shouldMaterializePreferredSkillEdgeForStableTaskPattern() {
        HermesDecisionPolicy decisionPolicy = new HermesDecisionPolicy(null, null);
        SkillRecipeRegistry recipeRegistry = new SkillRecipeRegistry();
        TestDispatcherMemoryFacade facade = new TestDispatcherMemoryFacade(
                List.of(new SkillUsageStats("todo.create", 3, 3, 0)),
                List.of(
                        new MemoryNode("skill-todo", "hermes.skill", Map.of("skillName", "todo.create", "name", "todo.create"), Instant.now(), Instant.now()),
                        new MemoryNode("task-report", "hermes.task", Map.of("task", "提交周报", "skillName", "todo.create", "name", "提交周报"), Instant.now(), Instant.now())
                )
        );
        RecordingMemoryCommandService commandService = new RecordingMemoryCommandService(facade);
        HermesEvolutionLoopService service = new HermesEvolutionLoopService(
                facade,
                commandService,
                new FixedSkillCostTelemetry(Map.of(
                        "todo.create", new CostModel(0.20d, 0.20d, 0.95d)
                )),
                decisionPolicy,
                recipeRegistry,
                5L,
                1L,
                6,
                0.40d
        );

        HermesEvolutionLoopService.EvolutionReport report = service.evolveUser("u1");

        assertTrue(report.reasons().stream().anyMatch(reason -> reason.contains("materialized-edges=1")));
        assertEquals(1, commandService.edges.size());
        assertEquals("prefers-skill", commandService.edges.get(0).relation());
        assertEquals("task-report", commandService.edges.get(0).from());
        assertEquals("skill-todo", commandService.edges.get(0).to());
    }

    @Test
    void shouldProduceReviewedDraftForWeakGeneratedSkill() {
        HermesDecisionPolicy decisionPolicy = new HermesDecisionPolicy(null, null);
        SkillRecipeRegistry recipeRegistry = new SkillRecipeRegistry();
        GeneratedSkillArtifactStore artifactStore = new GeneratedSkillArtifactStore();
        ToolLearningService learningService = new ToolLearningService(
                new DefaultToolGenerator("com.zhongbo.mindos.assistant.skill.generated"),
                new GeneratedSkillCompiler(),
                new SkillRegistry(List.of()),
                new SkillGovernanceValidator(),
                artifactStore
        );
        GeneratedSkillReview initialDraft = learningService.reviewDraft(new ToolGenerationRequest(
                "u1",
                "抓取某网站数据",
                "web.scrape",
                Map.of()
        ));
        HermesEvolutionLoopService service = new HermesEvolutionLoopService(
                new TestDispatcherMemoryFacade(
                        List.of(new SkillUsageStats(initialDraft.runtimeSkillName(), 3, 1, 2)),
                        List.of()
                ),
                null,
                new FixedSkillCostTelemetry(Map.of(
                        initialDraft.runtimeSkillName(), new CostModel(0.70d, 0.80d, 0.33d)
                )),
                decisionPolicy,
                recipeRegistry,
                learningService,
                5L,
                1L,
                6,
                0.40d
        );

        HermesEvolutionLoopService.EvolutionReport report = service.evolveUser("u1");

        assertTrue(report.reasons().stream().anyMatch(reason -> reason.contains("skill-evolution-drafts=1")));
        assertTrue(artifactStore.knownSkillNames().size() >= 2);
    }

    private static MemoryNode executionNode(String id, String workflowRecipe, String detailTitle) {
        return new MemoryNode(
                id,
                "hermes.execution",
                Map.of(
                        "name", workflowRecipe,
                        "workflowRecipe", workflowRecipe,
                        "detailTitle", detailTitle
                ),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z")
        );
    }

    private static final class TestDispatcherMemoryFacade extends DispatcherMemoryFacade {
        private final List<SkillUsageStats> usageStats;
        private final List<MemoryNode> graphNodes;

        private TestDispatcherMemoryFacade(List<SkillUsageStats> usageStats, List<MemoryNode> graphNodes) {
            super((MemoryGateway) null, new GraphMemory(), null);
            this.usageStats = usageStats == null ? List.of() : List.copyOf(usageStats);
            this.graphNodes = graphNodes == null ? List.of() : List.copyOf(graphNodes);
        }

        @Override
        public List<SkillUsageStats> getSkillUsageStats(String userId) {
            return usageStats;
        }

        @Override
        public List<MemoryNode> searchGraphNodes(String userId, String keyword, int limit) {
            return graphNodes;
        }

        @Override
        public boolean hasGraphMemory() {
            return true;
        }
    }

    private static final class FixedSkillCostTelemetry implements SkillCostTelemetry {
        private final Map<String, CostModel> models;

        private FixedSkillCostTelemetry(Map<String, CostModel> models) {
            this.models = models == null ? Map.of() : Map.copyOf(models);
        }

        @Override
        public void record(String userId, String skillName, long latencyMs, int totalTokensEstimate, boolean success) {
        }

        @Override
        public Map<String, CostModel> costModels(String userId) {
            return models;
        }

        @Override
        public Map<String, Double> averageLatencies(String userId) {
            return Map.of();
        }
    }

    private static final class RecordingMemoryCommandService extends DispatcherMemoryCommandService {
        private final List<MemoryEdge> edges = new ArrayList<>();

        private RecordingMemoryCommandService(DispatcherMemoryFacade dispatcherMemoryFacade) {
            super(dispatcherMemoryFacade, null);
        }

        @Override
        public MemoryEdge linkGraph(String userId,
                                    String fromNodeId,
                                    String relation,
                                    String toNodeId,
                                    double weight,
                                    Map<String, Object> metadata) {
            MemoryEdge edge = new MemoryEdge(fromNodeId, toNodeId, relation, weight, metadata, Instant.now());
            edges.add(edge);
            return edge;
        }
    }
}
