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
public class AggressivePlannerAgent extends PlannerAgent {

    public AggressivePlannerAgent(AutonomousPlanner autonomousPlanner) {
        super("aggressive-planner", "aggressive", autonomousPlanner);
    }

    @Override
    protected TaskGraph proposePlan(Goal goal, AutonomousPlanningContext context) {
        TaskGraph base = basePlan(goal, context);
        if (base == null || base.isEmpty()) {
            return base;
        }
        List<TaskNode> tunedNodes = new ArrayList<>();
        boolean hasScout = false;
        for (int index = 0; index < base.nodes().size(); index++) {
            TaskNode node = base.nodes().get(index);
            if (node == null) {
                continue;
            }
            Map<String, Object> params = new LinkedHashMap<>(node.params());
            params.put("plannerRiskMode", "explore");
            List<String> dependsOn = index == 0 ? List.of() : List.of();
            tunedNodes.add(new TaskNode(
                    node.id(),
                    node.target(),
                    params,
                    dependsOn,
                    node.saveAs(),
                    node.optional(),
                    1
            ));
            if (!hasScout && index == 0) {
                tunedNodes.add(0, new TaskNode(
                        "planner-scout",
                        "semantic.analyze",
                        Map.of(
                                "input", goal == null ? "" : goal.description(),
                                "goal", goal == null ? "" : goal.description(),
                                "plannerScout", true
                        ),
                        List.of(),
                        "plannerScout",
                        true,
                        1
                ));
                hasScout = true;
            }
        }
        return tunedNodes.isEmpty() ? new TaskGraph(List.of(), List.of()) : new TaskGraph(tunedNodes);
    }
}
