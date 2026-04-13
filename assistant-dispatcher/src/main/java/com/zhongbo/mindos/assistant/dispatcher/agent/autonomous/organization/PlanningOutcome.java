package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.MultiAgentCoordinator;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;

public record PlanningOutcome(StrategyDirective directive,
                              AutonomousPlanningContext planningContext,
                              MultiAgentCoordinator.PlanSelection selection) {

    public boolean hasPlan() {
        return selection != null && selection.hasPlan();
    }

    public TaskGraph graph() {
        return selection == null ? new TaskGraph(java.util.List.of(), java.util.List.of()) : selection.graph();
    }
}
