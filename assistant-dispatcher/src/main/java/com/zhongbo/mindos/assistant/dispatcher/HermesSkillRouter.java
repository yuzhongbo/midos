package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.skill.SkillExecutionGateway;

import java.util.LinkedHashMap;
import java.util.Map;

final class HermesSkillRouter {

    private final SkillExecutionGateway skillExecutionGateway;

    HermesSkillRouter(SkillExecutionGateway skillExecutionGateway) {
        this.skillExecutionGateway = skillExecutionGateway;
    }

    SkillResult execute(Decision decision, SkillContext baseContext) {
        if (decision == null || decision.target() == null || decision.target().isBlank()) {
            return SkillResult.failure("decision-engine", "missing routed target");
        }
        if (skillExecutionGateway == null) {
            return SkillResult.failure(decision.target(), "skill execution gateway unavailable");
        }
        Map<String, Object> attributes = new LinkedHashMap<>(baseContext == null ? Map.of() : baseContext.attributes());
        if (decision.params() != null && !decision.params().isEmpty()) {
            attributes.putAll(decision.params());
        }
        SkillContext executionContext = new SkillContext(
                baseContext == null ? "" : baseContext.userId(),
                baseContext == null ? "" : baseContext.input(),
                attributes
        );
        try {
            return skillExecutionGateway.executeDslAsync(
                    new SkillDsl(decision.target(), decision.params()),
                    executionContext
            ).join();
        } catch (RuntimeException ex) {
            return SkillResult.failure(decision.target(), ex.getMessage() == null ? "skill execution failed" : ex.getMessage());
        }
    }
}
