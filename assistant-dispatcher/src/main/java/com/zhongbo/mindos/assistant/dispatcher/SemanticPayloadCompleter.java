package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.skill.DecisionCapabilityCatalog;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;

final class SemanticPayloadCompleter {

    private static final Logger LOGGER = Logger.getLogger(SemanticPayloadCompleter.class.getName());

    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final BehaviorRoutingSupport behaviorRoutingSupport;
    private final SkillCommandAssembler skillCommandAssembler;
    private final Function<String, String> memoryBucketResolver;

    SemanticPayloadCompleter(DispatcherMemoryFacade dispatcherMemoryFacade,
                             BehaviorRoutingSupport behaviorRoutingSupport,
                             SkillCommandAssembler skillCommandAssembler,
                             Function<String, String> memoryBucketResolver) {
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.behaviorRoutingSupport = behaviorRoutingSupport;
        this.skillCommandAssembler = skillCommandAssembler;
        this.memoryBucketResolver = memoryBucketResolver;
    }

    Map<String, Object> buildEffectiveSemanticPayload(String userId,
                                                      SemanticAnalysisResult semanticAnalysis,
                                                      String originalInput,
                                                      String targetSkill) {
        if (semanticAnalysis == null || targetSkill == null || targetSkill.isBlank()) {
            return Map.of();
        }
        String executionTarget = DecisionCapabilityCatalog.executionTarget(targetSkill);
        Map<String, Object> payload = new LinkedHashMap<>(skillCommandAssembler.buildSemanticPayload(
                executionTarget,
                semanticAnalysis.payload(),
                originalInput,
                semanticAnalysis.summary(),
                semanticAnalysis.routingInput(originalInput),
                ""
        ));
        completeFromMemory(userId, executionTarget, semanticAnalysis, payload, originalInput);
        return payload;
    }

    String summarizeSemanticParams(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        List<String> keys = List.of("task", "query", "keyword", "topic", "goal", "dueDate");
        List<String> pairs = new ArrayList<>();
        for (String key : keys) {
            Object value = payload.get(key);
            if (isBlankValue(value)) {
                continue;
            }
            pairs.add(key + "=" + capText(String.valueOf(value).trim(), 40));
            if (pairs.size() >= 3) {
                break;
            }
        }
        return String.join(";", pairs);
    }

    String resolveMemoryBucket(String input) {
        if (memoryBucketResolver == null) {
            return "general";
        }
        String resolved = memoryBucketResolver.apply(input);
        return resolved == null || resolved.isBlank() ? "general" : resolved;
    }

    private void completeFromMemory(String userId,
                                    String targetSkill,
                                    SemanticAnalysisResult semanticAnalysis,
                                    Map<String, Object> payload,
                                    String originalInput) {
        if (userId == null || userId.isBlank() || payload == null) {
            return;
        }
        String skill = targetSkill == null || targetSkill.isBlank()
                ? (semanticAnalysis == null ? "" : semanticAnalysis.suggestedSkill())
                : targetSkill;
        if (skill == null || skill.isBlank()) {
            return;
        }
        String summary = semanticAnalysis.summary() == null ? "" : semanticAnalysis.summary().trim();
        String routingInput = semanticAnalysis.routingInput(originalInput);
        String memoryQuery = summary.isBlank() ? routingInput : summary;
        List<SemanticMemoryEntry> related = dispatcherMemoryFacade.searchKnowledge(
                userId,
                memoryQuery,
                3,
                resolveMemoryBucket(originalInput)
        );
        String memoryHint = related.isEmpty() ? "" : related.get(0).text();

        payload.putAll(skillCommandAssembler.buildSemanticPayload(
                skill,
                payload,
                originalInput,
                capText(summary, 180),
                capText(routingInput, 180),
                capText(memoryHint, 180)
        ));
        List<String> filledKeys = new ArrayList<>();
        Set<String> beforeKeys = payload.isEmpty() ? Set.of() : new LinkedHashSet<>(payload.keySet());
        behaviorRoutingSupport.applyBehaviorLearnedDefaults(userId, skill, payload);
        for (String key : payload.keySet()) {
            if (!beforeKeys.contains(key)) {
                filledKeys.add(key);
            }
        }
        if (!filledKeys.isEmpty()) {
            LOGGER.info(() -> "semantic.payload.completed userId=" + userId + ", skill=" + skill + ", filled=" + filledKeys + ", memoryHintPresent=" + !memoryHint.isBlank());
        }
    }

    private boolean isMcpSearchSkill(String skillName) {
        String normalized = normalize(skillName);
        return normalized.startsWith("mcp.")
                && (normalized.contains("search") || normalized.endsWith("query"));
    }

    private boolean isBlankValue(Object value) {
        if (value == null) {
            return true;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private String capText(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 14)) + "\n...[truncated]";
    }
}
