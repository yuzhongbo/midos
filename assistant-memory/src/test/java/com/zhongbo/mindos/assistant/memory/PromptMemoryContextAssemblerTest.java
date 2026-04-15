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
                new SemanticMemoryEntry("semantic-summary intentType=tool-call, contextScope=standalone, summary=用户要创建待办并确认 owner due", List.of(0.1, 0.2), Instant.now()),
                "task");
        semanticMemoryService.addEntry("u6",
                new SemanticMemoryEntry("intent=创建待办; intentType=tool-call; contextScope=standalone; channel=todo.create; outcome=success; summary=用户要创建待办并确认 owner due",
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
        assertTrue(context.semanticContext().contains("[routing] semantic-summary"));
        assertFalse(context.semanticContext().contains("reply="));
    }

    private void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
