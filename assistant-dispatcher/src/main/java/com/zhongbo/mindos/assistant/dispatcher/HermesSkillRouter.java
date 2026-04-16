package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.system.DevelopmentWorkflowService;
import com.zhongbo.mindos.assistant.skill.SkillExecutionGateway;

import java.util.LinkedHashMap;
import java.util.Map;

final class HermesSkillRouter {

    private final SkillExecutionGateway skillExecutionGateway;
    private final HermesToolSchemaCatalog toolSchemaCatalog;
    private final DevelopmentWorkflowService developmentWorkflowService;

    HermesSkillRouter(SkillExecutionGateway skillExecutionGateway, HermesToolSchemaCatalog toolSchemaCatalog) {
        this(skillExecutionGateway, toolSchemaCatalog, null);
    }

    HermesSkillRouter(SkillExecutionGateway skillExecutionGateway,
                      HermesToolSchemaCatalog toolSchemaCatalog,
                      DevelopmentWorkflowService developmentWorkflowService) {
        this.skillExecutionGateway = skillExecutionGateway;
        this.toolSchemaCatalog = toolSchemaCatalog;
        this.developmentWorkflowService = developmentWorkflowService;
    }

    SkillResult execute(Decision decision, SkillContext baseContext) {
        if (decision == null || decision.target() == null || decision.target().isBlank()) {
            return SkillResult.failure("decision-engine", "missing routed target");
        }
        String executionTarget = resolveExecutionTarget(decision.target());
        SkillContext executionContext = buildExecutionContext(decision, baseContext);
        if (usesDevelopmentWorkflow(executionTarget)) {
            return developmentWorkflowService.execute(executionTarget, decision.params(), executionContext);
        }
        if (skillExecutionGateway == null) {
            return SkillResult.failure(executionTarget, "skill execution gateway unavailable");
        }
        try {
            return skillExecutionGateway.executeDslAsync(
                    new SkillDsl(executionTarget, decision.params()),
                    executionContext
            ).join();
        } catch (RuntimeException ex) {
            return SkillResult.failure(executionTarget, ex.getMessage() == null ? "skill execution failed" : ex.getMessage());
        }
    }

    boolean usesDevelopmentWorkflow(String executionTarget) {
        return developmentWorkflowService != null && developmentWorkflowService.supports(executionTarget);
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
        if (decisionTarget == null || decisionTarget.isBlank()) {
            return "";
        }
        if (toolSchemaCatalog == null) {
            return decisionTarget;
        }
        String resolved = toolSchemaCatalog.executionTargetForDecision(decisionTarget);
        return resolved.isBlank() ? decisionTarget : resolved;
    }
}
