package com.zhongbo.mindos.assistant.dispatcher.system;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.DecisionCapabilityCatalog;
import com.zhongbo.mindos.assistant.skill.SkillExecutionGateway;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DevelopmentWorkflowService {

    public static final String WORKFLOW_REASON = "systemWorkflow=development";

    private static final String CODE_GENERATE_TARGET = "code.generate";

    private final SkillExecutionGateway skillExecutionGateway;

    public DevelopmentWorkflowService(SkillExecutionGateway skillExecutionGateway) {
        this.skillExecutionGateway = skillExecutionGateway;
    }

    public boolean supports(String executionTarget) {
        return CODE_GENERATE_TARGET.equals(normalize(executionTarget));
    }

    public SkillResult execute(String executionTarget,
                               Map<String, Object> params,
                               SkillContext executionContext) {
        String normalizedTarget = normalize(executionTarget);
        if (!supports(normalizedTarget)) {
            return SkillResult.failure(normalizedTarget, "unsupported development workflow target");
        }
        if (skillExecutionGateway == null) {
            return SkillResult.failure(normalizedTarget, "development workflow unavailable");
        }
        Map<String, Object> safeParams = params == null ? Map.of() : new LinkedHashMap<>(params);
        try {
            return skillExecutionGateway.executeDslAsync(
                    new SkillDsl(normalizedTarget, safeParams),
                    enrichExecutionContext(normalizedTarget, executionContext)
            ).join();
        } catch (RuntimeException ex) {
            return SkillResult.failure(normalizedTarget, ex.getMessage() == null ? "skill execution failed" : ex.getMessage());
        }
    }

    private SkillContext enrichExecutionContext(String executionTarget, SkillContext context) {
        Map<String, Object> attributes = new LinkedHashMap<>(context == null || context.attributes() == null
                ? Map.of()
                : context.attributes());
        attributes.put("systemWorkflow", "development");
        attributes.put("systemWorkflowExecutionTarget", executionTarget);
        attributes.putIfAbsent("systemWorkflowCapability", DecisionCapabilityCatalog.decisionTarget(executionTarget));
        return new SkillContext(
                context == null ? "" : context.userId(),
                context == null ? "" : context.input(),
                attributes
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
