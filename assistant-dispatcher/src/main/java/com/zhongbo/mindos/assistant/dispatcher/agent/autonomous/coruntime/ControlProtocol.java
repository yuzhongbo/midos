package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.ExecutionPolicy;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ControlProtocol {

    private final double approvalRiskThreshold;
    private final double highCostThreshold;
    private final double approvalConfidenceFloor;
    private final double autonomyConfidenceThreshold;
    private final double minTrustToAutonomy;

    public ControlProtocol() {
        this(0.68, 0.75, 0.55, 0.62, 0.45);
    }

    @Autowired
    public ControlProtocol(@Value("${mindos.coruntime.approval-risk-threshold:0.68}") double approvalRiskThreshold,
                           @Value("${mindos.coruntime.high-cost-threshold:0.75}") double highCostThreshold,
                           @Value("${mindos.coruntime.approval-confidence-floor:0.55}") double approvalConfidenceFloor,
                           @Value("${mindos.coruntime.autonomy-confidence-threshold:0.62}") double autonomyConfidenceThreshold,
                           @Value("${mindos.coruntime.min-trust-to-autonomy:0.45}") double minTrustToAutonomy) {
        this.approvalRiskThreshold = clamp(approvalRiskThreshold);
        this.highCostThreshold = clamp(highCostThreshold);
        this.approvalConfidenceFloor = clamp(approvalConfidenceFloor);
        this.autonomyConfidenceThreshold = clamp(autonomyConfidenceThreshold);
        this.minTrustToAutonomy = clamp(minTrustToAutonomy);
    }

    public boolean requireApproval(Task task) {
        return requireApproval(task, new SharedDecisionContext(null, task, null, null, null, null, -1.0, java.util.Map.of()));
    }

    public boolean requireApproval(Task task, SharedDecisionContext context) {
        SharedDecisionContext safeContext = context == null
                ? new SharedDecisionContext(null, task, null, null, null, null, -1.0, java.util.Map.of())
                : context;
        if (safeContext.booleanAttribute("human.approvalRequired", false)
                || safeContext.booleanAttribute("coruntime.forceHumanReview", false)) {
            return true;
        }
        if (safeContext.plan() == null || !safeContext.plan().executable() || safeContext.targets().isEmpty()) {
            return true;
        }
        HumanPreference preference = safeContext.preference();
        double trust = Math.max(0.0, safeContext.trustScore());
        double riskThreshold = clampRange(
                approvalRiskThreshold
                        + (preference.riskTolerance() - 0.5) * 0.20
                        + (trust - 0.5) * 0.10,
                0.35,
                0.90
        );
        double confidenceFloor = clampRange(
                approvalConfidenceFloor
                        - (preference.autonomyLevel() - 0.5) * 0.18
                        - (trust - 0.5) * 0.10,
                0.25,
                0.85
        );
        if (safeContext.cost() >= highCostThreshold) {
            return true;
        }
        if (safeContext.risk() > riskThreshold) {
            return true;
        }
        if (safeContext.confidence() < confidenceFloor) {
            return true;
        }
        return task != null && task.policy() == ExecutionPolicy.SPECULATIVE && trust < 0.6;
    }

    public boolean allowAutonomous(Task task) {
        return allowAutonomous(task, new SharedDecisionContext(null, task, null, null, null, null, -1.0, java.util.Map.of()));
    }

    public boolean allowAutonomous(Task task, SharedDecisionContext context) {
        SharedDecisionContext safeContext = context == null
                ? new SharedDecisionContext(null, task, null, null, null, null, -1.0, java.util.Map.of())
                : context;
        if (requireApproval(task, safeContext)) {
            return false;
        }
        HumanPreference preference = safeContext.preference();
        double trust = Math.max(0.0, safeContext.trustScore());
        double riskCap = clampRange(
                0.30
                        + preference.riskTolerance() * 0.20
                        + trust * 0.10,
                0.15,
                0.80
        );
        double confidenceNeed = clampRange(
                autonomyConfidenceThreshold
                        - preference.autonomyLevel() * 0.10
                        - trust * 0.05,
                0.30,
                0.85
        );
        double trustNeed = clampRange(
                minTrustToAutonomy - preference.autonomyLevel() * 0.10,
                0.20,
                0.80
        );
        return safeContext.confidence() >= confidenceNeed
                && safeContext.risk() <= riskCap
                && trust >= trustNeed;
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double clampRange(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
