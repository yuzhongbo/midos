package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HumanRuntimeSession {

    private final HumanIntent intent;
    private final Deque<Approval> approvals;
    private final Deque<HumanFeedback> feedbackQueue;
    private final Map<String, Object> attributes;
    private final Instant startedAt;

    private HumanRuntimeSession(HumanIntent intent,
                                Deque<Approval> approvals,
                                Deque<HumanFeedback> feedbackQueue,
                                Map<String, Object> attributes,
                                Instant startedAt) {
        this.intent = intent == null ? HumanIntent.empty() : intent;
        this.approvals = approvals == null ? new ArrayDeque<>() : approvals;
        this.feedbackQueue = feedbackQueue == null ? new ArrayDeque<>() : feedbackQueue;
        this.attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
        this.startedAt = startedAt == null ? Instant.now() : startedAt;
    }

    public static HumanRuntimeSession from(String userId,
                                           Goal goal,
                                           Map<String, Object> profileContext) {
        LinkedHashMap<String, Object> attributes = new LinkedHashMap<>(profileContext == null ? Map.of() : profileContext);
        HumanIntent intent = new HumanIntent(
                normalize(userId),
                stringValue(attributes.get("human.intent.goal"), goal == null ? "" : goal.description()),
                numberValue(attributes.get("human.preference.autonomy"), 0.6),
                numberValue(attributes.get("human.preference.riskTolerance"), 0.5),
                numberValue(attributes.get("human.preference.costSensitivity"), 0.5),
                stringValue(attributes.get("human.preference.style"), "balanced"),
                stringValue(attributes.get("human.intent.notes"), ""),
                attributes,
                Instant.now()
        );
        return new HumanRuntimeSession(
                intent,
                approvalQueue(intent.userId(), attributes),
                feedbackQueue(intent.userId(), goal == null ? "" : goal.goalId(), attributes),
                attributes,
                Instant.now()
        );
    }

    public static HumanRuntimeSession empty() {
        return new HumanRuntimeSession(HumanIntent.empty(), new ArrayDeque<>(), new ArrayDeque<>(), Map.of(), Instant.now());
    }

    public HumanIntent intent() {
        return intent;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Approval nextApproval() {
        return approvals.isEmpty() ? null : approvals.pollFirst();
    }

    public HumanFeedback nextFeedback() {
        return feedbackQueue.isEmpty() ? null : feedbackQueue.pollFirst();
    }

    private static Deque<Approval> approvalQueue(String userId, Map<String, Object> attributes) {
        ArrayDeque<Approval> queue = new ArrayDeque<>();
        Object rawQueue = attributes.get("human.approval.queue");
        if (rawQueue instanceof List<?> list) {
            for (Object item : list) {
                Approval approval = approvalFrom(userId, item);
                if (approval != null) {
                    queue.add(approval);
                }
            }
        }
        if (queue.isEmpty() && hasAny(attributes, "human.approval.status", "human.approval.reason", "human.approval.override")) {
            Approval approval = approvalFrom(userId, attributes);
            if (approval != null) {
                queue.add(approval);
            }
        }
        return queue;
    }

    private static Deque<HumanFeedback> feedbackQueue(String userId, String goalId, Map<String, Object> attributes) {
        ArrayDeque<HumanFeedback> queue = new ArrayDeque<>();
        Object rawQueue = attributes.get("human.feedback.queue");
        if (rawQueue instanceof List<?> list) {
            for (Object item : list) {
                HumanFeedback feedback = feedbackFrom(userId, goalId, item);
                if (feedback != null && feedback.present()) {
                    queue.add(feedback);
                }
            }
        }
        if (queue.isEmpty() && hasAny(attributes, "human.feedback.score", "human.feedback.notes", "human.feedback.rollback", "human.feedback.corrections")) {
            HumanFeedback feedback = feedbackFrom(userId, goalId, attributes);
            if (feedback != null && feedback.present()) {
                queue.add(feedback);
            }
        }
        return queue;
    }

    private static Approval approvalFrom(String userId, Object raw) {
        if (raw instanceof Approval approval) {
            return approval;
        }
        Map<String, Object> values = mapValue(raw);
        if (values.isEmpty()) {
            String status = raw == null ? "" : String.valueOf(raw).trim();
            if (status.isBlank()) {
                return null;
            }
            return new Approval(parseApprovalStatus(status), normalize(userId), "", Map.of(), Instant.now());
        }
        return new Approval(
                parseApprovalStatus(stringValue(values.get("status"), "pending")),
                stringValue(values.get("approverId"), normalize(userId)),
                stringValue(values.get("reason"), ""),
                mapValue(values.getOrDefault("overrideAttributes", values.get("override"))),
                Instant.now()
        );
    }

    private static HumanFeedback feedbackFrom(String userId, String goalId, Object raw) {
        if (raw instanceof HumanFeedback feedback) {
            return feedback;
        }
        Map<String, Object> values = mapValue(raw);
        if (values.isEmpty()) {
            return null;
        }
        return new HumanFeedback(
                normalize(userId),
                goalId == null ? "" : goalId.trim(),
                stringValue(values.get("taskId"), ""),
                numberValue(values.getOrDefault("satisfactionScore", values.get("score")), 0.5),
                booleanValue(values.getOrDefault("approvedOutcome", values.get("approved")), true),
                booleanValue(values.getOrDefault("requestRollback", values.get("rollback")), false),
                booleanValue(values.getOrDefault("requestInterrupt", values.get("interrupt")), false),
                nullableNumber(values.getOrDefault("suggestedAutonomy", values.get("autonomyPreference"))),
                nullableNumber(values.getOrDefault("suggestedRiskTolerance", values.get("riskTolerance"))),
                nullableNumber(values.getOrDefault("suggestedCostSensitivity", values.get("costSensitivity"))),
                stringValue(values.getOrDefault("preferredStyle", values.get("style")), ""),
                stringValue(values.get("language"), ""),
                stringValue(values.getOrDefault("preferredChannel", values.get("channel")), ""),
                stringValue(values.get("notes"), ""),
                mapValue(values.getOrDefault("corrections", values.get("params"))),
                Instant.now()
        );
    }

    private static ApprovalStatus parseApprovalStatus(String rawStatus) {
        String normalized = rawStatus == null ? "" : rawStatus.trim().toUpperCase(Locale.ROOT);
        if ("APPROVED".equals(normalized) || "APPROVE".equals(normalized)) {
            return ApprovalStatus.APPROVED;
        }
        if ("REJECTED".equals(normalized) || "REJECT".equals(normalized) || "DENIED".equals(normalized)) {
            return ApprovalStatus.REJECTED;
        }
        if ("MODIFIED".equals(normalized) || "MODIFY".equals(normalized) || "OVERRIDE".equals(normalized)) {
            return ApprovalStatus.MODIFIED;
        }
        return ApprovalStatus.PENDING;
    }

    private static Map<String, Object> mapValue(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    values.put(String.valueOf(key), value);
                }
            });
            return values;
        }
        return Map.of();
    }

    private static String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? fallback : normalized;
    }

    private static double numberValue(Object value, double fallback) {
        Double parsed = nullableNumber(value);
        return parsed == null ? fallback : parsed;
    }

    private static Double nullableNumber(Object value) {
        if (value instanceof Number number) {
            return clamp(number.doubleValue());
        }
        if (value == null) {
            return null;
        }
        try {
            return clamp(Double.parseDouble(String.valueOf(value).trim()));
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return fallback;
        }
        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "yes".equals(normalized) || "1".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "no".equals(normalized) || "0".equals(normalized)) {
            return false;
        }
        return fallback;
    }

    private static boolean hasAny(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String userId) {
        return userId == null ? "" : userId.trim();
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
