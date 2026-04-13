package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.EvaluationResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.Node;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.RuntimeContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.RuntimeState;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.TaskHandle;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.TaskState;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class HumanAICoRuntime {

    private final HumanRuntimeSessionManager sessionManager;
    private final HumanInterface humanInterface;
    private final HumanPreferenceModel humanPreferenceModel;
    private final TrustModel trustModel;
    private final InterventionManager interventionManager;
    private final SharedDecisionEngine sharedDecisionEngine;

    public HumanAICoRuntime(HumanRuntimeSessionManager sessionManager,
                            HumanInterface humanInterface,
                            HumanPreferenceModel humanPreferenceModel,
                            TrustModel trustModel,
                            InterventionManager interventionManager,
                            SharedDecisionEngine sharedDecisionEngine) {
        this.sessionManager = sessionManager;
        this.humanInterface = humanInterface;
        this.humanPreferenceModel = humanPreferenceModel;
        this.trustModel = trustModel;
        this.interventionManager = interventionManager;
        this.sharedDecisionEngine = sharedDecisionEngine;
    }

    public HumanIntent startSession(String userId,
                                    Goal goal,
                                    Map<String, Object> profileContext) {
        if (sessionManager == null) {
            return HumanIntent.empty();
        }
        sessionManager.activate(HumanRuntimeSession.from(userId, goal, profileContext));
        return humanInterface == null ? HumanIntent.empty() : humanInterface.capture();
    }

    public Map<String, Object> enrichProfileContext(String userId,
                                                    Goal goal,
                                                    Map<String, Object> profileContext) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(profileContext == null ? Map.of() : profileContext);
        java.util.ArrayList<String> seededPreferenceKeys = new java.util.ArrayList<>();
        HumanIntent intent = humanInterface == null ? HumanIntent.empty() : humanInterface.capture();
        HumanPreference preference = predictPreference(userId, goal, merged);
        merged.put("coruntime.shared", true);
        merged.putIfAbsent("human.intent.goal", intent.goal().isBlank() && goal != null ? goal.description() : intent.goal());
        merged.putIfAbsent("human.intent.notes", intent.notes());
        if (!merged.containsKey("human.preference.autonomy")) {
            merged.put("human.preference.autonomy", preference.autonomyLevel());
            seededPreferenceKeys.add("human.preference.autonomy");
        }
        if (!merged.containsKey("human.preference.riskTolerance")) {
            merged.put("human.preference.riskTolerance", preference.riskTolerance());
            seededPreferenceKeys.add("human.preference.riskTolerance");
        }
        if (!merged.containsKey("human.preference.costSensitivity")) {
            merged.put("human.preference.costSensitivity", preference.costSensitivity());
            seededPreferenceKeys.add("human.preference.costSensitivity");
        }
        if (!merged.containsKey("human.preference.style")) {
            merged.put("human.preference.style", preference.decisionStyle());
            seededPreferenceKeys.add("human.preference.style");
        }
        if (!merged.containsKey("human.preference.language")) {
            merged.put("human.preference.language", preference.language());
            seededPreferenceKeys.add("human.preference.language");
        }
        if (!merged.containsKey("human.preference.channel")) {
            merged.put("human.preference.channel", preference.preferredChannel());
            seededPreferenceKeys.add("human.preference.channel");
        }
        if (!merged.containsKey("human.preference.prefersExplanations")) {
            merged.put("human.preference.prefersExplanations", preference.prefersExplanations());
            seededPreferenceKeys.add("human.preference.prefersExplanations");
        }
        if (!seededPreferenceKeys.isEmpty()) {
            merged.put("coruntime.seededPreferenceKeys", List.copyOf(seededPreferenceKeys));
        }
        return Map.copyOf(merged);
    }

    public HumanPreference predictPreference(String userId,
                                            Goal goal,
                                            Map<String, Object> profileContext) {
        if (humanPreferenceModel == null) {
            return HumanPreference.defaultPreference();
        }
        return humanPreferenceModel.predict(new RuntimeContext(
                userId == null ? "" : userId,
                goal == null ? "" : goal.description(),
                profileContext == null ? Map.of() : profileContext,
                Node.local()
        ));
    }

    public HumanCycleOutcome afterCycle(TaskHandle handle,
                                        RuntimeState runtimeState,
                                        GoalExecutionResult result,
                                        EvaluationResult evaluation) {
        if (runtimeState == null) {
            return HumanCycleOutcome.empty();
        }
        RuntimeContext context = runtimeState.context();
        HumanFeedback feedback = shouldReadFeedback(runtimeState, result, evaluation) && humanInterface != null
                ? humanInterface.getFeedback()
                : HumanFeedback.empty();
        if (feedback.present() && humanPreferenceModel != null) {
            humanPreferenceModel.learn(feedback);
        }
        HumanPreference preference = humanPreferenceModel == null
                ? HumanPreference.defaultPreference()
                : humanPreferenceModel.predict(context);
        double trust = trustModel == null
                ? 0.55
                : trustModel.update(runtimeState.task(), evaluation, feedback, context);
        boolean interrupted = false;
        boolean correctionApplied = false;
        if (handle != null && !handle.isEmpty() && interventionManager != null && feedback.present()) {
            if (feedback.requestInterrupt()) {
                interventionManager.interrupt(handle);
                interrupted = true;
            }
            if (feedback.requestRollback()) {
                interventionManager.rollback(handle);
                correctionApplied = true;
            }
            if (!feedback.corrections().isEmpty()) {
                interventionManager.modify(handle, new Params(feedback.corrections()));
                correctionApplied = true;
            }
        }
        SharedDecision decision = sharedDecisionEngine == null ? null : sharedDecisionEngine.latest(handle);
        List<InterventionEvent> events = interventionManager == null ? List.of() : interventionManager.history(handle);
        return new HumanCycleOutcome(decision, feedback, preference, trust, events, interrupted, correctionApplied);
    }

    public List<SharedDecision> decisions(TaskHandle handle) {
        return sharedDecisionEngine == null ? List.of() : sharedDecisionEngine.history(handle);
    }

    public List<InterventionEvent> interventions(TaskHandle handle) {
        return interventionManager == null ? List.of() : interventionManager.history(handle);
    }

    public void finishSession() {
        if (sessionManager != null) {
            sessionManager.clear();
        }
    }

    private boolean shouldReadFeedback(RuntimeState runtimeState,
                                       GoalExecutionResult result,
                                       EvaluationResult evaluation) {
        if (result != null) {
            return true;
        }
        if (evaluation == null) {
            return false;
        }
        if (runtimeState.state() == TaskState.WAITING || runtimeState.state() == TaskState.SUSPENDED) {
            return false;
        }
        return evaluation.isSuccess() || !evaluation.summary().isBlank();
    }
}
