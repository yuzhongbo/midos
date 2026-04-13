package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanner;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ConservativePlannerAgent extends PlannerAgent {

    public ConservativePlannerAgent(AutonomousPlanner autonomousPlanner) {
        super("conservative-planner", "conservative", autonomousPlanner);
    }

    @Override
    protected TaskGraph proposePlan(Goal goal, AutonomousPlanningContext context) {
        TaskGraph base = basePlan(goal, context);
        if (base == null || base.isEmpty()) {
            return base;
        }
        TaskGraph stabilized = withPreflight(goal, base);
        List<TaskNode> tunedNodes = new ArrayList<>();
        int retryBudgetCap = retryBudgetCap(context, 3);
        for (TaskNode node : stabilized.nodes()) {
            if (node == null) {
                continue;
            }
            Map<String, Object> params = new LinkedHashMap<>(node.params());
            params.put("plannerRiskMode", "stable");
            tunedNodes.add(new TaskNode(
                    node.id(),
                    node.target(),
                    params,
                    node.dependsOn(),
                    node.saveAs(),
                    node.optional(),
                    Math.max(1, Math.min(retryBudgetCap, Math.max(3, node.maxAttempts())))
            ));
        }
        return tunedNodes.isEmpty() ? new TaskGraph(List.of(), List.of()) : new TaskGraph(tunedNodes, stabilized.edges());
    }

    private TaskGraph withPreflight(Goal goal, TaskGraph graph) {
        boolean alreadyPresent = graph.nodes().stream().anyMatch(node -> node != null && "semantic.analyze".equalsIgnoreCase(node.target()));
        if (alreadyPresent) {
            return graph;
        }
        TaskNode preflight = new TaskNode(
                "planner-preflight",
                "semantic.analyze",
                Map.of(
                        "input", goal == null ? "" : goal.description(),
                        "goal", goal == null ? "" : goal.description(),
                        "plannerPreflight", true
                ),
                List.of(),
                "plannerPreflight",
                true,
                1
        );
        List<TaskNode> nodes = new ArrayList<>();
        nodes.add(preflight);
        for (TaskNode node : graph.nodes()) {
            if (node == null) {
                continue;
            }
            List<String> dependsOn = node.dependsOn();
            if (dependsOn == null || dependsOn.isEmpty()) {
                dependsOn = List.of(preflight.id());
            }
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
        return new TaskGraph(nodes);
    }
}
