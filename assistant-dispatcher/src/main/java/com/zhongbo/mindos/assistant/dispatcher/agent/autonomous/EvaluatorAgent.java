package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.MasterOrchestrationResult;

public interface EvaluatorAgent {

    AutonomousEvaluation evaluate(AutonomousGoal goal, MasterOrchestrationResult result);

    default AutonomousEvaluation evaluate(AutonomousGoal goal,
                                          MasterOrchestrationResult result,
                                          String userFeedback) {
        return evaluate(goal, result);
    }
}
