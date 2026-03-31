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
        assertEquals("", context.personaSnapshot().get("language"));
    }
}


