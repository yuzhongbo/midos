package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanner;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class PlannerAgent {

    private final String agentId;
    private final String strategyType;
    protected final AutonomousPlanner autonomousPlanner;

    protected PlannerAgent(String agentId,
                           String strategyType,
                           AutonomousPlanner autonomousPlanner) {
        this.agentId = agentId == null ? "" : agentId.trim();
        this.strategyType = strategyType == null ? "balanced" : strategyType.trim().toLowerCase(java.util.Locale.ROOT);
        this.autonomousPlanner = autonomousPlanner;
    }

    public String agentId() {
        return agentId;
    }

    public String strategyType() {
        return strategyType;
    }

    public final TaskGraph plan(Goal goal, AutonomousPlanningContext context) {
        TaskGraph proposal = proposePlan(goal, AutonomousPlanningContext.safe(context));
        return stampMetadata(proposal);
    }

    protected abstract TaskGraph proposePlan(Goal goal, AutonomousPlanningContext context);

    protected TaskGraph basePlan(Goal goal, AutonomousPlanningContext context) {
        AutonomousPlanningContext safeContext = AutonomousPlanningContext.safe(context);
        if (autonomousPlanner == null) {
            return new TaskGraph(List.of(), List.of());
        }
        return safeContext.lastResult() == null || safeContext.lastEvaluation() == null
                ? autonomousPlanner.plan(goal, safeContext)
                : autonomousPlanner.replan(goal, safeContext.lastResult(), safeContext.lastEvaluation(), safeContext);
    }

    protected TaskGraph stampMetadata(TaskGraph graph) {
        if (graph == null || graph.isEmpty()) {
            return new TaskGraph(List.of(), List.of());
        }
        List<TaskNode> stampedNodes = new ArrayList<>();
        for (TaskNode node : graph.nodes()) {
            if (node == null) {
                continue;
            }
            Map<String, Object> params = new LinkedHashMap<>(node.params());
            params.put("plannerAgentId", agentId());
            params.put("plannerStrategyType", strategyType());
            stampedNodes.add(new TaskNode(
                    node.id(),
                    node.target(),
                    params,
                    node.dependsOn(),
                    node.saveAs(),
                    node.optional(),
                    node.maxAttempts()
            ));
        }
        return stampedNodes.isEmpty() ? new TaskGraph(List.of(), List.of()) : new TaskGraph(stampedNodes, graph.edges());
    }
}
