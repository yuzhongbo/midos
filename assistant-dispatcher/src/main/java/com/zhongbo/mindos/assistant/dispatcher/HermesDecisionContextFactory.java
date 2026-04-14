package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalyzer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class HermesDecisionContextFactory {

    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final PersonaCoreService personaCoreService;
    private final SemanticAnalyzer semanticAnalyzer;
    private final HermesToolSchemaCatalog toolSchemaCatalog;
    private final int promptMaxChars;
    private final int memoryContextMaxChars;
    private final Consumer<DispatcherMemoryFacade.MemoryCompressionStats> compressionMetricsConsumer;

    HermesDecisionContextFactory(DispatcherMemoryFacade dispatcherMemoryFacade,
                                 PersonaCoreService personaCoreService,
                                 SemanticAnalyzer semanticAnalyzer,
                                 HermesToolSchemaCatalog toolSchemaCatalog,
                                  int promptMaxChars,
                                 int memoryContextMaxChars,
                                 Consumer<DispatcherMemoryFacade.MemoryCompressionStats> compressionMetricsConsumer) {
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.personaCoreService = personaCoreService;
        this.semanticAnalyzer = semanticAnalyzer;
        this.toolSchemaCatalog = toolSchemaCatalog;
        this.promptMaxChars = Math.max(400, promptMaxChars);
        this.memoryContextMaxChars = Math.max(400, memoryContextMaxChars);
        this.compressionMetricsConsumer = compressionMetricsConsumer;
    }

    HermesDecisionContext create(String userId, String userInput, Map<String, Object> profileContext) {
        Map<String, Object> resolvedProfileContext = personaCoreService == null
                ? safeMap(profileContext)
                : personaCoreService.resolveProfileContext(userId, profileContext);
        List<HermesToolSchema> toolSchemas = toolSchemaCatalog == null ? List.of() : toolSchemaCatalog.listSchemas();
        PromptMemoryContextDto promptMemoryContext = dispatcherMemoryFacade.buildPromptMemoryContext(
                userId,
                userInput,
                promptMaxChars,
                resolvedProfileContext
        );
        String memoryContext = dispatcherMemoryFacade.buildMemoryContext(
                userId,
                userInput,
                memoryContextMaxChars,
                compressionMetricsConsumer
        );
        List<Map<String, Object>> chatHistory = dispatcherMemoryFacade.buildChatHistory(userId);
        List<String> toolSummaries = toolSchemas.stream()
                .map(HermesToolSchema::semanticSummary)
                .toList();
        SemanticAnalysisResult semanticAnalysis = semanticAnalyzer == null
                ? SemanticAnalysisResult.empty()
                : semanticAnalyzer.analyze(userId, userInput, memoryContext, resolvedProfileContext, toolSummaries);
        String routingInput = semanticAnalysis.routingInput(userInput);
        SkillContext skillContext = dispatcherMemoryFacade.buildSkillContext(
                userId,
                routingInput,
                userInput,
                resolvedProfileContext,
                memoryContext,
                chatHistory,
                semanticAnalysis
        );
        Map<String, Object> llmContext = new LinkedHashMap<>(resolvedProfileContext);
        llmContext.put("userId", userId == null ? "" : userId);
        llmContext.put("input", userInput == null ? "" : userInput);
        llmContext.put("memoryContext", memoryContext);
        if (!chatHistory.isEmpty()) {
            llmContext.put("chatHistory", chatHistory);
        }
        llmContext.putAll(semanticAnalysis.asAttributes());
        return new HermesDecisionContext(
                userId == null ? "" : userId,
                userInput == null ? "" : userInput,
                routingInput == null ? "" : routingInput,
                resolvedProfileContext,
                promptMemoryContext,
                memoryContext,
                chatHistory,
                toolSchemas,
                semanticAnalysis,
                buildSkillSuccessRates(userId),
                llmContext,
                skillContext
        );
    }

    private Map<String, Double> buildSkillSuccessRates(String userId) {
        if (userId == null || userId.isBlank()) {
            return Map.of();
        }
        Map<String, Double> rates = new LinkedHashMap<>();
        for (SkillUsageStats stats : dispatcherMemoryFacade.getSkillUsageStats(userId)) {
            if (stats == null || stats.skillName() == null || stats.skillName().isBlank() || stats.totalCount() <= 0) {
                continue;
            }
            rates.put(stats.skillName(), stats.successCount() / (double) stats.totalCount());
        }
        return rates.isEmpty() ? Map.of() : Map.copyOf(rates);
    }

    private Map<String, Object> safeMap(Map<String, Object> value) {
        return value == null || value.isEmpty() ? Map.of() : Map.copyOf(value);
    }
}
