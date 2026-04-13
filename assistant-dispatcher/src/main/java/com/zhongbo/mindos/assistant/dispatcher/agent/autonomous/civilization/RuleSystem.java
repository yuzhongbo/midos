package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class RuleSystem {

    private final CopyOnWriteArrayList<Rule> rules = new CopyOnWriteArrayList<>(List.of(
            new Rule("civilization-resource-paid", "Resources must be paid before execution", RuleType.RESOURCE_MUST_BE_PAID, 0.0, true),
            new Rule("civilization-market-mediated", "Cross-organization work must be market-mediated", RuleType.CROSS_ORG_AGENT_DIRECT_CALL_BLOCKED, 0.0, true),
            new Rule("civilization-task-cost", "Each task must carry explicit economic cost", RuleType.TASK_COST_REQUIRED, 0.01, true),
            new Rule("civilization-high-cost-approval", "High-cost work requires decentralized approval", RuleType.HIGH_COST_APPROVAL_REQUIRED, 40.0, true)
    ));

    public List<Rule> rules() {
        return List.copyOf(rules);
    }

    public boolean validate(Transaction transaction) {
        if (transaction == null) {
            return false;
        }
        for (Rule rule : rules) {
            if (rule == null || !rule.active()) {
                continue;
            }
            if (!validateRule(rule, transaction)) {
                return false;
            }
        }
        return true;
    }

    public double threshold(RuleType type, double fallback) {
        return rules.stream()
                .filter(rule -> rule != null && rule.type() == type && rule.active())
                .findFirst()
                .map(Rule::threshold)
                .orElse(fallback);
    }

    public void updateThreshold(RuleType type, double nextThreshold) {
        if (type == null) {
            return;
        }
        List<Rule> snapshot = new ArrayList<>(rules);
        for (int index = 0; index < snapshot.size(); index++) {
            Rule rule = snapshot.get(index);
            if (rule != null && rule.type() == type) {
                snapshot.set(index, rule.withThreshold(nextThreshold));
            }
        }
        rules.clear();
        rules.addAll(snapshot);
    }

    public Map<RuleType, Double> thresholds() {
        Map<RuleType, Double> thresholds = new EnumMap<>(RuleType.class);
        for (Rule rule : rules) {
            if (rule != null && rule.active()) {
                thresholds.put(rule.type(), rule.threshold());
            }
        }
        return Map.copyOf(thresholds);
    }

    private boolean validateRule(Rule rule, Transaction transaction) {
        return switch (rule.type()) {
            case RESOURCE_MUST_BE_PAID -> transaction.resourceAmounts().isEmpty() || transaction.totalCost() > rule.threshold();
            case CROSS_ORG_AGENT_DIRECT_CALL_BLOCKED -> transaction.requesterOrgId().equalsIgnoreCase(transaction.providerOrgId()) || transaction.marketMediated();
            case TASK_COST_REQUIRED -> transaction.totalCost() >= rule.threshold();
            case HIGH_COST_APPROVAL_REQUIRED -> transaction.totalCost() <= rule.threshold() || transaction.approvalGranted();
        };
    }
}
