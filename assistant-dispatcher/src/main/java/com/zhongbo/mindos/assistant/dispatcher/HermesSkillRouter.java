package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.system.SkillCompositionService;
import com.zhongbo.mindos.assistant.skill.SkillExecutionGateway;

import java.util.LinkedHashMap;
import java.util.Map;

final class HermesSkillRouter {

    private final SkillExecutionGateway skillExecutionGateway;
    private final HermesToolSchemaCatalog toolSchemaCatalog;
    private final SkillCompositionService skillCompositionService;

    HermesSkillRouter(SkillExecutionGateway skillExecutionGateway, HermesToolSchemaCatalog toolSchemaCatalog) {
        this(skillExecutionGateway, toolSchemaCatalog, null);
    }

    HermesSkillRouter(SkillExecutionGateway skillExecutionGateway,
                      HermesToolSchemaCatalog toolSchemaCatalog,
                      SkillCompositionService skillCompositionService) {
        this.skillExecutionGateway = skillExecutionGateway;
        this.toolSchemaCatalog = toolSchemaCatalog;
        this.skillCompositionService = skillCompositionService;
    }

    SkillResult execute(String requestedDecisionTarget, Decision decision, SkillContext baseContext) {
        if (decision == null || decision.target() == null || decision.target().isBlank()) {
            return SkillResult.failure("decision-engine", "missing routed target");
        }
        String decisionTarget = requestedDecisionTarget == null || requestedDecisionTarget.isBlank()
                ? decision.target()
                : requestedDecisionTarget;
        HermesSkillIdentity skillIdentity = resolveSkillIdentity(decisionTarget, baseContext);
        if (usesSkillComposition(skillIdentity.decisionTarget(), skillIdentity.executionTarget())) {
            return skillIdentity.attachTo(
                    skillCompositionService.execute(
                            skillIdentity.decisionTarget(),
                            skillIdentity.executionTarget(),
                            decision.params(),
                            baseContext
                    )
            );
        }
        SkillContext executionContext = buildExecutionContext(decision, baseContext);
        if (skillExecutionGateway == null) {
            return skillIdentity.attachTo(SkillResult.failure(skillIdentity.executionTarget(), "skill execution gateway unavailable"));
        }
        try {
            return skillIdentity.attachTo(skillExecutionGateway.executeDslAsync(
                    new SkillDsl(skillIdentity.executionTarget(), decision.params()),
                    executionContext
            ).join());
        } catch (RuntimeException ex) {
            return skillIdentity.attachTo(
                    SkillResult.failure(
                            skillIdentity.executionTarget(),
                            ex.getMessage() == null ? "skill execution failed" : ex.getMessage()
                    )
            );
        }
    }

    String workflowReasonFor(String requestedDecisionTarget, String executionTarget) {
        if (!usesSkillComposition(requestedDecisionTarget, executionTarget)) {
            return "";
        }
        return skillCompositionService.workflowReasonFor(requestedDecisionTarget, executionTarget);
    }

    boolean usesSkillComposition(String requestedDecisionTarget, String executionTarget) {
        return skillCompositionService != null && skillCompositionService.supports(requestedDecisionTarget, executionTarget);
    }

    private SkillContext buildExecutionContext(Decision decision, SkillContext baseContext) {
        Map<String, Object> attributes = new LinkedHashMap<>(baseContext == null ? Map.of() : baseContext.attributes());
        if (decision.params() != null && !decision.params().isEmpty()) {
            attributes.putAll(decision.params());
        }
        return new SkillContext(
                baseContext == null ? "" : baseContext.userId(),
                baseContext == null ? "" : baseContext.input(),
                attributes
        );
    }

    String resolveExecutionTarget(String decisionTarget) {
        return resolveExecutionTarget(decisionTarget, null);
    }

    String resolveExecutionTarget(String decisionTarget, SkillContext baseContext) {
        return resolveSkillIdentity(decisionTarget, baseContext).executionTarget();
    }

    HermesSkillIdentity resolveSkillIdentity(String decisionTarget, SkillContext baseContext) {
        return HermesSkillIdentity.resolve(
                decisionTarget,
                toolSchemaCatalog,
                baseContext == null || baseContext.attributes() == null ? Map.of() : baseContext.attributes()
        );
    }
}
