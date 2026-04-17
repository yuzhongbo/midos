package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.dto.ActiveGoalSnapshotDto;
import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.common.dto.TaskThreadSnapshotDto;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.MemoryFacade;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemory;
import com.zhongbo.mindos.assistant.memory.graph.MemoryNode;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import com.zhongbo.mindos.assistant.skill.SkillCandidate;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalyzer;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.InMemoryParamSchemaRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HermesDecisionContextFactoryTest {

    @Test
    void shouldUseCompactDecisionMemoryForSemanticAnalysis() {
        PromptMemoryContextDto promptMemoryContext = new PromptMemoryContextDto(
                "user: 我们刚才在看接口文档\nassistant: 好的，我继续",
                "- [fact] 文档检索优先走 docs.lookup\n"
                        + "- [working] 当前事项：继续看接口文档；下一步：确认认证流程\n"
                        + "- [assistant-context] 上下文明确时直接推进",
                "- skill=docs.lookup, successRate=0.91",
                Map.of("tone", "direct"),
                List.of(),
                new TaskThreadSnapshotDto(
                        "继续看接口文档",
                        "进行中",
                        "确认认证流程",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "当前事项 继续看接口文档；下一步 确认认证流程"
                ),
                Map.of("clarifyStyle", "minimal")
        );
        DispatcherMemoryFacade facade = new DispatcherMemoryFacade(
                new MemoryFacade(new TestMemoryManager(
                        List.of(
                                new ConversationTurn("user", "旧对话一", Instant.parse("2024-01-01T00:00:00Z")),
                                new ConversationTurn("assistant", "旧对话二", Instant.parse("2024-01-01T00:01:00Z"))
                        ),
                        List.of(new SemanticMemoryEntry("知识A", List.of(0.2), Instant.parse("2024-01-01T00:06:00Z"))),
                        List.of(new SkillUsageStats("docs.lookup", 10, 9, 1)),
                        promptMemoryContext
                )),
                4,
                2,
                2,
                2,
                3
        );
        AtomicReference<String> capturedDecisionMemory = new AtomicReference<>("");
        SemanticAnalyzer semanticAnalyzer = (userId, userInput, memoryContext, profileContext, availableSkillSummaries) -> {
            capturedDecisionMemory.set(memoryContext);
            return SemanticAnalysisResult.empty();
        };
        HermesDecisionContextFactory factory = new HermesDecisionContextFactory(
                facade,
                null,
                semanticAnalyzer,
                null,
                new DispatchHeuristicsSupport(null, false, List.of(), false, true, Set.of("新闻", "天气")),
                DispatcherAnswerMode.BALANCED,
                1600,
                900,
                true,
                280,
                stats -> {
                }
        );

        HermesDecisionContext context = factory.create("u1", "继续看文档", Map.of("role", "assistant"));

        assertTrue(capturedDecisionMemory.get().contains("Active task:"));
        assertTrue(capturedDecisionMemory.get().contains("Relevant facts:"));
        assertFalse(capturedDecisionMemory.get().contains("User skill habits:"));
        assertFalse(capturedDecisionMemory.get().contains("Relevant knowledge:"));
        assertTrue(context.memoryContext().contains("User skill habits:"));
        assertTrue(context.memoryContext().contains("Relevant knowledge:"));
    }

    @Test
    void shouldIncludeGraphSkillScoresInDecisionContext() {
        GraphMemory graphMemory = new GraphMemory();
        Instant recordedAt = Instant.parse("2024-01-01T00:00:00Z");
        graphMemory.upsertNode("u1", new MemoryNode(
                "skill:todo.create",
                "hermes.skill",
                Map.of(
                        "name", "todo.create",
                        "skillName", "todo.create",
                        "lastTask", "整理任务"
                ),
                recordedAt,
                recordedAt
        ));
        DispatcherMemoryFacade facade = new DispatcherMemoryFacade(
                new MemoryFacade(new TestMemoryManager(
                        List.of(),
                        List.of(),
                        List.of(),
                        new PromptMemoryContextDto("recent", "semantic", "procedural", Map.of(), List.of())
                )),
                (MemoryGateway) null,
                graphMemory,
                graphMemory,
                null
        );
        InMemoryParamSchemaRegistry schemaRegistry = new InMemoryParamSchemaRegistry();
        schemaRegistry.registerDefaults();
        HermesDecisionContextFactory factory = new HermesDecisionContextFactory(
                facade,
                null,
                (userId, userInput, memoryContext, profileContext, availableSkillSummaries) -> SemanticAnalysisResult.empty(),
                new HermesToolSchemaCatalog(
                        new TestSkillCatalog(List.of(
                                new SkillDescriptor("todo.create", "Create todo item", List.of("待办", "整理任务")),
                                new SkillDescriptor("file.search", "Search local files", List.of("找文件", "搜索文件"))
                        )),
                        schemaRegistry
                ),
                new DispatchHeuristicsSupport(null, false, List.of(), false, true, Set.of("新闻", "天气")),
                DispatcherAnswerMode.BALANCED,
                1600,
                900,
                true,
                280,
                stats -> {
                }
        );

        HermesDecisionContext context = factory.create("u1", "整理任务", Map.of());

        assertTrue(context.graphSkillScores().getOrDefault("todo.create", 0.0d) > 0.0d);
        assertTrue(context.graphSkillScores().getOrDefault("todo.create", 0.0d)
                > context.graphSkillScores().getOrDefault("file.search", 0.0d));
    }

    @Test
    void shouldBuildGraphContinuationHintForShortFollowUp() {
        GraphMemory graphMemory = new GraphMemory();
        Instant recordedAt = Instant.parse("2024-01-01T00:00:00Z");
        graphMemory.upsertNode("u1", new MemoryNode(
                "task:weekly-report",
                "hermes.task",
                Map.of(
                        "name", "提交周报",
                        "task", "提交周报",
                        "project", "运营周报",
                        "nextAction", "整理风险说明",
                        "decisionTarget", "task.manage",
                        "executionTarget", "todo.create",
                        "canonicalSkill", "todo.create",
                        "skillName", "todo.create"
                ),
                recordedAt,
                recordedAt
        ));
        PromptMemoryContextDto promptMemoryContext = new PromptMemoryContextDto(
                "",
                "",
                "",
                Map.of(),
                List.of(),
                new TaskThreadSnapshotDto(
                        "提交周报",
                        "进行中",
                        "整理风险说明",
                        "运营周报",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "当前事项 提交周报；下一步 整理风险说明"
                ),
                Map.of()
        );
        DispatcherMemoryFacade facade = new DispatcherMemoryFacade(
                new MemoryFacade(new TestMemoryManager(List.of(), List.of(), List.of(), promptMemoryContext)),
                (MemoryGateway) null,
                graphMemory,
                graphMemory,
                null
        );
        HermesDecisionContextFactory factory = new HermesDecisionContextFactory(
                facade,
                null,
                (userId, userInput, memoryContext, profileContext, availableSkillSummaries) -> SemanticAnalysisResult.empty(),
                null,
                new DispatchHeuristicsSupport(null, false, List.of(), false, true, Set.of("新闻", "天气")),
                DispatcherAnswerMode.BALANCED,
                1600,
                900,
                true,
                280,
                stats -> {
                }
        );

        HermesDecisionContext context = factory.create("u1", "开始吧", Map.of());

        assertEquals("提交周报", context.graphContinuationHint().get("task"));
        assertEquals("task.manage", context.graphContinuationHint().get("decisionTarget"));
        assertEquals("todo.create", context.graphContinuationHint().get("executionTarget"));
        assertEquals("todo.create", context.graphContinuationHint().get("canonicalSkill"));
    }

    @Test
    void shouldCarryActiveGoalIntoDecisionMemoryAndRuntimeContexts() {
        PromptMemoryContextDto promptMemoryContext = new PromptMemoryContextDto(
                "",
                "- [fact] 当前在推进 Hermes stage5 升级",
                "",
                Map.of(),
                List.of(),
                TaskThreadSnapshotDto.empty(),
                new ActiveGoalSnapshotDto(
                        "goal-1",
                        "升级 Hermes",
                        "完成 goal persistence",
                        "ACTIVE",
                        "Goal context 全链路生效",
                        "2026-05-01T00:00:00Z",
                        "2026-04-20T00:00:00Z",
                        40,
                        "长期目标 升级 Hermes；目标说明 完成 goal persistence；进度 40%"
                ),
                Map.of()
        );
        DispatcherMemoryFacade facade = new DispatcherMemoryFacade(
                new MemoryFacade(new TestMemoryManager(List.of(), List.of(), List.of(), promptMemoryContext)),
                4,
                2,
                2,
                2,
                3
        );
        HermesDecisionContextFactory factory = new HermesDecisionContextFactory(
                facade,
                null,
                (userId, userInput, memoryContext, profileContext, availableSkillSummaries) -> SemanticAnalysisResult.empty(),
                null,
                new DispatchHeuristicsSupport(null, false, List.of(), false, true, Set.of("新闻", "天气")),
                DispatcherAnswerMode.BALANCED,
                1600,
                900,
                true,
                280,
                stats -> {
                }
        );

        HermesDecisionContext context = factory.create("u-goal", "继续推进", Map.of());

        assertTrue(context.memoryContext().contains("Active long-term goal:"));
        assertEquals("升级 Hermes", context.skillContext().attributes().get("activeGoal"));
        assertEquals("ACTIVE", context.skillContext().attributes().get("activeGoalStatus"));
        assertTrue(context.llmContext().get("activeGoal") instanceof Map);
        Map<?, ?> activeGoal = (Map<?, ?>) context.llmContext().get("activeGoal");
        assertEquals("升级 Hermes", activeGoal.get("activeGoal"));
        assertEquals(40, activeGoal.get("activeGoalProgressPercent"));
    }

    private static final class TestMemoryManager extends MemoryManager {
        private final List<ConversationTurn> recentConversation;
        private final List<SemanticMemoryEntry> knowledge;
        private final List<SkillUsageStats> usageStats;
        private final PromptMemoryContextDto promptMemoryContext;

        private TestMemoryManager(List<ConversationTurn> recentConversation,
                                  List<SemanticMemoryEntry> knowledge,
                                  List<SkillUsageStats> usageStats,
                                  PromptMemoryContextDto promptMemoryContext) {
            super(null, null, null, null, null, null, null, null, null, null, null, false, 4, 2, 3, 16);
            this.recentConversation = recentConversation;
            this.knowledge = knowledge;
            this.usageStats = usageStats;
            this.promptMemoryContext = promptMemoryContext;
        }

        @Override
        public List<ConversationTurn> getRecentConversation(String userId, int limit) {
            return recentConversation;
        }

        @Override
        public List<SemanticMemoryEntry> searchKnowledge(String userId, String query, int limit, String preferredBucket) {
            return knowledge;
        }

        @Override
        public List<SkillUsageStats> getSkillUsageStats(String userId) {
            return usageStats;
        }

        @Override
        public PromptMemoryContextDto buildPromptMemoryContext(String userId,
                                                               String userInput,
                                                               int maxChars,
                                                               Map<String, Object> profileContext) {
            return promptMemoryContext;
        }
    }

    private static final class TestSkillCatalog implements SkillCatalogFacade {
        private final List<SkillDescriptor> descriptors;

        private TestSkillCatalog(List<SkillDescriptor> descriptors) {
            this.descriptors = descriptors == null ? List.of() : List.copyOf(descriptors);
        }

        @Override
        public Optional<String> detectSkillName(String input) {
            return Optional.empty();
        }

        @Override
        public List<SkillCandidate> detectSkillCandidates(String input, int limit) {
            return List.of();
        }

        @Override
        public Optional<SkillDescriptor> describeSkill(String skillName) {
            return descriptors.stream().filter(descriptor -> descriptor.name().equals(skillName)).findFirst();
        }

        @Override
        public List<SkillDescriptor> listSkillDescriptors() {
            return descriptors;
        }

        @Override
        public String describeAvailableSkills() {
            return descriptors.stream().map(SkillDescriptor::name).reduce((left, right) -> left + ", " + right).orElse("");
        }

        @Override
        public List<String> listAvailableSkillSummaries() {
            return descriptors.stream()
                    .map(descriptor -> descriptor.name() + " - " + descriptor.description())
                    .toList();
        }
    }
}
