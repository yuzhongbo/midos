package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

final class HabitSkillSelector {

    private static final List<String> HABIT_CONTINUATION_CUES = List.of(
            "继续",
            "按之前",
            "按上次",
            "沿用",
            "还是那个",
            "同样方式",
            "按照我的习惯",
            "根据我的习惯"
    );

    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final SkillCommandAssembler skillCommandAssembler;
    private final boolean habitRoutingEnabled;
    private final int habitRoutingMinTotalCount;
    private final double habitRoutingMinSuccessRate;
    private final int habitContinuationInputMaxLength;
    private final int habitRoutingRecentWindowSize;
    private final int habitRoutingRecentMinSuccessCount;
    private final double habitRoutingRecentMaxAgeHours;

    HabitSkillSelector(DispatcherMemoryFacade dispatcherMemoryFacade,
                       SkillCommandAssembler skillCommandAssembler,
                       boolean habitRoutingEnabled,
                       int habitRoutingMinTotalCount,
                       double habitRoutingMinSuccessRate,
                       int habitContinuationInputMaxLength,
                       int habitRoutingRecentWindowSize,
                       int habitRoutingRecentMinSuccessCount,
                       double habitRoutingRecentMaxAgeHours) {
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.skillCommandAssembler = skillCommandAssembler;
        this.habitRoutingEnabled = habitRoutingEnabled;
        this.habitRoutingMinTotalCount = habitRoutingMinTotalCount;
        this.habitRoutingMinSuccessRate = habitRoutingMinSuccessRate;
        this.habitContinuationInputMaxLength = habitContinuationInputMaxLength;
        this.habitRoutingRecentWindowSize = habitRoutingRecentWindowSize;
        this.habitRoutingRecentMinSuccessCount = habitRoutingRecentMinSuccessCount;
        this.habitRoutingRecentMaxAgeHours = habitRoutingRecentMaxAgeHours;
    }

    List<DecisionSignal> recommend(RecommendationInput input) {
        if (input == null || !habitRoutingEnabled || input.userInput() == null || input.userInput().isBlank()) {
            return List.of();
        }
        if (!isContinuationIntent(normalize(input.userInput()))) {
            return List.of();
        }
        String userId = input.userId() == null ? "" : input.userId();
        List<ProceduralMemoryEntry> history = dispatcherMemoryFacade.getSkillUsageHistory(userId);
        List<DecisionSignal> recommendations = new ArrayList<>();
        preferredSkillFromHistory(history)
                .filter(skill -> passesHabitConfidenceGate(userId, skill, history))
                .filter(skill -> !isBlocked(skill, input.loopGuardBlocked()))
                .ifPresent(skill -> recommendations.add(new DecisionSignal(skill, 0.89, "memory")));
        preferredSkillFromStats(userId)
                .filter(skill -> recommendations.stream().noneMatch(candidate -> skill.equals(candidate.target())))
                .filter(skill -> passesHabitConfidenceGate(userId, skill, history))
                .filter(skill -> !isBlocked(skill, input.loopGuardBlocked()))
                .ifPresent(skill -> recommendations.add(new DecisionSignal(skill, 0.87, "memory")));
        return recommendations.stream()
                .sorted(Comparator.comparingDouble(DecisionSignal::score).reversed()
                        .thenComparing(DecisionSignal::target))
                .toList();
    }

    Optional<SkillDsl> buildSkillDsl(RecommendationInput input, DecisionSignal candidate) {
        if (input == null || candidate == null || candidate.target().isBlank()) {
            return Optional.empty();
        }
        return toSkillDslByHabit(
                input.userId() == null ? "" : input.userId(),
                candidate.target(),
                input.userInput(),
                input.profileContext() == null ? Map.of() : input.profileContext()
        );
    }

