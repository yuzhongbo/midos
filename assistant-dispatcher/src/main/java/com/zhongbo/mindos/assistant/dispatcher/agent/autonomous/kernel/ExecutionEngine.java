package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousGraphExecutor;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.DefaultEvaluator;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.EvaluationResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Evaluator;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
public class ExecutionEngine {

    private final AutonomousGraphExecutor graphExecutor;
    private final Evaluator evaluator;

    public ExecutionEngine(AutonomousGraphExecutor graphExecutor,
                           Evaluator evaluator) {
        this.graphExecutor = graphExecutor;
        this.evaluator = evaluator == null ? new DefaultEvaluator() : evaluator;
    }

    public ExecutionState execute(ExecutionPlan plan,
                                  RuntimeState currentState) {
        RuntimeState safeState = currentState == null
                ? RuntimeState.initial(plan == null ? null : plan.task())
                : currentState;
        ExecutionPlan safePlan = plan == null ? safeState.plan() : plan;
        TaskGraph remainingGraph = remainingGraph(safePlan == null ? null : safePlan.graph(), safeState.pointer());
        if (remainingGraph == null || remainingGraph.isEmpty()) {
            RuntimeState terminal = safeState.advance(
                    safePlan,
                    safeState.lastEvaluation() != null && safeState.lastEvaluation().isSuccess() ? TaskState.COMPLETED : TaskState.FAILED,
                    safeState.pointer(),
                    safeState.context(),
                    safeState.lastResult(),
                    safeState.lastEvaluation(),
                    "no executable work remaining"
            );
            return new ExecutionState(terminal, safeState.lastResult(), safeState.lastEvaluation(), null);
        }
        RuntimeContext runtimeContext = safeState.context().withAttributes(safePlan.attributes()).withAssignedNode(safePlan.executionNode());
        Map<String, Object> profileContext = new LinkedHashMap<>(runtimeContext.attributes());
        profileContext.put("runtime.executionNode", runtimeContext.assignedNode().nodeId());
        profileContext.put("runtime.resourceAllocation", safePlan.resourceAllocation());
        AutonomousPlanningContext planningContext = new AutonomousPlanningContext(
                runtimeContext.userId(),
                runtimeContext.input(),
                profileContext,
                null,
                safeState.pointer().cycle(),
                safeState.lastResult(),
                safeState.lastEvaluation(),
                safeState.pointer().failedTargets()
        );
        GoalExecutionResult result = graphExecutor == null
                ? null
                : graphExecutor.execute(safePlan.task().goal(), remainingGraph, planningContext);
        EvaluationResult evaluation = evaluator.evaluate(result, safePlan.task().goal());
        List<String> successfulNodeIds = result == null ? List.of() : result.successfulTaskIds();
        List<String> failedNodeIds = result == null || result.graphResult() == null ? List.of() : result.graphResult().failedNodeIds();
        ExecutionPointer nextPointer = safeState.pointer().advance(
                nextNodeId(remainingGraph, successfulNodeIds),
                successfulNodeIds,
                failedNodeIds,
                evaluation == null ? List.of() : evaluation.failedTargets()
        );
        TaskState nextState = evaluation == null
                ? TaskState.FAILED
                : evaluation.isSuccess()
                ? TaskState.COMPLETED
                : evaluation.needsReplan()
                ? TaskState.WAITING
                : TaskState.FAILED;
        String summary = evaluation == null ? "execution failed" : evaluation.summary();
        RuntimeState nextStateSnapshot = safeState.advance(
                safePlan,
                nextState,
                nextPointer,
                runtimeContext.withAttributes(Map.of(
                        "runtime.lastSummary", summary,
                        "runtime.lastSuccess", evaluation == null ? false : evaluation.isSuccess()
                )),
                result,
                evaluation,
                summary
        );
        return new ExecutionState(nextStateSnapshot, result, evaluation, null);
    }

    private TaskGraph remainingGraph(TaskGraph graph,
                                     ExecutionPointer pointer) {
        if (graph == null || graph.isEmpty()) {
            return new TaskGraph(List.of(), List.of());
        }
        LinkedHashSet<String> completed = new LinkedHashSet<>(pointer == null ? List.of() : pointer.completedNodeIds());
        List<TaskNode> nodes = new ArrayList<>();
        for (TaskNode node : graph.nodes()) {
            if (node == null || completed.contains(node.id())) {
                continue;
            }
            List<String> dependsOn = node.dependsOn().stream()
                    .filter(dependency -> !completed.contains(dependency))
                    .toList();
            nodes.add(new TaskNode(
                    node.id(),
                    node.target(),
                    node.params(),
                    dependsOn,
                    node.saveAs(),
                    node.optional(),
                    node.maxAttempts()
            ));
        }
        return nodes.isEmpty() ? new TaskGraph(List.of(), List.of()) : new TaskGraph(nodes);
    }

    private String nextNodeId(TaskGraph graph, List<String> successfulNodeIds) {
        if (graph == null || graph.nodes() == null) {
            return "";
        }
        LinkedHashSet<String> completed = new LinkedHashSet<>(successfulNodeIds == null ? List.of() : successfulNodeIds);
        for (TaskNode node : graph.nodes()) {
            if (node != null && !completed.contains(node.id())) {
                return node.id();
            }
        }
        return "";
    }
}
