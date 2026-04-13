package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import com.zhongbo.mindos.assistant.skill.SkillCandidate;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;

final class PlannerSignalCollector {

    private static final int DEFAULT_HEURISTIC_LIMIT = 4;

    private final SkillCatalogFacade skillEngine;
    private final DispatchRuleCatalog dispatchRuleCatalog;
    private final SkillDslParser skillDslParser;
    private final int heuristicLimit;
    private final List<String> searchPriorityOrder;

    PlannerSignalCollector() {
        this(null, DEFAULT_HEURISTIC_LIMIT);
    }

    PlannerSignalCollector(SkillCatalogFacade skillEngine) {
        this(skillEngine, DEFAULT_HEURISTIC_LIMIT);
    }

    PlannerSignalCollector(SkillCatalogFacade skillEngine, int heuristicLimit) {
        this.skillEngine = skillEngine;
        this.dispatchRuleCatalog = new DispatchRuleCatalog(skillEngine);
        this.skillDslParser = new SkillDslParser(new SkillDslValidator());
        this.heuristicLimit = Math.max(1, heuristicLimit);
        this.searchPriorityOrder = parsePriorityOrder(System.getProperty("mindos.dispatcher.parallel-routing.search-priority-order"));
    }

    List<DecisionSignal> collect(DecisionOrchestrator.UserInput input) {
        DecisionOrchestrator.UserInput safeInput = DecisionOrchestrator.UserInput.safe(input);
        List<DecisionSignal> signals = new ArrayList<>();
        addExplicitSignals(signals, safeInput.userInput(), safeInput.skillContext());
        addContextSignals(signals, safeInput.skillContext());
        addRuleSignals(signals, safeInput.userInput());
        addHeuristicSignals(signals, effectiveInput(safeInput.userInput(), safeInput.skillContext()));
        return signals.isEmpty() ? List.of() : List.copyOf(signals);
    }

    private void addExplicitSignals(List<DecisionSignal> signals,
                                    String userInput,
                                    SkillContext skillContext) {
        Optional.ofNullable(userInput)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .flatMap(skillDslParser::parse)
                .map(skillDsl -> skillDsl.skill())
                .ifPresent(skill -> addSignal(signals, skill, 1.0, "explicit"));
        if (userInput != null && userInput.trim().startsWith("skill:")) {
            String[] parts = userInput.trim().split("\\s+", 2);
            if (parts.length > 0 && parts[0].length() > "skill:".length()) {
                addSignal(signals, parts[0].substring("skill:".length()), 1.0, "explicit");
            }
        }
        Map<String, Object> attributes = attributes(skillContext);
        addSignal(signals, stringValue(attributes.get("explicitTarget")), 0.99, "explicit");
        addSignal(signals, stringValue(attributes.get("explicitSkill")), 0.99, "explicit");
        addSignal(signals, stringValue(attributes.get("_target")), 0.98, "explicit");
        addSignal(signals, stringValue(attributes.get("target")), 0.97, "explicit");
    }

    private void addContextSignals(List<DecisionSignal> signals, SkillContext skillContext) {
        Map<String, Object> attributes = attributes(skillContext);
        addSignal(signals, stringValue(attributes.get("habitTarget")), 0.89, "memory");
        double semanticConfidence = semanticConfidence(attributes);
        addSignal(signals, stringValue(attributes.get(SemanticAnalysisResult.ATTR_SUGGESTED_SKILL)), semanticConfidence, "semantic");
        addSignal(signals, stringValue(attributes.get(SemanticAnalysisResult.ATTR_INTENT)), Math.max(0.60, semanticConfidence - 0.05), "semantic");
    }

    private void addRuleSignals(List<DecisionSignal> signals, String userInput) {
        dispatchRuleCatalog.recommendFallbackSignals(userInput).forEach(signals::add);
    }

    private void addHeuristicSignals(List<DecisionSignal> signals, String input) {
        if (skillEngine == null || input == null || input.isBlank()) {
            return;
        }
        List<SkillCandidate> candidates = skillEngine.detectSkillCandidates(input, heuristicLimit);
        for (SkillCandidate candidate : candidates) {
            if (candidate == null || candidate.skillName() == null || candidate.skillName().isBlank()) {
                continue;
            }
            double score = heuristicScore(candidate);
            addSignal(signals, candidate.skillName(), score, "heuristic");
        }
    }

    private void addSignal(List<DecisionSignal> signals, String target, double score, String source) {
        if (target == null || target.isBlank()) {
            return;
        }
        signals.add(new DecisionSignal(target, clamp01(score), source));
    }

    private Map<String, Object> attributes(SkillContext skillContext) {
        return skillContext == null || skillContext.attributes() == null ? Map.of() : skillContext.attributes();
    }

    private String effectiveInput(String userInput, SkillContext skillContext) {
        if (skillContext != null && skillContext.input() != null && !skillContext.input().isBlank()) {
            return skillContext.input();
        }
        return userInput == null ? "" : userInput.trim();
    }

    private double semanticConfidence(Map<String, Object> attributes) {
        Object raw = attributes.get(SemanticAnalysisResult.ATTR_CONFIDENCE);
        if (raw instanceof Number number) {
            return clamp01(number.doubleValue());
        }
        return 0.82;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double priorityBoost(String skillName) {
        if (skillName == null || skillName.isBlank() || searchPriorityOrder.isEmpty()) {
            return 0.0;
        }
        String normalized = skillName.trim().toLowerCase(Locale.ROOT);
        for (int index = 0; index < searchPriorityOrder.size(); index++) {
            if (searchPriorityOrder.get(index).equals(normalized)) {
                return Math.max(0, searchPriorityOrder.size() - index) * 0.01;
            }
        }
        return 0.0;
    }

    private double heuristicScore(SkillCandidate candidate) {
        if (candidate == null) {
            return 0.0;
        }
        double normalizedScore = Math.min(Math.max(candidate.score(), 0), 1000) / 1000.0;
        return 0.86 + priorityBoost(candidate.skillName()) + normalizedScore * 0.12;
    }

    private List<String> parsePriorityOrder(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(rawValue.split(","))
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .toList();
    }
}
