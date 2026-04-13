package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;

import java.util.LinkedHashMap;
import java.util.Map;

final class ConfidenceGate {

    private static final double DEFAULT_THRESHOLD = 0.60;

    private final double threshold;

    ConfidenceGate() {
        this(resolveThreshold());
    }

    ConfidenceGate(double threshold) {
        this.threshold = Math.max(0.0, Math.min(1.0, threshold));
    }

    Decision check(Decision decision) {
        if (decision == null || decision.requireClarify()) {
            return decision;
        }
        if (decision.target() == null || decision.target().isBlank()) {
            return clarify(decision, "需要更多信息才能确定执行目标。");
        }
        if (decision.confidence() >= threshold) {
            return decision;
        }
        return clarify(
                decision,
                "当前决策置信度不足（" + decision.confidence() + " < " + threshold + "），请确认关键参数或真实意图后再执行。"
        );
    }

    private Decision clarify(Decision decision, String message) {
        Map<String, Object> params = new LinkedHashMap<>(decision.params() == null ? Map.of() : decision.params());
        params.put(FinalPlanner.PLANNER_CLARIFY_MESSAGE_KEY, message == null ? "" : message.trim());
        return new Decision(
                decision.intent(),
                decision.target(),
                params.isEmpty() ? Map.of() : Map.copyOf(params),
                decision.confidence(),
                true
        );
    }

    private static double resolveThreshold() {
        String raw = System.getProperty("mindos.dispatcher.planner2.confidence-threshold");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_THRESHOLD;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return DEFAULT_THRESHOLD;
        }
    }
}
