package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.List;
import java.util.Map;

record HermesDecisionContext(
        String userId,
        String userInput,
        String routingInput,
        Map<String, Object> profileContext,
        PromptMemoryContextDto promptMemoryContext,
        String memoryContext,
        List<Map<String, Object>> chatHistory,
        List<HermesToolSchema> toolSchemas,
        SemanticAnalysisResult semanticAnalysis,
        Map<String, Double> skillSuccessRates,
        Map<String, Object> llmContext,
        SkillContext skillContext
) {
    HermesDecisionContext {
        profileContext = profileContext == null ? Map.of() : Map.copyOf(profileContext);
        chatHistory = chatHistory == null ? List.of() : List.copyOf(chatHistory);
        toolSchemas = toolSchemas == null ? List.of() : List.copyOf(toolSchemas);
        semanticAnalysis = semanticAnalysis == null ? SemanticAnalysisResult.empty() : semanticAnalysis;
        skillSuccessRates = skillSuccessRates == null ? Map.of() : Map.copyOf(skillSuccessRates);
        llmContext = llmContext == null ? Map.of() : Map.copyOf(llmContext);
    }
}
