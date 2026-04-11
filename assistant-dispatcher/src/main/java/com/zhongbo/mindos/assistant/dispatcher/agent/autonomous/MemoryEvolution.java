package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.MasterOrchestrationResult;

public interface MemoryEvolution {

    MemoryEvolutionResult evolve(String userId,
                                 AutonomousGoal goal,
                                 MasterOrchestrationResult execution,
                                 AutonomousEvaluation evaluation,
                                 long durationMs,
                                 int tokenEstimate,
                                 String workerId);
}
