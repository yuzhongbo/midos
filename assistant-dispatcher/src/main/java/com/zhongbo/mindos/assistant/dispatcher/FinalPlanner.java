package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import java.util.List;

public final class FinalPlanner {

    public static final String PLANNER_METADATA_PREFIX = "_planner.";
    public static final String PLANNER_ROUTE_SOURCE_KEY = "_plannerRouteSource";
    public static final String PLANNER_ROUTE_SOURCE_METADATA_KEY = PLANNER_METADATA_PREFIX + "routeSource";
    public static final String PLANNER_CANDIDATES_KEY = PLANNER_METADATA_PREFIX + "candidates";
    public static final String PLANNER_SIGNALS_KEY = PLANNER_METADATA_PREFIX + "signals";
    public static final String PLANNER_FAILED_TARGETS_KEY = PLANNER_METADATA_PREFIX + "failedTargets";
    public static final String PLANNER_CLARIFY_MESSAGE_KEY = PLANNER_METADATA_PREFIX + "clarifyMessage";
    public static final String RULE_FALLBACK_SOURCE = "rule-fallback";

    private final PlannerSignalCollector signalCollector;
    private final SignalFusionEngine fusionEngine;
    private final ConfidenceGate confidenceGate;
    private final PlannerDecisionFactory decisionFactory;
    private final ReplanEngine replanEngine;

    public FinalPlanner() {
        this(null, null);
    }

    public FinalPlanner(com.zhongbo.mindos.assistant.skill.SkillCatalogFacade skillEngine) {
        this(skillEngine, null);
    }

    public FinalPlanner(com.zhongbo.mindos.assistant.skill.SkillCatalogFacade skillEngine,
                        DispatcherMemoryFacade dispatcherMemoryFacade) {
        this(
                new DecisionParamAssembler(new SkillCommandAssembler(new SkillDslParser(new SkillDslValidator()), false)),
                new PlannerSignalCollector(skillEngine),
                new SignalFusionEngine(dispatcherMemoryFacade),
                new ConfidenceGate()
        );
    }

    FinalPlanner(DecisionParamAssembler decisionParamAssembler,
                 PlannerSignalCollector signalCollector,
                 SignalFusionEngine fusionEngine,
                 ConfidenceGate confidenceGate) {
        this.signalCollector = signalCollector == null ? new PlannerSignalCollector() : signalCollector;
        this.fusionEngine = fusionEngine == null ? new SignalFusionEngine() : fusionEngine;
        this.confidenceGate = confidenceGate == null ? new ConfidenceGate() : confidenceGate;
        this.decisionFactory = new PlannerDecisionFactory(decisionParamAssembler);
        this.replanEngine = new ReplanEngine(this.fusionEngine, this.decisionFactory);
    }

    public Decision plan(DecisionOrchestrator.UserInput input) {
        DecisionOrchestrator.UserInput safeInput = DecisionOrchestrator.UserInput.safe(input);
        List<DecisionSignal> signals = signalCollector.collect(safeInput);
        return confidenceGate.check(decisionFactory.build(
                safeInput,
                fusionEngine.fuse(safeInput, signals, java.util.Set.of()),
                java.util.Set.of()
        ));
    }

    public Decision plan(DecisionOrchestrator.UserInput input, List<DecisionSignal> signals) {
        DecisionOrchestrator.UserInput safeInput = DecisionOrchestrator.UserInput.safe(input);
        List<DecisionSignal> effectiveSignals = signals == null || signals.isEmpty()
                ? signalCollector.collect(safeInput)
                : List.copyOf(signals);
        return confidenceGate.check(decisionFactory.build(
                safeInput,
                fusionEngine.fuse(safeInput, effectiveSignals, java.util.Set.of()),
                java.util.Set.of()
        ));
    }

    public Decision replan(DecisionOrchestrator.UserInput input, Decision failedDecision) {
        DecisionOrchestrator.UserInput safeInput = DecisionOrchestrator.UserInput.safe(input);
        List<DecisionSignal> rememberedSignals = decisionFactory.signalsOf(failedDecision);
        List<DecisionSignal> effectiveSignals = rememberedSignals.isEmpty()
                ? signalCollector.collect(safeInput)
                : rememberedSignals;
        return confidenceGate.check(replanEngine.replan(safeInput, effectiveSignals, failedDecision));
    }
}
