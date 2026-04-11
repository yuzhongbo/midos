package com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

@Component
public class InMemoryPlannerLearningStore implements PlannerLearningStore {

    private final Map<String, Map<String, SkillLearningStats>> statsByUser = new ConcurrentHashMap<>();

    @Override
    public LearningSnapshot observe(String userId,
                                    String skillName,
                                    String routeType,
                                    boolean success,
                                    long latencyMs,
                                    int tokenEstimate,
                                    boolean usedFallback,
                                    double reward) {
        String normalizedUser = normalize(userId);
        String normalizedSkill = normalize(skillName);
        if (normalizedUser.isBlank() || normalizedSkill.isBlank()) {
            return LearningSnapshot.neutral();
        }
        SkillLearningStats stats = statsByUser
                .computeIfAbsent(normalizedUser, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(normalizedSkill, ignored -> new SkillLearningStats());
        stats.observe(routeType, success, latencyMs, tokenEstimate, usedFallback, reward);
        return snapshot(userId, skillName);
    }

    @Override
    public LearningSnapshot snapshot(String userId, String skillName) {
        String normalizedUser = normalize(userId);
        String normalizedSkill = normalize(skillName);
        if (normalizedUser.isBlank() || normalizedSkill.isBlank()) {
            return LearningSnapshot.neutral();
        }
        Map<String, SkillLearningStats> userStats = statsByUser.getOrDefault(normalizedUser, Map.of());
        SkillLearningStats stats = userStats.get(normalizedSkill);
        if (stats == null || stats.totalCount() == 0L) {
            return LearningSnapshot.neutral();
        }

        double successRate = ratio(stats.successCount(), stats.totalCount());
        double averageLatency = stats.averageLatencyMs();
        double averageTokens = stats.averageTokenEstimate();
        double averageReward = stats.averageReward();
        double latencyScore = inverseNormalized(averageLatency, 1_200.0);
        double tokenScore = inverseNormalized(averageTokens, 220.0);
        double rewardScore = normalizeReward(averageReward);
        double fallbackPenalty = Math.min(0.12, ratio(stats.fallbackCount(), stats.totalCount()) * 0.12);
        double score = clamp(0.40 * successRate + 0.18 * latencyScore + 0.10 * tokenScore + 0.32 * rewardScore - fallbackPenalty);
        String preferredRoute = stats.preferredRoute();

        List<String> reasons = new ArrayList<>();
        reasons.add("samples=" + stats.totalCount());
        reasons.add("successRate=" + round(successRate));
        reasons.add("latencyMs=" + Math.round(averageLatency));
        reasons.add("tokenEstimate=" + Math.round(averageTokens));
        reasons.add("rewardAverage=" + round(averageReward));
        reasons.add("rewardScore=" + round(rewardScore));
        reasons.add("preferredRoute=" + preferredRoute);
        if (stats.fallbackCount() > 0L) {
            reasons.add("fallbackCount=" + stats.fallbackCount());
        }
        return new LearningSnapshot(
                score,
                successRate,
                latencyScore,
                tokenScore,
                rewardScore,
                averageReward,
                preferredRoute,
                stats.totalCount(),
                reasons
        );
    }

    private double ratio(long numerator, long denominator) {
        if (denominator <= 0L) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, numerator / (double) denominator));
    }

    private double inverseNormalized(double value, double scale) {
        if (scale <= 0.0) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, 1.0 / (1.0 + Math.max(0.0, value) / scale)));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static double normalizeReward(double averageReward) {
        return Math.max(0.0, Math.min(1.0, 0.5 + averageReward / 3.0));
    }

    private static final class SkillLearningStats {
        private final LongAdder total = new LongAdder();
        private final LongAdder success = new LongAdder();
        private final LongAdder failure = new LongAdder();
        private final LongAdder fallback = new LongAdder();
        private final LongAdder totalLatency = new LongAdder();
        private final LongAdder totalTokens = new LongAdder();
        private final DoubleAdder totalReward = new DoubleAdder();
        private final Map<String, RouteLearningStats> routeStats = new ConcurrentHashMap<>();

        private void observe(String routeType,
                             boolean succeeded,
                             long latencyMs,
                             int tokenEstimate,
                             boolean usedFallback,
                             double reward) {
            total.increment();
            if (succeeded) {
                success.increment();
            } else {
                failure.increment();
            }
            if (usedFallback) {
                fallback.increment();
            }
            totalLatency.add(Math.max(0L, latencyMs));
            totalTokens.add(Math.max(0L, tokenEstimate));
            totalReward.add(reward);
            routeStats.computeIfAbsent(routeType == null || routeType.isBlank()
                    ? "auto"
                    : routeType.trim().toLowerCase(Locale.ROOT), ignored -> new RouteLearningStats())
                    .observe(succeeded, latencyMs, tokenEstimate, usedFallback, reward);
        }

        private long totalCount() {
            return total.sum();
        }

        private long successCount() {
            return success.sum();
        }

        private long fallbackCount() {
            return fallback.sum();
        }

        private double averageLatencyMs() {
            long count = totalCount();
            return count <= 0L ? 0.0 : totalLatency.sum() / (double) count;
        }

        private double averageTokenEstimate() {
            long count = totalCount();
            return count <= 0L ? 0.0 : totalTokens.sum() / (double) count;
        }

        private double averageReward() {
            long count = totalCount();
            return count <= 0L ? 0.0 : totalReward.sum() / (double) count;
        }

        private String preferredRoute() {
            return routeStats.entrySet().stream()
                    .map(entry -> Map.entry(entry.getKey(), entry.getValue().score()))
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("auto");
        }

    }

    private static final class RouteLearningStats {
        private final LongAdder total = new LongAdder();
        private final LongAdder success = new LongAdder();
        private final LongAdder fallback = new LongAdder();
        private final LongAdder totalLatency = new LongAdder();
        private final LongAdder totalTokens = new LongAdder();
        private final DoubleAdder totalReward = new DoubleAdder();

        private void observe(boolean succeeded,
                             long latencyMs,
                             int tokenEstimate,
                             boolean usedFallback,
                             double reward) {
            total.increment();
            if (succeeded) {
                success.increment();
            }
            if (usedFallback) {
                fallback.increment();
            }
            totalLatency.add(Math.max(0L, latencyMs));
            totalTokens.add(Math.max(0L, tokenEstimate));
            totalReward.add(reward);
        }

        private double score() {
            long count = total.sum();
            if (count <= 0L) {
                return 0.0;
            }
            double successRate = success.sum() / (double) count;
            double latencyScore = 1.0 / (1.0 + averageLatencyMs() / 900.0);
            double tokenScore = 1.0 / (1.0 + averageTokenEstimate() / 200.0);
            double rewardScore = normalizeReward(averageReward());
            double fallbackPenalty = Math.min(0.08, fallback.sum() / (double) count * 0.08);
            return Math.max(0.0, Math.min(1.0, 0.56 * successRate + 0.16 * latencyScore + 0.10 * tokenScore + 0.18 * rewardScore - fallbackPenalty));
        }

        private double averageLatencyMs() {
            long count = total.sum();
            return count <= 0L ? 0.0 : totalLatency.sum() / (double) count;
        }

        private double averageTokenEstimate() {
            long count = total.sum();
            return count <= 0L ? 0.0 : totalTokens.sum() / (double) count;
        }

        private double averageReward() {
            long count = total.sum();
            return count <= 0L ? 0.0 : totalReward.sum() / (double) count;
        }
    }
}
