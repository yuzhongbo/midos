package com.zhongbo.mindos.assistant.dispatcher.memory;

import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.dto.TaskThreadSnapshotDto;
import com.zhongbo.mindos.assistant.memory.MemoryFacade;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionPlan;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionStep;
import com.zhongbo.mindos.assistant.memory.model.MemoryStyleProfile;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatcherMemoryFacadeTest {

    @Test
    void shouldBuildChatHistoryAndDelegatePromptMemoryContext() {
        TestMemoryManager memoryManager = new TestMemoryManager(
                List.of(
                        new ConversationTurn("user", "第一条", Instant.parse("2024-01-01T00:00:00Z")),
                        new ConversationTurn("assistant", "第二条", Instant.parse("2024-01-01T00:01:00Z"))
                ),
                List.of(),
                List.of(),
                List.of(),
                null,
                new PromptMemoryContextDto("recent", "semantic", "procedural", Map.of("tone", "direct"), List.of())
        );
        DispatcherMemoryFacade facade = new DispatcherMemoryFacade(new MemoryFacade(memoryManager), 4, 2, 2, 2, 3);

        List<Map<String, Object>> history = facade.buildChatHistory("u1");
        PromptMemoryContextDto actual = facade.buildPromptMemoryContext("u1", "帮我回顾", 128, Map.of("multiAgent", true));

        assertEquals(2, history.size());
        assertEquals("user", history.get(0).get("role"));
        assertEquals("第一条", history.get(0).get("content"));
        assertTrue(history.get(0).containsKey("createdAt"));
        assertEquals("recent", actual.recentConversation());
        assertEquals("semantic", actual.semanticContext());
        assertEquals("procedural", actual.proceduralHints());
    }

    @Test
    void shouldComposeMemoryContextFromConversationKnowledgeAndHabits() {
        TestMemoryManager memoryManager = new TestMemoryManager(
                List.of(
                        new ConversationTurn("user", "旧对话一", Instant.parse("2024-01-01T00:00:00Z")),
                        new ConversationTurn("assistant", "旧对话二", Instant.parse("2024-01-01T00:01:00Z")),
                        new ConversationTurn("user", "保留对话三", Instant.parse("2024-01-01T00:02:00Z")),
                        new ConversationTurn("assistant", "保留对话四", Instant.parse("2024-01-01T00:03:00Z"))
                ),
                List.of(new SemanticMemoryEntry("persisted rollup", List.of(0.1), Instant.parse("2024-01-01T00:05:00Z"))),
                List.of(new SemanticMemoryEntry("知识A", List.of(0.2), Instant.parse("2024-01-01T00:06:00Z"))),
                List.of(new SkillUsageStats("todo.create", 10, 8, 2)),
                new MemoryCompressionPlan(
                        new MemoryStyleProfile("concise", "direct", "bullet"),
                        List.of(new MemoryCompressionStep("BRIEF", "前文摘要", 42)),
                        Instant.parse("2024-01-01T00:10:00Z")
                ),
                new PromptMemoryContextDto("recent", "semantic", "procedural", Map.of("tone", "direct"), List.of())
        );
        DispatcherMemoryFacade facade = new DispatcherMemoryFacade(new MemoryFacade(memoryManager), 4, 2, 2, 2, 3);

        AtomicReference<DispatcherMemoryFacade.MemoryCompressionStats> statsRef = new AtomicReference<>();
        String context = facade.buildMemoryContext("u1", "查一下记忆", 500, statsRef::set);

        assertTrue(context.contains("Recent conversation:"));
        assertTrue(context.contains("Relevant knowledge:"));
        assertTrue(context.contains("User skill habits:"));
        assertTrue(context.contains("persisted rollup"));
        assertTrue(context.contains("前文摘要"));
        assertTrue(context.contains("知识A"));
        assertTrue(context.contains("todo.create"));
        assertNotNull(statsRef.get());
        assertTrue(statsRef.get().compressed());
        assertEquals(2, statsRef.get().summarizedTurns());
    }

    @Test
    void shouldBuildUnifiedSkillAndFallbackContexts() {
        TestMemoryManager memoryManager = new TestMemoryManager(
                List.of(
                        new ConversationTurn("user", "旧对话一", Instant.parse("2024-01-01T00:00:00Z")),
                        new ConversationTurn("assistant", "旧对话二", Instant.parse("2024-01-01T00:01:00Z"))
                ),
                List.of(),
                List.of(),
                List.of(),
                null,
                new PromptMemoryContextDto("recent", "semantic", "procedural", Map.of("tone", "direct"), List.of())
        );
        DispatcherMemoryFacade facade = new DispatcherMemoryFacade(new MemoryFacade(memoryManager), 4, 2, 2, 2, 3);
        SemanticAnalysisResult semanticAnalysis = new SemanticAnalysisResult(
                "semantic",
                "create.todo",
                "rewritten input",
                "todo.create",
                Map.of("priority", "high"),
                List.of("task"),
                "用户要创建待办",
                0.82
        );

        String enriched = facade.enrichMemoryContextWithSemanticAnalysis("base context", semanticAnalysis, 0.45, 500, 120);
        List<Map<String, Object>> history = facade.buildChatHistory("u1");
        SkillContext skillContext = facade.buildSkillContext(
                "u1",
                semanticAnalysis.routingInput("原始输入"),
                "原始输入",
                Map.of("profile", "direct"),
                enriched,
                history,
                semanticAnalysis
        );
        Map<String, Object> llmContext = facade.buildFallbackLlmContext(
                "u1",
                semanticAnalysis.routingInput("原始输入"),
                "原始输入",
                Map.of("profile", "direct"),
                semanticAnalysis,
                enriched,
                new PromptMemoryContextDto("recent", "semantic", "procedural", Map.of("tone", "direct"), List.of()),
                history,
                true,
                true,
                true,
                200,
                "llm-fallback"
        );

        assertTrue(enriched.contains("Semantic analysis:"));
        assertEquals("u1", skillContext.userId());
        assertEquals("rewritten input", skillContext.input());
        assertEquals(enriched, skillContext.attributes().get("memoryContext"));
        assertEquals("原始输入", skillContext.attributes().get("originalInput"));
        assertEquals(history, skillContext.attributes().get("chatHistory"));
        assertEquals("u1", llmContext.get("userId"));
        assertEquals("rewritten input", llmContext.get("input"));
        assertEquals("llm-fallback", llmContext.get("routeStage"));
        assertEquals(Boolean.TRUE, llmContext.get("memory.shrinkApplied"));
        assertEquals("", llmContext.get("memoryContext"));
        assertEquals(Map.of("tone", "direct"), llmContext.get("memory.persona"));
    }

    @Test
    void shouldBuildCompactDecisionMemoryContextAndShrinkRealtimeRequests() {
        DispatcherMemoryFacade facade = new DispatcherMemoryFacade((MemoryFacade) null, 4, 2, 2, 2, 3);
        PromptMemoryContextDto promptMemoryContext = new PromptMemoryContextDto(
                "user: 继续看接口文档\nassistant: 好的，我继续整理",
                "- [fact] Spring Boot RestClient 官方文档更适合走 docs.lookup\n"
                        + "- [working] 当前事项：继续看接口文档；下一步：确认认证流程\n"
                        + "- [assistant-context] 上下文明确时直接推进\n"
                        + "- [buffer] 另一个候选事实",
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
                        "当前事项 继续看接口文档；下一步 确认认证流程"
                ),
                Map.of("clarifyStyle", "minimal")
        );

        String compact = facade.buildDecisionMemoryContext(
                promptMemoryContext,
                promptMemoryContext.taskThreadSnapshot().toMemoryContextSection(),
                "继续看文档",
                false,
                true,
                280,
                420
        );
        String realtime = facade.buildDecisionMemoryContext(
                promptMemoryContext,
                promptMemoryContext.taskThreadSnapshot().toMemoryContextSection(),
                "今天新闻",
                true,
                true,
                280,
                420
        );

        assertTrue(compact.contains("Active task:"));
        assertTrue(compact.contains("Relevant facts:"));
        assertTrue(compact.contains("Recent context:"));
        assertTrue(compact.contains("docs.lookup"));
        assertFalse(compact.contains("User skill habits"));
        assertFalse(compact.contains("上下文明确时直接推进"));
        assertFalse(compact.contains("当前事项：继续看接口文档；下一步：确认认证流程"));
        assertTrue(realtime.contains("Active task:"));
        assertFalse(realtime.contains("Relevant facts:"));
        assertFalse(realtime.contains("Recent context:"));
        assertTrue(realtime.length() <= 280);
    }

    private static final class TestMemoryManager extends MemoryManager {
        private final List<ConversationTurn> recentConversation;
        private final List<SemanticMemoryEntry> rollup;
        private final List<SemanticMemoryEntry> knowledge;
        private final List<SkillUsageStats> usageStats;
        private final MemoryCompressionPlan compressionPlan;
        private final PromptMemoryContextDto promptMemoryContext;

        private TestMemoryManager(List<ConversationTurn> recentConversation,
                                  List<SemanticMemoryEntry> rollup,
                                  List<SemanticMemoryEntry> knowledge,
                                  List<SkillUsageStats> usageStats,
                                  MemoryCompressionPlan compressionPlan,
                                  PromptMemoryContextDto promptMemoryContext) {
            super(null, null, null, null, null, null, null, null, null, null, null, false, 4, 2, 3, 16);
            this.recentConversation = recentConversation;
            this.rollup = rollup;
            this.knowledge = knowledge;
            this.usageStats = usageStats;
            this.compressionPlan = compressionPlan;
            this.promptMemoryContext = promptMemoryContext;
        }

        @Override
        public List<ConversationTurn> getRecentConversation(String userId, int limit) {
            return recentConversation;
        }

        @Override
        public List<SemanticMemoryEntry> searchKnowledge(String userId, String query, int limit, String preferredBucket) {
            if (limit == 1 && "conversation-rollup".equals(preferredBucket)) {
                return rollup;
            }
            return knowledge;
        }

        @Override
        public List<SkillUsageStats> getSkillUsageStats(String userId) {
            return usageStats;
        }

        @Override
        public MemoryCompressionPlan buildMemoryCompressionPlan(String userId,
                                                                String sourceText,
                                                                MemoryStyleProfile styleOverride,
                                                                String focus) {
            return compressionPlan;
        }

        @Override
        public PromptMemoryContextDto buildPromptMemoryContext(String userId,
                                                               String userInput,
                                                               int maxChars,
                                                               Map<String, Object> profileContext) {
            return promptMemoryContext;
        }
    }
}
