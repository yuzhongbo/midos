package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ExplainabilityEngine {

    public Explanation explain(SharedDecision decision) {
        if (decision == null) {
            return Explanation.empty();
        }
        List<String> reasons = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        reasons.add("mode=" + decision.mode().name().toLowerCase(Locale.ROOT));
        reasons.add("confidence=" + round(decision.confidence()));
        reasons.add("trust=" + round(decision.trustScore()));
        reasons.add("preference.autonomy=" + round(decision.preference().autonomyLevel()));
        if (decision.requiresHumanApproval()) {
            reasons.add("human-review-required");
        }
        if (decision.approval() != null && !decision.approval().reason().isBlank()) {
            reasons.add("approval.reason=" + decision.approval().reason());
        }
        if (!decision.targets().isEmpty()) {
            reasons.add("targets=" + decision.targets());
        }
        if (decision.risk() >= 0.5) {
            risks.add("predicted-risk=" + round(decision.risk()));
        }
        if (decision.cost() >= 0.5) {
            risks.add("predicted-cost=" + round(decision.cost()));
        }
        if (decision.confidence() < 0.55) {
            risks.add("low-confidence=" + round(decision.confidence()));
        }
        if (decision.targets().isEmpty()) {
            risks.add("no-executable-targets");
        }
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("taskId", decision.taskId());
        evidence.put("mode", decision.mode().name());
        evidence.put("allowExecution", decision.allowExecution());
        evidence.put("requiresHumanApproval", decision.requiresHumanApproval());
        evidence.put("approvalStatus", decision.approval().status().name());
        evidence.put("confidence", decision.confidence());
        evidence.put("risk", decision.risk());
        evidence.put("cost", decision.cost());
        evidence.put("trustScore", decision.trustScore());
        evidence.put("targets", decision.targets());
        evidence.put("preference", decision.preference().asMap());
        String summary = buildSummary(decision);
        return new Explanation(summary, reasons, risks, Map.copyOf(evidence), Instant.now());
    }

    private String buildSummary(SharedDecision decision) {
        if (decision.waitingForHuman()) {
            return "waiting-human-review";
        }
        if (decision.hasOverrides()) {
            return "human-modified-plan";
        }
        if (decision.allowExecution() && decision.mode() == DecisionMode.AI_AUTONOMOUS) {
            return "ai-autonomous-execution";
        }
        if (decision.allowExecution()) {
            return "joint-approved-execution";
        }
        return "shared-decision-hold";
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
