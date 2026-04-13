package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ReplanEngine {

    private final SignalFusionEngine fusionEngine;
    private final PlannerDecisionFactory decisionFactory;

    ReplanEngine(SignalFusionEngine fusionEngine,
                 PlannerDecisionFactory decisionFactory) {
        this.fusionEngine = fusionEngine == null ? new SignalFusionEngine() : fusionEngine;
        this.decisionFactory = decisionFactory == null
                ? new PlannerDecisionFactory(new DecisionParamAssembler(new SkillCommandAssembler(new SkillDslParser(new SkillDslValidator()), false)))
                : decisionFactory;
    }

    Decision replan(DecisionOrchestrator.UserInput input,
                    List<DecisionSignal> signals,
                    Decision failedDecision) {
        DecisionOrchestrator.UserInput safeInput = DecisionOrchestrator.UserInput.safe(input);
        LinkedHashSet<String> failedTargets = new LinkedHashSet<>(decisionFactory.failedTargetsOf(failedDecision));
        if (failedDecision != null && failedDecision.target() != null && !failedDecision.target().isBlank()) {
            failedTargets.add(failedDecision.target());
        }
        SignalFusionEngine.DecisionSelection selection = fusionEngine.fuse(safeInput, signals, failedTargets);
        if (selection == null || !selection.hasSelection()) {
            return decisionFactory.clarify(
                    safeInput,
                    selection,
                    failedTargets,
                    "上一个执行目标失败，当前没有足够可靠的备选方案，请补充说明。"
            );
        }
        if (failedTargets.contains(selection.selectedTarget())) {
            return decisionFactory.clarify(
                    safeInput,
                    selection,
                    failedTargets,
                    "重新规划后仍命中了已失败目标，请确认你的真实意图。"
            );
        }
        return decisionFactory.build(safeInput, selection, failedTargets);
    }
}
