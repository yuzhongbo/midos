package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.common.dto.RetrievedMemoryItemDto;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptMemoryContextAssemblerTest {

    @Test
    void shouldApplyRecencyDecayAndReturnHigherScoreForRecentItems() {
        EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
        SemanticMemoryService semanticMemoryService = new SemanticMemoryService(new MemoryConsolidationService());
        ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
        PreferenceProfileService preferenceProfileService = new PreferenceProfileService(2, true);
        DefaultPromptMemoryContextAssembler assembler = new DefaultPromptMemoryContextAssembler(
                episodicMemoryService,
                semanticMemoryService,
                proceduralMemoryService,
                preferenceProfileService
        );

        semanticMemoryService.addEntry("u1", new SemanticMemoryEntry(
                "project alpha api integration recent",
                List.of(0.1, 0.2),
                Instant.now().minusSeconds(3600)
        ));
        semanticMemoryService.addEntry("u1", new SemanticMemoryEntry(
                "project alpha api integration historical",
                List.of(0.1, 0.2),
                Instant.now().minusSeconds(3600 * 24 * 30L)
        ));

        PromptMemoryContextDto context = assembler.assemble("u1", "alpha api", 1200, Map.of());
        List<RetrievedMemoryItemDto> semanticItems = context.debugTopItems().stream()
                .filter(item -> "semantic".equals(item.type()))
                .toList();

        assertTrue(semanticItems.size() >= 2);
        assertTrue(semanticItems.get(0).finalScore() >= semanticItems.get(1).finalScore());
    }

    @Test
    void shouldRespectMaxCharsBudgetWhenAssembling() {
        EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
        SemanticMemoryService semanticMemoryService = new SemanticMemoryService(new MemoryConsolidationService());
        ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
        PreferenceProfileService preferenceProfileService = new PreferenceProfileService(2, true);
        DefaultPromptMemoryContextAssembler assembler = new DefaultPromptMemoryContextAssembler(
                episodicMemoryService,
                semanticMemoryService,
                proceduralMemoryService,
                preferenceProfileService
        );

        for (int i = 0; i < 10; i++) {
            episodicMemoryService.appendTurn("u2", new ConversationTurn("user", "message-" + i + " very long context block", Instant.now()));
            semanticMemoryService.addEntry("u2", SemanticMemoryEntry.of("knowledge line " + i + " with lots of details", List.of(0.3, 0.4)));
        }

        PromptMemoryContextDto context = assembler.assemble("u2", "knowledge", 420, Map.of());
        int total = context.recentConversation().length()
                + context.semanticContext().length()
                + context.proceduralHints().length();

        assertTrue(total <= 420);
    }

    @Test
    void shouldIncludeProceduralHintsWhenHistoryExists() {
        EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
        SemanticMemoryService semanticMemoryService = new SemanticMemoryService(new MemoryConsolidationService());
        ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
        PreferenceProfileService preferenceProfileService = new PreferenceProfileService(2, true);
        DefaultPromptMemoryContextAssembler assembler = new DefaultPromptMemoryContextAssembler(
                episodicMemoryService,
                semanticMemoryService,
                proceduralMemoryService,
                preferenceProfileService
        );

        proceduralMemoryService.log("u3", "teaching.plan", "math plan", true);
        proceduralMemoryService.log("u3", "teaching.plan", "follow up", true);
        proceduralMemoryService.log("u3", "eq.coach", "conflict", false);

        PromptMemoryContextDto context = assembler.assemble("u3", "继续按之前方式", 800, Map.of());

        assertFalse(context.proceduralHints().isBlank());
        assertTrue(context.proceduralHints().contains("teaching.plan"));
        assertEquals("zh-CN", context.personaSnapshot().get("language"));
    }

    @Test
    void shouldSuppressProceduralHintsForConversationalQueries() {
        EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
        SemanticMemoryService semanticMemoryService = new SemanticMemoryService(new MemoryConsolidationService());
        ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
        PreferenceProfileService preferenceProfileService = new PreferenceProfileService(2, true);
        DefaultPromptMemoryContextAssembler assembler = new DefaultPromptMemoryContextAssembler(
                episodicMemoryService,
                semanticMemoryService,
                proceduralMemoryService,
                preferenceProfileService
        );

        proceduralMemoryService.log("u3-chat", "news_search", "查看国际新闻", true);
        proceduralMemoryService.log("u3-chat", "eq.coach", "分析沟通问题", true);

        PromptMemoryContextDto context = assembler.assemble("u3-chat", "日常跟我聊天", 800, Map.of());

        assertTrue(context.proceduralHints().isBlank());
        assertTrue(context.debugTopItems().stream().noneMatch(item -> "procedural".equals(item.type())));
    }

    @Test
    void shouldSurfaceFactAndWorkingHintsInSemanticContext() {
        String oldLayersEnabled = System.getProperty("mindos.memory.layers.enabled");
        String oldFactMaxChars = System.getProperty("mindos.memory.layers.fact-max-chars");
        try {
            System.setProperty("mindos.memory.layers.enabled", "true");
            System.setProperty("mindos.memory.layers.fact-max-chars", "80");

            EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
            SemanticMemoryService semanticMemoryService = new SemanticMemoryService(new MemoryConsolidationService());
            ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
            PreferenceProfileService preferenceProfileService = new PreferenceProfileService(2, true);
            DefaultPromptMemoryContextAssembler assembler = new DefaultPromptMemoryContextAssembler(
                    episodicMemoryService,
                    semanticMemoryService,
                    proceduralMemoryService,
                    preferenceProfileService
            );

            semanticMemoryService.addEntry("u4",
                    new SemanticMemoryEntry("owner Alice due 2026-04-05", List.of(0.1, 0.2), Instant.now()),
                    "task");
            semanticMemoryService.addEntry("u4",
                    new SemanticMemoryEntry("working draft for alpha launch checklist", List.of(0.2, 0.3), Instant.now().minusSeconds(3600)),
                    "task");

            PromptMemoryContextDto context = assembler.assemble("u4", "owner due", 800, Map.of());

            assertTrue(context.semanticContext().contains("[fact]"));
            assertTrue(context.semanticContext().contains("owner Alice due 2026-04-05"));
        } finally {
            restoreProperty("mindos.memory.layers.enabled", oldLayersEnabled);
            restoreProperty("mindos.memory.layers.fact-max-chars", oldFactMaxChars);
        }
    }

    @Test
    void shouldRetainLexicalRelevanceWhenHybridSearchIsDisabled() {
        EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
        SemanticMemoryService semanticMemoryService = new SemanticMemoryService(new MemoryConsolidationService());
        ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
        PreferenceProfileService preferenceProfileService = new PreferenceProfileService(2, true);
        DefaultPromptMemoryContextAssembler assembler = new DefaultPromptMemoryContextAssembler(
                episodicMemoryService,
                semanticMemoryService,
                proceduralMemoryService,
                preferenceProfileService
        );

        semanticMemoryService.addEntry("u5", SemanticMemoryEntry.of("project alpha api owner", List.of(0.1, 0.2)), "task");

        PromptMemoryContextDto context = assembler.assemble("u5", "alpha owner", 800, Map.of());

        RetrievedMemoryItemDto semanticItem = context.debugTopItems().stream()
                .filter(item -> "semantic".equals(item.type()))
                .findFirst()
                .orElseThrow();
        assertTrue(semanticItem.relevanceScore() > 0.0);
    }

    @Test
    void shouldPreferFactMemoryOverRollupAndRoutingSummaries() {
        EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
        SemanticMemoryService semanticMemoryService = new SemanticMemoryService(new MemoryConsolidationService());
        ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
        PreferenceProfileService preferenceProfileService = new PreferenceProfileService(2, true);
        DefaultPromptMemoryContextAssembler assembler = new DefaultPromptMemoryContextAssembler(
                episodicMemoryService,
                semanticMemoryService,
                proceduralMemoryService,
                preferenceProfileService
        );

        semanticMemoryService.addEntry("u6",
                new SemanticMemoryEntry("owner Alice due 2026-04-05", List.of(0.1, 0.2), Instant.now()),
                "task");
        semanticMemoryService.addEntry("u6",
                new SemanticMemoryEntry("[意图摘要] 用户当前想要：用户要创建待办并确认 owner due；可用执行方式：todo.create", List.of(0.1, 0.2), Instant.now()),
                "task");
        semanticMemoryService.addEntry("u6",
                new SemanticMemoryEntry("[助手上下文] 用户刚才在处理：用户要创建待办并确认 owner due；执行方式：todo.create；结果：已推进",
                        List.of(0.1, 0.2),
                        Instant.now()),
                "conversation-rollup");

        PromptMemoryContextDto context = assembler.assemble("u6", "创建待办 owner due", 800, Map.of());

        RetrievedMemoryItemDto factItem = context.debugTopItems().stream()
                .filter(item -> "semantic".equals(item.type()))
                .findFirst()
                .orElseThrow();
        RetrievedMemoryItemDto routingItem = context.debugTopItems().stream()
                .filter(item -> "semantic-routing".equals(item.type()))
                .findFirst()
                .orElseThrow();

        assertEquals("owner Alice due 2026-04-05", factItem.text());
        assertTrue(factItem.finalScore() > routingItem.finalScore());
        assertTrue(context.semanticContext().contains("owner Alice due 2026-04-05"));
        assertTrue(context.semanticContext().contains("[summary] 用户当前想要：用户要创建待办并确认 owner due"));
        assertFalse(context.semanticContext().contains("reply="));
    }

    @Test
    void shouldHumanizeTaskStateAndClassifyLearningSignalAsRoutingContext() {
        EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
        SemanticMemoryService semanticMemoryService = new SemanticMemoryService(new MemoryConsolidationService());
        ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
        PreferenceProfileService preferenceProfileService = new PreferenceProfileService(2, true);
        DefaultPromptMemoryContextAssembler assembler = new DefaultPromptMemoryContextAssembler(
                episodicMemoryService,
                semanticMemoryService,
                proceduralMemoryService,
                preferenceProfileService
        );

        semanticMemoryService.addEntry("u6-state",
                new SemanticMemoryEntry("[任务状态] 当前事项：提交周报；状态：进行中；下一步：继续推进提交周报", List.of(0.1, 0.2), Instant.now()),
                "task");
        semanticMemoryService.addEntry("u6-state",
                new SemanticMemoryEntry("[学习信号] 当前事项：提交周报；偏好：上下文明确时直接推进，少澄清", List.of(0.1, 0.2), Instant.now()),
                "task");

        PromptMemoryContextDto context = assembler.assemble("u6-state", "继续推进周报", 800, Map.of());

        assertTrue(context.semanticContext().contains("当前事项：提交周报；状态：进行中"));
        assertFalse(context.semanticContext().contains("[任务状态]"));
        assertTrue(context.debugTopItems().stream().anyMatch(item ->
                "semantic-routing".equals(item.type()) && item.text().contains("上下文明确时直接推进")));
        assertEquals("提交周报", context.taskThreadSnapshot().focus());
        assertEquals("继续推进提交周报", context.taskThreadSnapshot().nextAction());
        assertEquals("minimal", context.learnedPreferences().get("clarifyStyle"));
        assertEquals("direct-progress", context.learnedPreferences().get("executionStyle"));
    }

    @Test
    void shouldFilterInternalAssistantRepliesFromRecentConversationContext() {
        EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
        SemanticMemoryService semanticMemoryService = new SemanticMemoryService(new MemoryConsolidationService());
        ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
        PreferenceProfileService preferenceProfileService = new PreferenceProfileService(2, true);
        DefaultPromptMemoryContextAssembler assembler = new DefaultPromptMemoryContextAssembler(
                episodicMemoryService,
                semanticMemoryService,
                proceduralMemoryService,
                preferenceProfileService
        );

        episodicMemoryService.appendTurn("u7", new ConversationTurn("user", "继续帮我整理周报重点", Instant.now().minusSeconds(90)));
        episodicMemoryService.appendTurn("u7", new ConversationTurn("assistant", "根据已有记忆，我先直接回答：1. [会话摘要] [复盘聚焦] - intent=获取最新新闻资讯", Instant.now().minusSeconds(60)));
        episodicMemoryService.appendTurn("u7", new ConversationTurn("assistant", "好的，我们继续推进周报重点，我先帮你拆成三部分。", Instant.now().minusSeconds(30)));

        PromptMemoryContextDto context = assembler.assemble("u7", "继续整理周报", 800, Map.of());

        assertTrue(context.recentConversation().contains("user: 继续帮我整理周报重点"));
        assertTrue(context.recentConversation().contains("assistant: 好的，我们继续推进周报重点"));
        assertFalse(context.recentConversation().contains("根据已有记忆，我先直接回答"));
        assertTrue(context.debugTopItems().stream().noneMatch(item -> item.text().contains("根据已有记忆，我先直接回答")));
    }

    @Test
    void shouldDownrankConversationSummaryMemoryAgainstConcreteFacts() {
        EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
        SemanticMemoryService semanticMemoryService = new SemanticMemoryService(new MemoryConsolidationService());
        ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
        PreferenceProfileService preferenceProfileService = new PreferenceProfileService(2, true);
        DefaultPromptMemoryContextAssembler assembler = new DefaultPromptMemoryContextAssembler(
                episodicMemoryService,
                semanticMemoryService,
                proceduralMemoryService,
                preferenceProfileService
        );

        semanticMemoryService.addEntry("u8",
                new SemanticMemoryEntry("owner Alice due 2026-04-05", List.of(0.1, 0.2), Instant.now()),
                "task");
        semanticMemoryService.addEntry("u8",
                new SemanticMemoryEntry("[会话摘要] [复盘聚焦] - owner Alice discussed weekly report due Friday", List.of(0.1, 0.2), Instant.now()),
                "task");

        PromptMemoryContextDto context = assembler.assemble("u8", "owner due weekly report", 800, Map.of());

        RetrievedMemoryItemDto factItem = context.debugTopItems().stream()
                .filter(item -> "semantic".equals(item.type()))
                .findFirst()
                .orElseThrow();
        RetrievedMemoryItemDto summaryItem = context.debugTopItems().stream()
                .filter(item -> "semantic-summary".equals(item.type()))
                .findFirst()
                .orElseThrow();

        assertEquals("owner Alice due 2026-04-05", factItem.text());
        assertTrue(factItem.finalScore() > summaryItem.finalScore());
        assertTrue(context.semanticContext().contains("[summary] owner Alice discussed weekly report due Friday"));
    }

    private void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
