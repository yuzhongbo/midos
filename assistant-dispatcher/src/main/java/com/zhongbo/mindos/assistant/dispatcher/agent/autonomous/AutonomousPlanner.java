package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.dispatcher.FinalPlanner;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AutonomousPlanner {

    private final FinalPlanner finalPlanner;

    @Autowired
    public AutonomousPlanner(SkillCatalogFacade skillEngine,
                             DispatcherMemoryFacade dispatcherMemoryFacade) {
        this.finalPlanner = new FinalPlanner(skillEngine, dispatcherMemoryFacade);
    }

    AutonomousPlanner(FinalPlanner finalPlanner) {
        this.finalPlanner = finalPlanner == null ? new FinalPlanner() : finalPlanner;
    }

    public TaskGraph plan(Goal goal, AutonomousPlanningContext context) {
        return finalPlanner.plan(goal, context);
    }

    public TaskGraph replan(Goal goal,
                            GoalExecutionResult result,
                            EvaluationResult evaluation,
                            AutonomousPlanningContext context) {
        return finalPlanner.replan(goal, result, evaluation, context);
    }
}
