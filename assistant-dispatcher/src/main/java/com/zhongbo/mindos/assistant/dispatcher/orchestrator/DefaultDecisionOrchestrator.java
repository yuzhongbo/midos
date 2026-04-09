package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DefaultDecisionOrchestrator implements DecisionOrchestrator {

    private final CandidatePlanner candidatePlanner;
    private final ParamValidator paramValidator;
    private final ConversationLoop conversationLoop;
    private final FallbackPlan fallbackPlan;

    public DefaultDecisionOrchestrator(CandidatePlanner candidatePlanner,
                                       ParamValidator paramValidator,
                                       ConversationLoop conversationLoop,
                                       FallbackPlan fallbackPlan) {
        this.candidatePlanner = candidatePlanner;
        this.paramValidator = paramValidator;
        this.conversationLoop = conversationLoop;
        this.fallbackPlan = fallbackPlan;
    }

    @Override
    public OrchestrationOutcome orchestrate(Decision decision, Map<String, Object> profileContext) {
        if (decision == null || decision.target() == null || decision.target().isBlank()) {
            return new OrchestrationOutcome(null, null);
        }
        String target = candidatePlanner.plan(decision.target()).stream().findFirst().orElse(decision.target());
        java.util.List<String> executionOrder = new java.util.ArrayList<>();
        if (target != null && !target.isBlank()) {
            executionOrder.add(target);
        }
        executionOrder.addAll(fallbackPlan.fallbacks(target));

        String lastFailure = null;
        for (String candidate : executionOrder) {
            ParamValidator.ValidationResult namespaceValidation = validateNamespace(candidate);
            if (!namespaceValidation.valid()) {
                lastFailure = namespaceValidation.message();
                continue;
            }
            ParamValidator.ValidationResult validation = paramValidator.validate(candidate, decision.params());
            if (!validation.valid() || decision.requiresClarify()) {
                lastFailure = validation.message();
                continue;
            }
            Map<String, Object> params = decision.params() == null ? Map.of() : decision.params();
            return new OrchestrationOutcome(new SkillDsl(candidate, params), null);
        }
        return new OrchestrationOutcome(null, conversationLoop.requestClarification(target, lastFailure));
    }

    private ParamValidator.ValidationResult validateNamespace(String target) {
        if (target == null || target.isBlank() || !target.startsWith("mcp.")) {
            return ParamValidator.ValidationResult.ok();
        }
        String[] parts = target.split("\\.");
        if (parts.length < 3 || parts[1].isBlank() || parts[2].isBlank()) {
            return ParamValidator.ValidationResult.error("MCP 名称需为 mcp.<alias>.<tool>");
        }
        return ParamValidator.ValidationResult.ok();
    }
}
