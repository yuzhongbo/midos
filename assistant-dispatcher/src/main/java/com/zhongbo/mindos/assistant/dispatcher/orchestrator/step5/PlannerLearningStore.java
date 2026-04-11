package com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public interface PlannerLearningStore {

    LearningSnapshot observe(String userId,
                             String skillName,
                             String routeType,
                             boolean success,
                             long latencyMs,
                             int tokenEstimate,
                             boolean usedFallback,
                             double reward);

    LearningSnapshot snapshot(String userId, String skillName);

    record LearningSnapshot(double score,
                            double successRate,
                            double latencyScore,
                            double tokenScore,
                            double rewardScore,
                            double averageReward,
                            String preferredRoute,
                            long sampleCount,
                            List<String> reasons) {

        public LearningSnapshot {
            score = clamp(score);
            successRate = clamp(successRate);
            latencyScore = clamp(latencyScore);
            tokenScore = clamp(tokenScore);
            rewardScore = clamp(rewardScore);
            if (Double.isNaN(averageReward) || Double.isInfinite(averageReward)) {
                averageReward = 0.0;
            }
            preferredRoute = normalize(preferredRoute);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        public static LearningSnapshot neutral() {
            return new LearningSnapshot(0.5, 0.5, 0.5, 0.5, 0.5, 0.0, "auto", 0L, List.of("neutral"));
        }

        public Map<String, Object> asMap() {
            return Map.of(
                    "score", score,
                    "successRate", successRate,
                    "latencyScore", latencyScore,
                    "tokenScore", tokenScore,
                    "rewardScore", rewardScore,
                    "averageReward", averageReward,
                    "preferredRoute", preferredRoute,
                    "sampleCount", sampleCount,
                    "reasons", reasons
            );
        }

        private static double clamp(double value) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return 0.5;
            }
            return Math.max(0.0, Math.min(1.0, value));
        }

        private static String normalize(String value) {
            if (value == null) {
                return "auto";
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return normalized.isBlank() ? "auto" : normalized;
        }
    }
}
