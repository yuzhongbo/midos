package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import java.util.Map;

final class TaskGraphCoordinatorBridgeAdapter implements TaskGraphCoordinator.TaskGraphBridge {

    @FunctionalInterface
    interface ClarificationOutcomeBuilder {
        DecisionOrchestrator.OrchestrationOutcome build(String target, String message);
    }

    @FunctionalInterface
    interface EffectiveParamsBuilder {
        Map<String, Object> build(Map<String, Object> params, SkillContext skillContext);
    }

    @FunctionalInterface
    interface ContextPatchApplier {
        Map<String, Object> apply(Map<String, Object> baseContext, Map<String, Object> patch);
    }

    @FunctionalInterface
    interface TraceEventRecorder {
        void record(String traceId, String phase, String action, Map<String, Object> details);
    }

    private final ClarificationOutcomeBuilder clarificationOutcomeBuilder;
    private final EffectiveParamsBuilder effectiveParamsBuilder;
    private final ContextPatchApplier contextPatchApplier;
    private final TraceEventRecorder traceEventRecorder;

    TaskGraphCoordinatorBridgeAdapter(ClarificationOutcomeBuilder clarificationOutcomeBuilder,
                                      EffectiveParamsBuilder effectiveParamsBuilder,
                                      ContextPatchApplier contextPatchApplier,
                                      TraceEventRecorder traceEventRecorder) {
        this.clarificationOutcomeBuilder = clarificationOutcomeBuilder;
        this.effectiveParamsBuilder = effectiveParamsBuilder;
        this.contextPatchApplier = contextPatchApplier;
        this.traceEventRecorder = traceEventRecorder;
    }

    @Override
    public DecisionOrchestrator.OrchestrationOutcome clarificationOutcome(String target, String message) {
        return clarificationOutcomeBuilder.build(target, message);
    }

    @Override
    public Map<String, Object> buildEffectiveParams(Map<String, Object> params, SkillContext skillContext) {
        return effectiveParamsBuilder.build(params, skillContext);
    }

    @Override
    public Map<String, Object> applyContextPatch(Map<String, Object> baseContext, Map<String, Object> patch) {
        return contextPatchApplier.apply(baseContext, patch);
    }

    @Override
    public void traceEvent(String traceId, String phase, String action, Map<String, Object> details) {
        traceEventRecorder.record(traceId, phase, action, details);
    }
}
