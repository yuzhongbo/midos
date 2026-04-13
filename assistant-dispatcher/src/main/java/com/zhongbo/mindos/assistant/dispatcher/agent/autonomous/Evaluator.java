package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

public interface Evaluator {

    EvaluationResult evaluate(GoalExecutionResult result, Goal goal);
}
