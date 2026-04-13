package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ExecutionMemoryFacade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
public class AutonomousLoopEngine {

    private final AutonomousPlanner planner;
    private final AutonomousGraphExecutor executor;
    private final Evaluator evaluator;
    private final GoalMemory goalMemory;
    private final ExecutionMemoryFacade executionMemoryFacade;
    private final int maxIterations;

    @Autowired
    public AutonomousLoopEngine(AutonomousPlanner planner,
                                AutonomousGraphExecutor executor,
                                Evaluator evaluator,
                                GoalMemory goalMemory,
                                ExecutionMemoryFacade executionMemoryFacade,
                                @Value("${mindos.autonomous.loop.max-iterations:5}") int maxIterations) {
        this.planner = planner;
        this.executor = executor;
        this.evaluator = evaluator;
        this.goalMemory = goalMemory;
        this.executionMemoryFacade = executionMemoryFacade;
        this.maxIterations = Math.max(1, maxIterations);
    }

    public AutonomousGoalRunResult run(Goal goal) {
        return run(goal, "", Map.of());
    }

    public AutonomousGoalRunResult run(String userId,
                                       String goalDescription,
                                       Map<String, Object> profileContext) {
        return run(Goal.of(goalDescription, 1.0), userId, profileContext);
    }

    public AutonomousGoalRunResult run(Goal goal,
                                       String userId,
                                       Map<String, Object> profileContext) {
        Goal currentGoal = (goal == null ? Goal.of("", 0.0) : goal).markInProgress();
        Instant startedAt = Instant.now();
        List<GoalMemory.GoalTrace> traces = new ArrayList<>();
        EvaluationResult lastEvaluation = EvaluationResult.initial(currentGoal);
        GoalExecutionResult lastResult = null;
        AutonomousPlanningContext planningContext = new AutonomousPlanningContext(
                userId,
                currentGoal.description(),
                profileContext,
                goalMemory,
                1,
                null,
                lastEvaluation,
                List.of()
        );
        String stopReason = "";
        for (int iteration = 1; iteration <= maxIterations && !currentGoal.isTerminal(); iteration++) {
            TaskGraph graph = iteration == 1
                    ? planner.plan(currentGoal, planningContext)
                    : planner.replan(currentGoal, lastResult, lastEvaluation, planningContext);
            if (graph == null || graph.isEmpty()) {
                GoalExecutionResult planFailure = syntheticFailure(currentGoal, planningContext, "planner produced empty task graph");
                EvaluationResult evaluation = new EvaluationResult(
                        currentGoal.goalId(),
                        GoalStatus.FAILED,
                        false,
                        false,
                        false,
                        "planner produced empty task graph",
                        List.of(),
                        List.of(),
                        planningContext.excludedTargets(),
                        0.0,
                        Instant.now()
                );
                Goal failedGoal = currentGoal.markFailed();
                traces.add(goalMemory.record(new GoalMemory.GoalTrace(
                        failedGoal,
                        new TaskGraph(List.of(), List.of()),
                        planFailure,
                        evaluation,
                        planningContext.iteration(),
                        Instant.now()
                )));
                currentGoal = failedGoal;
                stopReason = "planner-empty";
                break;
            }
            GoalExecutionResult executionResult = executor.execute(currentGoal, graph, planningContext);
            executionMemoryFacade.record(
                    executionResult.userId(),
                    executionResult.userInput(),
                    executionResult.finalResult(),
                    executionResult.executionTrace()
            );
            EvaluationResult evaluation = evaluator.evaluate(executionResult, currentGoal);
            Goal nextGoal = switch (evaluation.goalStatus()) {
                case COMPLETED -> currentGoal.markCompleted();
                case FAILED -> currentGoal.markFailed();
                case ACTIVE, IN_PROGRESS -> currentGoal.markInProgress();
            };
            traces.add(goalMemory.record(new GoalMemory.GoalTrace(
                    nextGoal,
                    graph,
                    executionResult,
                    evaluation,
                    planningContext.iteration(),
                    Instant.now()
            )));
            if (evaluation.isSuccess()) {
                currentGoal = nextGoal.markCompleted();
                stopReason = "completed";
                break;
            }
            if (!evaluation.needsReplan()) {
                currentGoal = nextGoal.markFailed();
                stopReason = "evaluation-stop";
                break;
            }
            currentGoal = nextGoal;
            lastResult = executionResult;
            lastEvaluation = evaluation;
            planningContext = planningContext.nextIteration(
                    executionResult,
                    evaluation,
                    mergeExcludedTargets(planningContext.excludedTargets(), evaluation.failedTargets())
            );
        }
        if (stopReason.isBlank()) {
            if (!currentGoal.isCompleted()) {
                currentGoal = currentGoal.markFailed();
            }
            stopReason = currentGoal.isCompleted() ? "completed" : "max-iterations";
        }
        return new AutonomousGoalRunResult(currentGoal, traces, stopReason, startedAt, Instant.now());
    }

    private GoalExecutionResult syntheticFailure(Goal goal,
                                                 AutonomousPlanningContext context,
                                                 String message) {
        return new GoalExecutionResult(
                goal,
                new TaskGraph(List.of(), List.of()),
                null,
                SkillResult.failure("autonomous.planner", message),
                context == null ? "" : context.userId(),
                goal == null ? "" : goal.description(),
                context == null ? 1 : context.iteration(),
                Instant.now(),
                Instant.now()
        );
    }

    private List<String> mergeExcludedTargets(List<String> current, List<String> next) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (current != null) {
            merged.addAll(current);
        }
        if (next != null) {
            merged.addAll(next);
        }
        return merged.isEmpty() ? List.of() : List.copyOf(merged);
    }
}
