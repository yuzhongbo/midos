package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.TaskHandle;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SharedDecision(TaskHandle handle,
                             String taskId,
                             DecisionMode mode,
                             boolean allowExecution,
                             boolean requiresHumanApproval,
                             Approval approval,
                             Explanation explanation,
                             HumanPreference preference,
                             double trustScore,
                             double confidence,
                             double risk,
                             double cost,
                             List<String> targets,
                             Map<String, Object> overrides,
                             Instant decidedAt) {

    public SharedDecision {
        handle = handle == null ? new TaskHandle(taskId) : handle;
        taskId = taskId == null ? "" : taskId.trim();
        mode = mode == null ? DecisionMode.JOINT_REVIEW : mode;
        approval = approval == null ? Approval.pending("awaiting-human-decision") : approval;
        explanation = explanation == null ? Explanation.empty() : explanation;
        preference = preference == null ? HumanPreference.defaultPreference() : preference;
        trustScore = clamp(trustScore, 0.0);
        confidence = clamp(confidence, 0.0);
        risk = clamp(risk, 0.0);
        cost = clamp(cost, 0.0);
        targets = targets == null ? List.of() : List.copyOf(targets);
        overrides = overrides == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(overrides));
        decidedAt = decidedAt == null ? Instant.now() : decidedAt;
    }

    public boolean waitingForHuman() {
        return requiresHumanApproval && !allowExecution;
    }

    public boolean hasOverrides() {
        return !overrides.isEmpty();
    }

    public SharedDecision withExplanation(Explanation nextExplanation) {
        return new SharedDecision(
                handle,
                taskId,
                mode,
                allowExecution,
                requiresHumanApproval,
                approval,
                nextExplanation,
                preference,
                trustScore,
                confidence,
                risk,
                cost,
                targets,
                overrides,
                decidedAt
        );
    }

    public Map<String, Object> attributes() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("coruntime.mode", mode.name());
        values.put("coruntime.allowExecution", allowExecution);
        values.put("coruntime.requiresApproval", requiresHumanApproval);
        values.put("coruntime.approvalStatus", approval.status().name());
        values.put("coruntime.approvalReason", approval.reason());
        values.put("coruntime.trustScore", trustScore);
        values.put("coruntime.confidence", confidence);
        values.put("coruntime.risk", risk);
        values.put("coruntime.cost", cost);
        values.put("coruntime.targets", targets);
        values.put("coruntime.preference", preference.asMap());
        values.put("coruntime.explanation.summary", explanation.summary());
        values.put("coruntime.explanation.reasons", explanation.reasons());
        values.put("coruntime.explanation.risks", explanation.risks());
        if (!overrides.isEmpty()) {
            values.put("coruntime.override", overrides);
        }
        return Map.copyOf(values);
    }

    private static double clamp(double value, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
