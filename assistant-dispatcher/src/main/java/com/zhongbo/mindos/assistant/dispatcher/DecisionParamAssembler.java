package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import com.zhongbo.mindos.assistant.skill.DecisionCapabilityCatalog;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class DecisionParamAssembler {

    private final SkillCommandAssembler skillCommandAssembler;
    private final SkillDslParser skillDslParser;

    DecisionParamAssembler() {
        this(null);
    }

    DecisionParamAssembler(SkillCommandAssembler skillCommandAssembler) {
        this.skillCommandAssembler = skillCommandAssembler;
        this.skillDslParser = new SkillDslParser(new SkillDslValidator());
    }

    Map<String, Object> assembleParams(String skillName,
                                       String signalSource,
                                       String userInput,
                                       SkillContext context) {
        String executionTarget = DecisionCapabilityCatalog.executionTarget(skillName);
        Map<String, Object> params = decisionParamsFromInput(
                executionTarget,
                effectiveInput(userInput, context),
                context == null || context.attributes() == null ? Map.of() : context.attributes()
        );
        if (executionTarget.isBlank()) {
            return params;
        }
        Map<String, Object> enriched = new LinkedHashMap<>(params);
        String effectiveInput = effectiveInput(userInput, context);
        if ("explicit".equals(signalSource)) {
            enriched.putAll(explicitSkillParams(effectiveInput));
        }
        if ("echo".equals(executionTarget) && !enriched.containsKey("text")) {
            String echoText = extractEchoText(effectiveInput);
            if (echoText != null && !echoText.isBlank()) {
                enriched.put("text", echoText);
            }
        }
        if ("todo.create".equals(executionTarget) && !enriched.containsKey("task") && effectiveInput != null && !effectiveInput.isBlank()) {
            enriched.put("task", effectiveInput);
        }
        if ("eq.coach".equals(executionTarget) && !enriched.containsKey("query") && effectiveInput != null && !effectiveInput.isBlank()) {
            enriched.put("query", effectiveInput);
        }
        if ("code.generate".equals(executionTarget) && !enriched.containsKey("task") && effectiveInput != null && !effectiveInput.isBlank()) {
            enriched.put("task", effectiveInput);
        }
        if ("news_search".equals(executionTarget) && !enriched.containsKey("query") && effectiveInput != null && !effectiveInput.isBlank()) {
            enriched.put("query", effectiveInput);
        }
        if ("web.lookup".equals(executionTarget) && !enriched.containsKey("query") && effectiveInput != null && !effectiveInput.isBlank()) {
            enriched.put("query", effectiveInput);
        }
        if ("file.search".equals(executionTarget)) {
            enriched.putIfAbsent("path", "./");
            if (!enriched.containsKey("keyword") && effectiveInput != null && !effectiveInput.isBlank()) {
                enriched.put("keyword", effectiveInput);
            }
        }
        if (FinalPlanner.RULE_FALLBACK_SOURCE.equals(signalSource)) {
            enriched.put(FinalPlanner.PLANNER_ROUTE_SOURCE_KEY, FinalPlanner.RULE_FALLBACK_SOURCE);
        }
        return enriched.isEmpty() ? Map.of() : Map.copyOf(enriched);
    }

    Map<String, Object> decisionParamsFromContext(SkillContext context) {
        return decisionParamsFromContext(null, context);
    }

    Map<String, Object> decisionParamsFromContext(String skillName, SkillContext context) {
        if (context == null) {
            return Map.of();
        }
        return decisionParamsFromInput(skillName, context.input(), context.attributes());
    }

    Map<String, Object> decisionParamsFromInput(String skillName,
                                                String userInput,
                                                Map<String, Object> attributes) {
        String executionTarget = DecisionCapabilityCatalog.executionTarget(skillName);
        Map<String, Object> params = new LinkedHashMap<>(attributes == null ? Map.of() : attributes);
        if (hasSemanticPayload(params)) {
            Object semanticPayload = params.get(SemanticAnalysisResult.ATTR_PAYLOAD);
            if (semanticPayload instanceof Map<?, ?> payload) {
                payload.forEach((key, value) -> params.putIfAbsent(String.valueOf(key), value));
            }
        }
        if (usesCanonicalCommands(executionTarget)) {
            params.remove("input");
            if (skillCommandAssembler != null) {
                return skillCommandAssembler.buildDetectedSkillDsl(executionTarget, userInput, params)
                        .map(SkillDsl::input)
                        .map(input -> (Map<String, Object>) new LinkedHashMap<>(input))
                        .orElse(params);
            }
            return params;
        }
        if (userInput != null && !userInput.isBlank()) {
            params.putIfAbsent("input", userInput);
        }
        return params;
    }

    Map<String, Object> orchestratorProfileContext(SkillContext context) {
        if (context == null || context.attributes() == null || context.attributes().isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(context.attributes());
    }

    String resolveSelectedTarget(Decision decision,
                                 DecisionOrchestrator.OrchestrationOutcome outcome,
                                 Optional<SkillResult> routedResult) {
        if (outcome != null && outcome.selectedSkill() != null && !outcome.selectedSkill().isBlank()) {
            return outcome.selectedSkill();
        }
        if (routedResult != null && routedResult.isPresent()) {
            return routedResult.get().skillName();
        }
        return decision == null ? "" : decision.target();
    }

    private boolean usesCanonicalCommands(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        return switch (skillName) {
            case "teaching.plan", "todo.create", "eq.coach", "file.search", "news_search", "web.lookup", "code.generate", "semantic.analyze", "echo", "time" -> true;
            default -> false;
        };
    }

    private boolean hasSemanticPayload(Map<String, Object> attributes) {
        return attributes != null && attributes.containsKey(SemanticAnalysisResult.ATTR_PAYLOAD);
    }

    private String effectiveInput(String userInput, SkillContext context) {
        Map<String, Object> attributes = context == null || context.attributes() == null ? Map.of() : context.attributes();
        Object rewritten = attributes.get(SemanticAnalysisResult.ATTR_REWRITTEN_INPUT);
        String rewrittenInput = rewritten == null ? "" : String.valueOf(rewritten).trim();
        if (!rewrittenInput.isBlank()) {
            return rewrittenInput;
        }
        if (context != null && context.input() != null && !context.input().isBlank()) {
            return context.input();
        }
        return userInput == null ? "" : userInput;
    }

    private String extractEchoText(String userInput) {
        if (userInput == null) {
            return "";
        }
        String trimmed = userInput.trim();
        if (!trimmed.regionMatches(true, 0, "echo ", 0, "echo ".length())) {
            return "";
        }
        return trimmed.length() <= "echo ".length() ? "" : trimmed.substring("echo ".length()).trim();
    }

    private Map<String, Object> explicitSkillParams(String userInput) {
        if (userInput == null) {
            return Map.of();
        }
        String trimmed = userInput.trim();
        if (trimmed.startsWith("{")) {
            return skillDslParser.parse(trimmed)
                    .map(SkillDsl::input)
                    .map(input -> input == null || input.isEmpty() ? Map.<String, Object>of() : Map.copyOf(new LinkedHashMap<>(input)))
                    .orElse(Map.of());
        }
        if (!trimmed.startsWith("skill:")) {
            return Map.of();
        }
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length <= 1) {
            return Map.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        for (int index = 1; index < tokens.length; index++) {
            String token = tokens[index];
            int splitIndex = token.indexOf('=');
            if (splitIndex > 0 && splitIndex < token.length() - 1) {
                params.put(token.substring(0, splitIndex), token.substring(splitIndex + 1));
            }
        }
        return params.isEmpty() ? Map.of() : Map.copyOf(params);
    }
}
