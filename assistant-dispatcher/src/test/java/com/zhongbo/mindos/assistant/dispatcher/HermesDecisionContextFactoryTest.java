package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.common.dto.TaskThreadSnapshotDto;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.MemoryFacade;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalyzer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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
}
