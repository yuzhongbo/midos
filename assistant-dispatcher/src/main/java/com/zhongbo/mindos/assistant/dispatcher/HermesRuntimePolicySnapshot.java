package com.zhongbo.mindos.assistant.dispatcher;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

record HermesRuntimePolicySnapshot(
        String snapshotId,
        String source,
        Instant createdAt,
        double memorySuccessBoostWeight,
        double graphBoostWeight,
        double graphContinuationBaseScore,
        double searchPriorityLeadBoost,
        double builtinNewsLeadBoost,
        String strategySummary,
        List<String> reconstructionActions,
        Map<String, Double> skillScoreAdjustments,
        Map<String, String> skillAdjustmentReasons
) {

    private static final double DEFAULT_MEMORY_SUCCESS_BOOST_WEIGHT = 0.20d;
    private static final double DEFAULT_GRAPH_BOOST_WEIGHT = 0.25d;
    private static final double DEFAULT_GRAPH_CONTINUATION_BASE_SCORE = 0.74d;
    private static final double DEFAULT_SEARCH_PRIORITY_LEAD_BOOST = 0.02d;
    private static final double DEFAULT_BUILTIN_NEWS_LEAD_BOOST = 0.03d;
    private static final double MAX_ABSOLUTE_SKILL_ADJUSTMENT = 0.25d;

    HermesRuntimePolicySnapshot {
        snapshotId = snapshotId == null || snapshotId.isBlank() ? "policy-" + UUID.randomUUID() : snapshotId.trim();
        source = source == null || source.isBlank() ? "runtime-default" : source.trim();
        createdAt = createdAt == null ? Instant.now() : createdAt;
        memorySuccessBoostWeight = clamp(memorySuccessBoostWeight, 0.0d, 1.0d);
        graphBoostWeight = clamp(graphBoostWeight, 0.0d, 1.0d);
        graphContinuationBaseScore = clamp(graphContinuationBaseScore, 0.0d, 1.0d);
        searchPriorityLeadBoost = clamp(searchPriorityLeadBoost, 0.0d, 0.20d);
        builtinNewsLeadBoost = clamp(builtinNewsLeadBoost, 0.0d, 0.20d);
        strategySummary = strategySummary == null ? "" : strategySummary.trim();
        reconstructionActions = reconstructionActions == null ? List.of() : List.copyOf(reconstructionActions);
        skillScoreAdjustments = immutableAdjustments(skillScoreAdjustments);
        skillAdjustmentReasons = immutableReasons(skillAdjustmentReasons);
    }

    static HermesRuntimePolicySnapshot defaults() {
        return new HermesRuntimePolicySnapshot(
                "policy-default",
                "runtime-default",
                Instant.EPOCH,
                DEFAULT_MEMORY_SUCCESS_BOOST_WEIGHT,
                DEFAULT_GRAPH_BOOST_WEIGHT,
                DEFAULT_GRAPH_CONTINUATION_BASE_SCORE,
                DEFAULT_SEARCH_PRIORITY_LEAD_BOOST,
                DEFAULT_BUILTIN_NEWS_LEAD_BOOST,
                "",
                List.of(),
                Map.of(),
                Map.of()
        );
    }

    HermesRuntimePolicySnapshot(String snapshotId,
                                String source,
                                Instant createdAt,
                                double memorySuccessBoostWeight,
                                double graphBoostWeight,
                                double graphContinuationBaseScore,
                                double searchPriorityLeadBoost,
                                double builtinNewsLeadBoost,
                                Map<String, Double> skillScoreAdjustments,
                                Map<String, String> skillAdjustmentReasons) {
        this(
                snapshotId,
                source,
                createdAt,
                memorySuccessBoostWeight,
                graphBoostWeight,
                graphContinuationBaseScore,
                searchPriorityLeadBoost,
                builtinNewsLeadBoost,
                "",
                List.of(),
                skillScoreAdjustments,
                skillAdjustmentReasons
        );
    }

    double skillScoreAdjustment(HermesSkillIdentity skillIdentity) {
        if (skillIdentity == null || skillScoreAdjustments.isEmpty()) {
            return 0.0d;
        }
        Double value = firstMatchingValue(skillScoreAdjustments, skillIdentity);
        return value == null ? 0.0d : value;
    }

    String adjustmentReason(HermesSkillIdentity skillIdentity) {
        if (skillIdentity == null || skillAdjustmentReasons.isEmpty()) {
            return "";
        }
        String reason = firstMatchingValue(skillAdjustmentReasons, skillIdentity);
        return reason == null ? "" : reason;
    }

    private static <T> T firstMatchingValue(Map<String, T> values, HermesSkillIdentity skillIdentity) {
        if (values == null || values.isEmpty() || skillIdentity == null) {
            return null;
        }
        T value = values.get(normalize(skillIdentity.decisionTarget()));
        if (value != null) {
            return value;
        }
        value = values.get(normalize(skillIdentity.executionTarget()));
        if (value != null) {
            return value;
        }
        return values.get(normalize(skillIdentity.canonicalSkill()));
    }

    private static Map<String, Double> immutableAdjustments(Map<String, Double> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Double> normalized = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            String normalizedKey = normalize(key);
            if (normalizedKey.isBlank() || value == null) {
                return;
            }
            normalized.put(normalizedKey, clamp(value, -MAX_ABSOLUTE_SKILL_ADJUSTMENT, MAX_ABSOLUTE_SKILL_ADJUSTMENT));
        });
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }

    private static Map<String, String> immutableReasons(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            String normalizedKey = normalize(key);
            String normalizedValue = value == null ? "" : value.trim();
            if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                return;
            }
            normalized.put(normalizedKey, normalizedValue);
        });
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