    Optional<String> preferredSkillFromHistory(List<ProceduralMemoryEntry> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ProceduralMemoryEntry entry = history.get(i);
            if (entry != null && entry.success() && isHabitEligibleSkill(entry.skillName())) {
                return Optional.of(entry.skillName());
            }
        }
        return Optional.empty();
    }

    Optional<String> preferredSkillFromStats(String userId) {
        return dispatcherMemoryFacade.getSkillUsageStats(userId).stream()
                .filter(stats -> isHabitEligibleSkill(stats.skillName()))
                .filter(stats -> stats.totalCount() >= habitRoutingMinTotalCount)
                .filter(stats -> stats.successCount() * 1.0 / Math.max(1, stats.totalCount()) >= habitRoutingMinSuccessRate)
                .max(Comparator.comparingLong(SkillUsageStats::successCount))
                .map(SkillUsageStats::skillName);
    }

    Map<String, Object> extractTeachingPlanPayload(String userInput) {
        return skillCommandAssembler.extractTeachingPlanPayload(userInput);
    }

    boolean isHabitEligibleSkill(String skillName) {
        String normalized = normalize(skillName);
        if (normalized.isBlank()) {
            return false;
        }
        if ("llm".equals(normalized) || "security.guard".equals(normalized) || "reflection".equals(normalized)) {
            return false;
        }
        return !normalized.startsWith("memory.")
                && !normalized.startsWith("semantic.")
                && !normalized.startsWith("policy.")
                && !normalized.startsWith("planner.")
                && !normalized.startsWith("reflection.")
                && !normalized.startsWith("strategy.")
                && !normalized.startsWith("autonomous.");
    }

    String sanitizeContinuationPrefix(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceFirst("^(继续|按之前|按上次|沿用|同样方式|还是那个)[，,、 ]*", "").trim();
    }

    boolean isContinuationOnlyInput(String userInput) {
        String normalized = normalize(userInput);
        return isContinuationIntent(normalized)
                && normalized.length() <= habitContinuationInputMaxLength;
    }

    boolean isContinuationIntent(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        for (String cue : HABIT_CONTINUATION_CUES) {
            int index = normalized.indexOf(cue);
            if (index >= 0 && index <= 2) {
                return true;
            }
        }
        return false;
    }

    private boolean passesHabitConfidenceGate(String userId,
                                              String preferredSkill,
                                              List<ProceduralMemoryEntry> history) {
        if (preferredSkill == null || preferredSkill.isBlank() || history == null || history.isEmpty()) {
            return false;
        }
        if (!passesStatsThreshold(userId, preferredSkill)) {
            return false;
        }

        int scanned = 0;
        int successCount = 0;
        Instant lastSuccessAt = null;
        for (int i = history.size() - 1; i >= 0 && scanned < habitRoutingRecentWindowSize; i--) {
            ProceduralMemoryEntry entry = history.get(i);
            scanned++;
            if (!entry.success() || !preferredSkill.equals(entry.skillName())) {
                continue;
            }
            successCount++;
            if (lastSuccessAt == null || (entry.createdAt() != null && entry.createdAt().isAfter(lastSuccessAt))) {
                lastSuccessAt = entry.createdAt();
            }
        }
        if (successCount < habitRoutingRecentMinSuccessCount || lastSuccessAt == null) {
            return false;
        }

        double ageHours = Math.max(0.0, Duration.between(lastSuccessAt, Instant.now()).toMillis() / 3_600_000d);
        return ageHours <= habitRoutingRecentMaxAgeHours;
    }

    private boolean passesStatsThreshold(String userId, String skillName) {
        return dispatcherMemoryFacade.getSkillUsageStats(userId).stream()
                .filter(stats -> isHabitEligibleSkill(stats.skillName()))
                .filter(stats -> skillName.equals(stats.skillName()))
                .anyMatch(stats -> stats.totalCount() >= habitRoutingMinTotalCount
                        && stats.successCount() * 1.0 / Math.max(1, stats.totalCount()) >= habitRoutingMinSuccessRate);
    }

    private Optional<SkillDsl> toSkillDslByHabit(String userId,
                                                 String skillName,
                                                 String userInput,
                                                 Map<String, Object> profileContext) {
        return skillCommandAssembler.buildHabitSkillDsl(
                skillName,
                userInput,
                profileContext,
                isContinuationOnlyInput(userInput),
                findLastSuccessfulSkillInput(userId, skillName)
        );
    }

    private Optional<String> findLastSuccessfulSkillInput(String userId, String skillName) {
        List<ProceduralMemoryEntry> history = dispatcherMemoryFacade.getSkillUsageHistory(userId);
        for (int i = history.size() - 1; i >= 0; i--) {
            ProceduralMemoryEntry entry = history.get(i);
            if (entry.success()
                    && skillName.equals(entry.skillName())
                    && entry.input() != null
                    && !entry.input().isBlank()) {
                return Optional.of(entry.input());
            }
        }
        return Optional.empty();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private boolean isBlocked(String skillName, Predicate<String> loopGuardBlocked) {
        return loopGuardBlocked != null && loopGuardBlocked.test(skillName);
    }

    record RecommendationInput(String userId,
                               String userInput,
                               Map<String, Object> profileContext,
                               Predicate<String> loopGuardBlocked) {
    }
}
