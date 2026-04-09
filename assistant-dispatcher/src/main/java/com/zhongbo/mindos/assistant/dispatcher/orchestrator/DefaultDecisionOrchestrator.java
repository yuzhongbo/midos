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

    public DefaultDecisionOrchestrator(CandidatePlanner candidatePlanner,
                                       ParamValidator paramValidator,
                                       ConversationLoop conversationLoop) {
        this.candidatePlanner = candidatePlanner;
        this.paramValidator = paramValidator;
        this.conversationLoop = conversationLoop;
    }

    @Override
    public OrchestrationOutcome orchestrate(Decision decision, Map<String, Object> profileContext) {
        if (decision == null || decision.target() == null || decision.target().isBlank()) {
            return new OrchestrationOutcome(null, null);
        }
        // Use planner for potential future expansion; currently single target.
        String target = candidatePlanner.plan(decision.target()).stream().findFirst().orElse(decision.target());
        ParamValidator.ValidationResult validation = paramValidator.validate(target, decision.params());
        if (!validation.valid() || decision.requiresClarify()) {
            // Clarify branch: currently return empty to let caller handle clarification.
            return new OrchestrationOutcome(null, conversationLoop.requestClarification(target, validation.message()));
        }
        Map<String, Object> params = decision.params() == null ? Map.of() : decision.params();
        return new OrchestrationOutcome(new SkillDsl(target, params), null);
    }
}
