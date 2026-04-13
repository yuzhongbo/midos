package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.MultiAgentCoordinator;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PlanningCognitivePlugin implements CognitivePlugin {

    private final MultiAgentCoordinator coordinator;

    public PlanningCognitivePlugin(MultiAgentCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public String pluginId() {
        return "planning.multi-agent";
    }

    @Override
    public CognitiveCapability capability() {
        return CognitiveCapability.PLANNING;
    }

    @Override
    public RuntimeObject runtimeObject() {
        return new RuntimeObject(
                "plugin.planning.multi-agent",
                RuntimeObjectType.COGNITIVE_PLUGIN,
                "planning",
                Map.of("planner", "multi-agent-coordinator")
        );
    }

    @Override
    public CognitivePluginOutput run(CognitivePluginContext context) {
        Task task = context == null ? null : context.task();
        if (task == null) {
            return CognitivePluginOutput.empty();
        }
        TaskGraph graph = task.graph();
        LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
        RuntimeState state = context == null ? null : context.runtimeState();
        RuntimeContext runtimeContext = state == null ? RuntimeContext.empty() : state.context();
        TaskGraph overrideGraph = overrideGraph(runtimeContext);
        if (overrideGraph != null && !overrideGraph.isEmpty()) {
            graph = overrideGraph;
            attributes.put("planning.selection.summary", "human-overridden-graph");
            attributes.put("planning.selection.agentId", "human");
            attributes.put("planning.selection.strategyType", "human");
            attributes.put("planning.selection.humanOverride", true);
        }
        if (graph == null || graph.isEmpty()) {
            GoalExecutionResult lastResult = state == null ? null : state.lastResult();
            com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.EvaluationResult lastEvaluation = state == null ? null : state.lastEvaluation();
            List<String> failedTargets = state == null || state.pointer() == null ? List.of() : state.pointer().failedTargets();
            AutonomousPlanningContext planningContext = new AutonomousPlanningContext(
                    runtimeContext.userId(),
                    runtimeContext.input().isBlank() ? task.goal().description() : runtimeContext.input(),
                    runtimeContext.attributes(),
                    null,
                    state == null ? 1 : state.pointer().cycle(),
                    lastResult,
                    lastEvaluation,
                    failedTargets
            );
            List<String> allowedAgentIds = allowedAgentIds(runtimeContext);
            MultiAgentCoordinator.PlanSelection selection = coordinator == null
                    ? MultiAgentCoordinator.PlanSelection.empty()
                    : coordinator.selectBestPlan(task.goal(), planningContext, allowedAgentIds);
            graph = selection.graph();
            attributes.put("planning.selection.summary", selection.summary());
            attributes.put("planning.selection.agentId", selection.agentId());
            attributes.put("planning.selection.strategyType", selection.strategyType());
            attributes.put("planning.selection.candidateCount", selection.proposals().size());
        }
        return new CognitivePluginOutput(
                graph,
                attributes,
                graph == null || graph.isEmpty() ? 0.0 : 0.85,
                graph == null || graph.isEmpty() ? "no-plan" : "planned graph nodes=" + graph.nodes().size()
        );
    }

    @Override
    public int priority() {
        return 10;
    }

    private TaskGraph overrideGraph(RuntimeContext runtimeContext) {
        if (runtimeContext == null || runtimeContext.attributes().isEmpty()) {
            return null;
        }
        Object value = runtimeContext.attributes().get("coruntime.overrideGraph");
        return value instanceof TaskGraph override ? override : null;
    }

    private List<String> allowedAgentIds(RuntimeContext runtimeContext) {
        if (runtimeContext == null || runtimeContext.attributes().isEmpty()) {
            return List.of();
        }
        Object value = runtimeContext.attributes().get("coruntime.allowedAgentIds");
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item != null && !String.valueOf(item).isBlank())
                    .map(item -> String.valueOf(item).trim())
                    .toList();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(String.valueOf(value).split("[,;；]"))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .toList();
    }
}
