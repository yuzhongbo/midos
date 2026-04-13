package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanner;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import org.springframework.stereotype.Component;

@Component
public class BalancedPlannerAgent extends PlannerAgent {

    public BalancedPlannerAgent(AutonomousPlanner autonomousPlanner) {
        super("balanced-planner", "balanced", autonomousPlanner);
    }

    @Override
    protected TaskGraph proposePlan(Goal goal, AutonomousPlanningContext context) {
        return basePlan(goal, context);
    }
}
