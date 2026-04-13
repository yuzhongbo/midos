package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.AGIMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.RuntimeContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.RuntimeState;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.Task;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.TaskHandle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class SharedDecisionEngine {

    private final ControlProtocol controlProtocol;
    private final HumanInterface humanInterface;
    private final ExplainabilityEngine explainabilityEngine;
    private final HumanPreferenceModel humanPreferenceModel;
    private final TrustModel trustModel;
    private final AGIMemory memory;
    private final Map<String, CopyOnWriteArrayList<SharedDecision>> decisions = new ConcurrentHashMap<>();

    public SharedDecisionEngine(ControlProtocol controlProtocol,
                                HumanInterface humanInterface,
                                ExplainabilityEngine explainabilityEngine,
                                HumanPreferenceModel humanPreferenceModel,
                                TrustModel trustModel) {
        this(controlProtocol, humanInterface, explainabilityEngine, humanPreferenceModel, trustModel, null);
    }

    @Autowired
    public SharedDecisionEngine(ControlProtocol controlProtocol,
                                HumanInterface humanInterface,
                                ExplainabilityEngine explainabilityEngine,
                                HumanPreferenceModel humanPreferenceModel,
                                TrustModel trustModel,
                                AGIMemory memory) {
        this.controlProtocol = controlProtocol;
        this.humanInterface = humanInterface;
        this.explainabilityEngine = explainabilityEngine;
        this.humanPreferenceModel = humanPreferenceModel;
        this.trustModel = trustModel;
        this.memory = memory;
    }

    public SharedDecision decide(Task task, SharedDecisionContext context) {
        Task safeTask = task == null
                ? Task.fromGoal(null, com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.ExecutionPolicy.AUTONOMOUS, Map.of())
                : task;
        SharedDecisionContext safeContext = context == null
                ? new SharedDecisionContext(new TaskHandle(safeTask.taskId()), safeTask, null, null, null, null, -1.0, Map.of())
                : context;
        RuntimeState runtimeState = safeContext.runtimeState();
        RuntimeContext runtimeContext = runtimeState == null
                ? RuntimeContext.empty()
                : new RuntimeContext(runtimeState.context().userId(), runtimeState.context().input(), safeContext.attributes(), runtimeState.context().assignedNode());
        HumanIntent intent = humanInterface == null ? HumanIntent.empty() : humanInterface.capture();
        HumanPreference preference = humanPreferenceModel == null
                ? HumanPreference.defaultPreference()
                : humanPreferenceModel.predict(runtimeContext);
        double trust = safeContext.trustScore() >= 0.0
                ? safeContext.trustScore()
                : trustModel == null
                ? 0.55
                : trustModel.trustScore(safeTask, runtimeContext);
        SharedDecisionContext effectiveContext = new SharedDecisionContext(
                safeContext.handle(),
                safeTask,
                runtimeState,
                safeContext.plan(),
                intent,
                preference,
                trust,
                safeContext.attributes()
        );
        Action action = new Action(
                safeContext.handle() == null ? safeTask.taskId() : safeContext.handle().taskId(),
                safeTask.taskId(),
                safeTask.goal().goalId(),
                safeContext.plan() == null ? safeTask.goal().description() : safeContext.plan().summary(),
                effectiveContext.confidence(),
                effectiveContext.risk(),
                effectiveContext.cost(),
                effectiveContext.targets(),
                Map.of(
                        "goal", safeTask.goal().description(),
                        "reasoning", effectiveContext.stringAttribute("reasoning.focusAreas", ""),
                        "planningAgent", effectiveContext.stringAttribute("planning.selection.agentId", ""),
                        "planningSummary", effectiveContext.stringAttribute("planning.selection.summary", "")
                )
        );
        boolean requireApproval = controlProtocol != null && controlProtocol.requireApproval(safeTask, effectiveContext);
        boolean allowAutonomous = controlProtocol != null && controlProtocol.allowAutonomous(safeTask, effectiveContext);
        Approval approval;
        DecisionMode mode;
        boolean allowExecution;
        Map<String, Object> overrides;
        if (effectiveContext.booleanAttribute("coruntime.forceHumanOverride", false)) {
            overrides = effectiveContext.attributes().containsKey("coruntime.override")
                    ? mapValue(effectiveContext.attributes().get("coruntime.override"))
                    : Map.of();
            approval = overrides.isEmpty()
                    ? Approval.pending("human-override-requested")
                    : Approval.modified("human-override-requested", overrides);
            mode = DecisionMode.HUMAN_OVERRIDE;
            allowExecution = false;
        } else if (requireApproval) {
            approval = humanInterface == null ? Approval.pending("human-interface-unavailable") : humanInterface.requestApproval(action);
            overrides = approval.overrideAttributes();
            if (approval.allowExecution()) {
                mode = DecisionMode.JOINT_APPROVED;
                allowExecution = true;
            } else if (approval.modified()) {
                mode = DecisionMode.HUMAN_MODIFIED;
                allowExecution = false;
            } else if (approval.rejected()) {
                mode = DecisionMode.HUMAN_REJECTED;
                allowExecution = false;
            } else {
                mode = DecisionMode.JOINT_REVIEW;
                allowExecution = false;
            }
        } else if (allowAutonomous) {
            approval = Approval.approved("autonomous-safe");
            mode = DecisionMode.AI_AUTONOMOUS;
            allowExecution = true;
            overrides = Map.of();
        } else {
            approval = Approval.pending("autonomy-not-allowed");
            mode = DecisionMode.JOINT_REVIEW;
            allowExecution = false;
            overrides = Map.of();
        }
        SharedDecision decision = new SharedDecision(
                safeContext.handle(),
                safeTask.taskId(),
                mode,
                allowExecution,
                requireApproval,
                approval,
                Explanation.empty(),
                preference,
                trust,
                effectiveContext.confidence(),
                effectiveContext.risk(),
                effectiveContext.cost(),
                effectiveContext.targets(),
                overrides,
                Instant.now()
        );
        SharedDecision explained = decision.withExplanation(
                explainabilityEngine == null ? Explanation.empty() : explainabilityEngine.explain(decision)
        );
        record(explained);
        return explained;
    }

    public SharedDecision latest(TaskHandle handle) {
        if (handle == null || handle.isEmpty()) {
            return null;
        }
        List<SharedDecision> history = history(handle);
        return history.isEmpty() ? null : history.get(history.size() - 1);
    }

    public List<SharedDecision> history(TaskHandle handle) {
        if (handle == null || handle.isEmpty()) {
            return List.of();
        }
        return List.copyOf(decisions.getOrDefault(handle.taskId(), new CopyOnWriteArrayList<>()));
    }

    private void record(SharedDecision decision) {
        if (decision == null || decision.handle() == null || decision.handle().isEmpty()) {
            return;
        }
        decisions.computeIfAbsent(decision.handle().taskId(), ignored -> new CopyOnWriteArrayList<>()).add(decision);
        if (memory == null) {
            return;
        }
        String namespace = "coruntime:decision:" + decision.handle().taskId();
        memory.shortTerm().put(namespace, decision.attributes());
        memory.semantic().put(namespace + ":summary", decision.explanation().summary());
        memory.longTerm().link(decision.handle().taskId(), namespace);
    }

    private Map<String, Object> mapValue(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    values.put(String.valueOf(key), value);
                }
            });
            return Map.copyOf(values);
        }
        return Map.of();
    }
}
