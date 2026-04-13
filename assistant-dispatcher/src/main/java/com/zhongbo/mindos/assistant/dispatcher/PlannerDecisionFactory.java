package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PlannerDecisionFactory {

    private final DecisionParamAssembler decisionParamAssembler;

    PlannerDecisionFactory(DecisionParamAssembler decisionParamAssembler) {
        this.decisionParamAssembler = decisionParamAssembler == null
                ? new DecisionParamAssembler(new SkillCommandAssembler(new SkillDslParser(new SkillDslValidator()), false))
                : decisionParamAssembler;
    }

    Decision build(DecisionOrchestrator.UserInput input,
                   SignalFusionEngine.DecisionSelection selection,
                   Set<String> failedTargets) {
        DecisionOrchestrator.UserInput safeInput = DecisionOrchestrator.UserInput.safe(input);
        if (selection == null || !selection.hasSelection()) {
            return clarify(safeInput, selection, failedTargets, "需要更多信息才能确定执行目标。");
        }
        String target = selection.selectedTarget();
        String source = selection.primarySource();
        Map<String, Object> params = new LinkedHashMap<>(decisionParamAssembler.assembleParams(
                target,
                source,
                safeInput.userInput(),
                safeInput.skillContext()
        ));
        enrichPlannerMetadata(params, selection, failedTargets, "");
        return new Decision(
                resolveIntent(safeInput.skillContext(), selection.intentHint(), target),
                target,
                params.isEmpty() ? Map.of() : Map.copyOf(params),
                selection.confidence(),
                target == null || target.isBlank()
        );
    }

    Decision clarify(DecisionOrchestrator.UserInput input,
                     SignalFusionEngine.DecisionSelection selection,
                     Set<String> failedTargets,
                     String message) {
        DecisionOrchestrator.UserInput safeInput = DecisionOrchestrator.UserInput.safe(input);
        String target = selection == null ? "" : selection.selectedTarget();
        String source = selection == null ? "" : selection.primarySource();
        Map<String, Object> params = new LinkedHashMap<>(decisionParamAssembler.assembleParams(
                target,
                source,
                safeInput.userInput(),
                safeInput.skillContext()
        ));
        enrichPlannerMetadata(params, selection, failedTargets, message);
        return new Decision(
                resolveIntent(safeInput.skillContext(), selection == null ? "" : selection.intentHint(), target),
                target,
                params.isEmpty() ? Map.of() : Map.copyOf(params),
                selection == null ? 0.0 : selection.confidence(),
                true
        );
    }

    List<DecisionSignal> signalsOf(Decision decision) {
        if (decision == null || decision.params() == null) {
            return List.of();
        }
        Object rawSignals = decision.params().get(FinalPlanner.PLANNER_SIGNALS_KEY);
        if (!(rawSignals instanceof List<?> sourceSignals) || sourceSignals.isEmpty()) {
            return List.of();
        }
        List<DecisionSignal> restored = new ArrayList<>();
        for (Object source : sourceSignals) {
            if (!(source instanceof Map<?, ?> map)) {
                continue;
            }
            String target = stringValue(map.get("target"));
            double score = numericValue(map.get("score"));
            String signalSource = stringValue(map.get("source"));
            if (!target.isBlank()) {
                restored.add(new DecisionSignal(target, score, signalSource));
            }
        }
        return restored.isEmpty() ? List.of() : List.copyOf(restored);
    }

    Set<String> failedTargetsOf(Decision decision) {
        if (decision == null || decision.params() == null) {
            return Set.of();
        }
        Object rawTargets = decision.params().get(FinalPlanner.PLANNER_FAILED_TARGETS_KEY);
        if (!(rawTargets instanceof List<?> values) || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> restored = new LinkedHashSet<>();
        for (Object value : values) {
            String target = stringValue(value);
            if (!target.isBlank()) {
                restored.add(target);
            }
        }
        return restored.isEmpty() ? Set.of() : Set.copyOf(restored);
    }

    private void enrichPlannerMetadata(Map<String, Object> params,
                                       SignalFusionEngine.DecisionSelection selection,
                                       Set<String> failedTargets,
                                       String clarifyMessage) {
        if (selection != null && !selection.primarySource().isBlank()) {
            params.put(FinalPlanner.PLANNER_ROUTE_SOURCE_KEY, selection.primarySource());
            params.put(FinalPlanner.PLANNER_ROUTE_SOURCE_METADATA_KEY, selection.primarySource());
        }
        if (selection != null && selection.topCandidates() != null && !selection.topCandidates().isEmpty()) {
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (SignalFusionEngine.CandidateScore candidate : selection.topCandidates()) {
                Map<String, Object> candidateMap = new LinkedHashMap<>();
                candidateMap.put("target", candidate.target());
                candidateMap.put("score", candidate.finalScore());
                candidateMap.put("baseScore", candidate.baseScore());
                candidateMap.put("source", candidate.primarySource());
                candidateMap.put("intentHint", candidate.intentHint());
                candidateMap.put("sourceScores", candidate.sourceScores());
                candidateMap.put("successBoost", candidate.successBoost());
                candidateMap.put("failurePenalty", candidate.failurePenalty());
                candidateMap.put("debounceAdjustment", candidate.debounceAdjustment());
                candidates.add(Map.copyOf(candidateMap));
            }
            params.put(FinalPlanner.PLANNER_CANDIDATES_KEY, List.copyOf(candidates));
        }
        if (selection != null && selection.retainedSignals() != null && !selection.retainedSignals().isEmpty()) {
            List<Map<String, Object>> signals = new ArrayList<>();
            for (DecisionSignal signal : selection.retainedSignals()) {
                Map<String, Object> signalMap = new LinkedHashMap<>();
                signalMap.put("target", signal.target());
                signalMap.put("score", signal.score());
                signalMap.put("source", signal.source());
                signals.add(Map.copyOf(signalMap));
            }
            params.put(FinalPlanner.PLANNER_SIGNALS_KEY, List.copyOf(signals));
        }
        if (failedTargets != null && !failedTargets.isEmpty()) {
            params.put(FinalPlanner.PLANNER_FAILED_TARGETS_KEY, List.copyOf(new ArrayList<>(failedTargets)));
        }
        if (clarifyMessage != null && !clarifyMessage.isBlank()) {
            params.put(FinalPlanner.PLANNER_CLARIFY_MESSAGE_KEY, clarifyMessage.trim());
        }
    }

    private String resolveIntent(SkillContext context, String intentHint, String target) {
        Map<String, Object> attributes = context == null || context.attributes() == null ? Map.of() : context.attributes();
        String semanticIntent = stringValue(attributes.get(SemanticAnalysisResult.ATTR_INTENT));
        if (!semanticIntent.isBlank()) {
            return semanticIntent;
        }
        if (intentHint != null && !intentHint.isBlank()) {
            return intentHint.trim();
        }
        return target == null ? "" : target.trim();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private double numericValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }
}
