package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

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
    public Optional<SkillDsl> toSkillDsl(Decision decision, Map<String, Object> profileContext) {
        if (decision == null || decision.target() == null || decision.target().isBlank()) {
            return Optional.empty();
        }
        // Use planner for potential future expansion; currently single target.
        String target = candidatePlanner.plan(decision.target()).stream().findFirst().orElse(decision.target());
        ParamValidator.ValidationResult validation = paramValidator.validate(target, decision.params());
        if (!validation.valid() || decision.requiresClarify()) {
            // Clarify branch: currently return empty to let caller handle clarification.
            return Optional.empty();
        }
        Map<String, Object> params = decision.params() == null ? Map.of() : decision.params();
        return Optional.of(new SkillDsl(target, params));
    }
}
