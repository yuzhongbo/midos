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
    private final DispatcherAnswerMode answerMode;
    private final ActiveTaskResolver activeTaskResolver;
    private final int promptMaxChars;
    private final int memoryContextMaxChars;
    private final Consumer<DispatcherMemoryFacade.MemoryCompressionStats> compressionMetricsConsumer;

    HermesDecisionContextFactory(DispatcherMemoryFacade dispatcherMemoryFacade,
                                 PersonaCoreService personaCoreService,
                                 SemanticAnalyzer semanticAnalyzer,
                                 HermesToolSchemaCatalog toolSchemaCatalog,
                                 DispatcherAnswerMode answerMode,
                                   int promptMaxChars,
                                  int memoryContextMaxChars,
                                  Consumer<DispatcherMemoryFacade.MemoryCompressionStats> compressionMetricsConsumer) {
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.personaCoreService = personaCoreService;
        this.semanticAnalyzer = semanticAnalyzer;
        this.toolSchemaCatalog = toolSchemaCatalog;
        this.answerMode = answerMode == null ? DispatcherAnswerMode.BALANCED : answerMode;
        this.activeTaskResolver = new ActiveTaskResolver(dispatcherMemoryFacade);
        this.promptMaxChars = Math.max(400, promptMaxChars);
        this.memoryContextMaxChars = Math.max(400, memoryContextMaxChars);
        this.compressionMetricsConsumer = compressionMetricsConsumer;
    }

    HermesDecisionContext create(String userId, String userInput, Map<String, Object> profileContext) {
        return create(userId, userInput, profileContext, true);
    }

    HermesDecisionContext create(String userId,
                                 String userInput,
                                 Map<String, Object> profileContext,
                                 boolean memoryEnabled) {
        Map<String, Object> resolvedProfileContext = !memoryEnabled || personaCoreService == null
                ? safeMap(profileContext)
                : personaCoreService.resolveProfileContext(userId, profileContext);
        List<HermesToolSchema> toolSchemas = toolSchemaCatalog == null ? List.of() : toolSchemaCatalog.listSchemas();
        PromptMemoryContextDto promptMemoryContext = memoryEnabled
                ? dispatcherMemoryFacade.buildPromptMemoryContext(
                userId,
                userInput,
                promptMaxChars,
                resolvedProfileContext
        )
                : new PromptMemoryContextDto("", "", "", Map.of(), List.of());
        String rawMemoryContext = memoryEnabled
                ? dispatcherMemoryFacade.buildMemoryContext(
                userId,
                userInput,
                memoryContextMaxChars,
                compressionMetricsConsumer
        )
                : "";
        ActiveTaskResolver.ResolvedTaskThread activeTaskThread = memoryEnabled
                ? activeTaskResolver.resolve(userId, userInput, promptMemoryContext)
                : ActiveTaskResolver.ResolvedTaskThread.empty();
        Map<String, Object> decisionProfileContext = mergeLearnedPreferences(
                resolvedProfileContext,
                promptMemoryContext.learnedPreferences()
        );
        String memoryContext = activeTaskResolver.enrichMemoryContext(rawMemoryContext, activeTaskThread, memoryContextMaxChars);
        List<Map<String, Object>> chatHistory = memoryEnabled
                ? dispatcherMemoryFacade.buildChatHistory(userId)
                : List.of();
        List<String> toolSummaries = toolSchemas.stream()
                .map(HermesToolSchema::semanticSummary)
                .toList();
        SemanticAnalysisResult semanticAnalysis = semanticAnalyzer == null
                ? SemanticAnalysisResult.empty()
                : semanticAnalyzer.analyze(userId, userInput, memoryContext, decisionProfileContext, toolSummaries);
        String routingInput = semanticAnalysis.routingInput(userInput);
        SkillContext skillContext = dispatcherMemoryFacade.buildSkillContext(
                userId,
                routingInput,
                userInput,
                decisionProfileContext,
                memoryContext,
                chatHistory,
                semanticAnalysis
        );
        if (!activeTaskThread.asAttributes().isEmpty()) {
            Map<String, Object> attributes = new LinkedHashMap<>(skillContext.attributes());
            attributes.putAll(activeTaskThread.asAttributes());
            skillContext = new SkillContext(skillContext.userId(), skillContext.input(), attributes);
        }
        Map<String, Object> llmContext = new LinkedHashMap<>(decisionProfileContext);
        llmContext.put("userId", userId == null ? "" : userId);
        llmContext.put("input", userInput == null ? "" : userInput);
        llmContext.put("memoryContext", memoryContext);
        if (promptMemoryContext.learnedPreferences() != null && !promptMemoryContext.learnedPreferences().isEmpty()) {
            llmContext.put("learnedPreferences", promptMemoryContext.learnedPreferences());
        }
        if (!activeTaskThread.asAttributes().isEmpty()) {
            llmContext.put("taskThread", activeTaskThread.asAttributes());
        }
        if (!chatHistory.isEmpty()) {
            llmContext.put("chatHistory", chatHistory);
        }
        llmContext.putAll(semanticAnalysis.asAttributes());
        return new HermesDecisionContext(
                userId == null ? "" : userId,
                userInput == null ? "" : userInput,
                routingInput == null ? "" : routingInput,
                decisionProfileContext,
                memoryEnabled,
                answerMode,
                promptMemoryContext,
                memoryContext,
                chatHistory,
                toolSchemas,
                semanticAnalysis,
                memoryEnabled ? buildSkillSuccessRates(userId) : Map.of(),
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

    private Map<String, Object> mergeLearnedPreferences(Map<String, Object> resolvedProfileContext,
                                                        Map<String, Object> learnedPreferences) {
        Map<String, Object> safeProfileContext = safeMap(resolvedProfileContext);
        if (learnedPreferences == null || learnedPreferences.isEmpty()) {
            return safeProfileContext;
        }
        Map<String, Object> merged = new LinkedHashMap<>(safeProfileContext);
        merged.put("learnedPreferences", Map.copyOf(learnedPreferences));
        return Map.copyOf(merged);
    }
}
