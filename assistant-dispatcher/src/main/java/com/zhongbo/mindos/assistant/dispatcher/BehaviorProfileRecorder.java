package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BehaviorProfileRecorder {

    private static final Logger LOGGER = Logger.getLogger(BehaviorProfileRecorder.class.getName());
    private static final Pattern TODO_DUE_DATE_PATTERN = Pattern.compile("(?:截止|due|到期|deadline)\\s*(?:时间|日期|date)?\\s*[:：]?\\s*([^，。；;\\n]+)", Pattern.CASE_INSENSITIVE);

    private final SkillDslParser skillDslParser;
    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final DispatcherMemoryCommandService memoryCommandService;
    private final boolean behaviorLearningEnabled;
    private final int behaviorLearningWindowSize;
    private final double behaviorLearningDefaultParamThreshold;
    private final Predicate<String> habitEligibleChecker;

    BehaviorProfileRecorder(SkillDslParser skillDslParser,
                            DispatcherMemoryFacade dispatcherMemoryFacade,
                            DispatcherMemoryCommandService memoryCommandService,
                            boolean behaviorLearningEnabled,
                            int behaviorLearningWindowSize,
                            double behaviorLearningDefaultParamThreshold,
                            Predicate<String> habitEligibleChecker) {
        this.skillDslParser = skillDslParser;
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.memoryCommandService = memoryCommandService == null
                ? new DispatcherMemoryCommandService(dispatcherMemoryFacade, null)
                : memoryCommandService;
        this.behaviorLearningEnabled = behaviorLearningEnabled;
        this.behaviorLearningWindowSize = behaviorLearningWindowSize;
        this.behaviorLearningDefaultParamThreshold = behaviorLearningDefaultParamThreshold;
        this.habitEligibleChecker = habitEligibleChecker;
    }

    void applyBehaviorLearnedDefaults(String userId, String skillName, Map<String, Object> payload) {
        if (!behaviorLearningEnabled || userId == null || userId.isBlank() || skillName == null || payload == null) {
            return;
        }
        Map<String, String> defaults = inferDefaultParamsFromHistory(userId, skillName);
        if (defaults.isEmpty()) {
            return;
        }
        List<String> applied = new ArrayList<>();
        defaults.forEach((key, value) -> {
            if (isBlankValue(payload.get(key))) {
                payload.put(key, value);
                applied.add(key + "=" + value);
            }
        });
        if (!applied.isEmpty()) {
            LOGGER.info(() -> "behavior-learning.apply userId=" + userId + ", skill=" + skillName + ", appliedDefaults=" + applied);
        }
    }

    void maybeStoreBehaviorProfile(String userId, SkillResult result) {
        if (!behaviorLearningEnabled || result == null || !result.success()) {
            return;
        }
        String channel = normalize(result.skillName());
        if (!isHabitEligibleSkill(channel)) {
            return;
        }
        String profile = buildBehaviorProfileSummary(userId);
        if (profile.isBlank()) {
            return;
        }
        String bucket = inferMemoryBucketBySkill(channel);
        List<SemanticMemoryEntry> recent = dispatcherMemoryFacade.searchKnowledge(userId, "behavior-profile", 1, bucket);
        if (!recent.isEmpty() && profile.equals(recent.get(0).text())) {
            return;
        }
        LOGGER.info(() -> "behavior-learning.store userId=" + userId + ", bucket=" + bucket + ", profileSummary=" + capText(profile, 200));
        List<Double> embedding = List.of((double) profile.length(), Math.abs(profile.hashCode() % 1000) / 1000.0);
        memoryCommandService.writeSemantic(userId, profile, embedding, bucket);
    }

    private Map<String, String> inferDefaultParamsFromHistory(String userId, String skillName) {
        List<ProceduralMemoryEntry> history = dispatcherMemoryFacade.getSkillUsageHistory(userId);
        if (history.isEmpty()) {
            return Map.of();
        }
        String normalizedSkill = normalize(skillName);
        int scanned = 0;
        Map<String, Map<String, Integer>> keyValueCounts = new LinkedHashMap<>();
        for (int i = history.size() - 1; i >= 0 && scanned < behaviorLearningWindowSize; i--) {
            ProceduralMemoryEntry entry = history.get(i);
            if (entry == null || !entry.success() || !normalizedSkill.equals(normalize(entry.skillName()))) {
                continue;
            }
            scanned++;
            extractBehaviorParams(normalizedSkill, entry.input()).forEach((key, value) -> {
                if (key == null || key.isBlank() || value == null || value.isBlank()) {
                    return;
                }
                keyValueCounts.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                        .merge(value, 1, Integer::sum);
            });
        }
        if (scanned < 2 || keyValueCounts.isEmpty()) {
            return Map.of();
        }
        Map<String, String> defaults = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : keyValueCounts.entrySet()) {
            Map.Entry<String, Integer> top = entry.getValue().entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .orElse(null);
            if (top == null) {
                continue;
            }
            double ratio = top.getValue() * 1.0 / scanned;
            if (top.getValue() >= 2 && ratio >= behaviorLearningDefaultParamThreshold) {
                defaults.put(entry.getKey(), top.getKey());
            }
        }
        if (!defaults.isEmpty()) {
            int scannedWindow = scanned;
            LOGGER.info(() -> "behavior-learning.infer userId=" + userId + ", skill=" + skillName + ", defaults=" + defaults + ", scannedWindow=" + scannedWindow);
        }
        return defaults.isEmpty() ? Map.of() : Map.copyOf(defaults);
    }

    private Map<String, String> extractBehaviorParams(String skillName, String input) {
        if (input == null || input.isBlank()) {
            return Map.of();
        }
        Map<String, String> extracted = new LinkedHashMap<>();
        Optional<SkillDsl> parsed = skillDslParser.parse(input);
        if (parsed.isPresent() && normalize(skillName).equals(normalize(parsed.get().skill()))) {
            parsed.get().input().forEach((key, value) -> {
                if (key == null || key.isBlank() || value == null) {
                    return;
                }
                String normalized = String.valueOf(value).trim();
                if (!normalized.isBlank()) {
                    extracted.put(key, capText(normalized, 48));
                }
            });
        }
        if ("todo.create".equals(normalize(skillName)) && !extracted.containsKey("dueDate")) {
            String dueDate = extractByPattern(input, TODO_DUE_DATE_PATTERN);
            if (dueDate != null && !dueDate.isBlank()) {
                extracted.put("dueDate", capText(dueDate.trim(), 40));
            }
        }
        return extracted.isEmpty() ? Map.of() : Map.copyOf(extracted);
    }

    private String buildBehaviorProfileSummary(String userId) {
        List<ProceduralMemoryEntry> history = dispatcherMemoryFacade.getSkillUsageHistory(userId);
        if (history.isEmpty()) {
            return "";
        }
        List<ProceduralMemoryEntry> window = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && window.size() < behaviorLearningWindowSize; i--) {
            ProceduralMemoryEntry entry = history.get(i);
            if (entry != null && entry.success() && isHabitEligibleSkill(entry.skillName())) {
                window.add(0, entry);
            }
        }
        if (window.size() < 2) {
            return "";
        }
        Map<String, Integer> intentCounts = new LinkedHashMap<>();
        for (ProceduralMemoryEntry entry : window) {
            intentCounts.merge(normalize(entry.skillName()), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> topIntents = intentCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .toList();
        List<String> intentParts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : topIntents) {
            intentParts.add(entry.getKey() + "(" + entry.getValue() + ")");
        }

        Map<String, String> defaults = inferDefaultParamsFromHistory(
                userId,
                topIntents.isEmpty() ? "" : topIntents.get(0).getKey()
        );
        List<String> defaultParts = defaults.entrySet().stream()
                .limit(3)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList();

        Map<String, Integer> sequenceCounts = new LinkedHashMap<>();
        for (int i = 1; i < window.size(); i++) {
            String pair = normalize(window.get(i - 1).skillName()) + "->" + normalize(window.get(i).skillName());
            sequenceCounts.merge(pair, 1, Integer::sum);
        }
        Map.Entry<String, Integer> topSequence = sequenceCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElse(null);

        StringBuilder summary = new StringBuilder("behavior-profile intents=");
        summary.append(String.join(";", intentParts));
        if (!defaultParts.isEmpty()) {
            summary.append(", defaults=").append(String.join(";", defaultParts));
        }
        if (topSequence != null && topSequence.getValue() > 1) {
            summary.append(", sequence=").append(topSequence.getKey()).append("(").append(topSequence.getValue()).append(")");
        }
        return capText(summary.toString(), 360);
    }

    private String inferMemoryBucketBySkill(String skillName) {
        String normalized = normalize(skillName);
        if (normalized.startsWith("todo") || normalized.contains("task")) {
            return "task";
        }
        if (normalized.contains("teach") || normalized.contains("plan")) {
            return "learning";
        }
        if (normalized.contains("eq") || normalized.contains("coach")) {
            return "eq";
        }
        if (normalized.contains("code") || normalized.contains("file")) {
            return "coding";
        }
        return "general";
    }

    private String extractByPattern(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private boolean isHabitEligibleSkill(String skillName) {
        return habitEligibleChecker != null && habitEligibleChecker.test(skillName);
    }

    private boolean isBlankValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String stringValue) {
            return stringValue.isBlank();
        }
        if (value instanceof List<?> listValue) {
            return listValue.isEmpty();
        }
        return false;
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
