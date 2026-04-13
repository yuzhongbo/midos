package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization.CivilizationCycleResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization.CivilizationMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization.DigitalCivilization;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization.DigitalCivilizationRuntime;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.AIOrganization;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.OrgMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.OrganizationAssessment;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.PlanningOutcome;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.WorldMemory;
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

    private final DigitalCivilizationRuntime civilizationRuntime;
    private final GoalMemory goalMemory;
    private final ExecutionMemoryFacade executionMemoryFacade;
    private final int maxIterations;

    @Autowired
    public AutonomousLoopEngine(DigitalCivilizationRuntime civilizationRuntime,
                                GoalMemory goalMemory,
                                ExecutionMemoryFacade executionMemoryFacade,
                                @Value("${mindos.autonomous.loop.max-iterations:5}") int maxIterations) {
        this.civilizationRuntime = civilizationRuntime;
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
        GoalMemory safeGoalMemory = goalMemory == null ? new GoalMemory() : goalMemory;
        Goal currentGoal = (goal == null ? Goal.of("", 0.0) : goal).markInProgress();
        Instant startedAt = Instant.now();
        List<GoalMemory.GoalTrace> traces = new ArrayList<>();
        List<WorldMemory.ExecutionTrace> worldTraces = new ArrayList<>();
        List<OrgMemory.OrgExecutionTrace> orgTraces = new ArrayList<>();
        List<CivilizationMemory.CivilizationTrace> civilizationTraces = new ArrayList<>();
        AIOrganization currentOrganization = null;
        DigitalCivilization currentCivilization = civilizationRuntime == null ? null : civilizationRuntime.currentCivilization();
        EvaluationResult lastEvaluation = EvaluationResult.initial(currentGoal);
        AutonomousPlanningContext planningContext = new AutonomousPlanningContext(
                userId,
                currentGoal.description(),
                profileContext,
                safeGoalMemory,
                1,
                null,
                lastEvaluation,
                List.of()
        );
        String stopReason = "";
        for (int iteration = 1; iteration <= maxIterations && !currentGoal.isTerminal(); iteration++) {
            CivilizationCycleResult cycle = civilizationRuntime == null
                    ? null
                    : civilizationRuntime.runCycle(currentGoal, planningContext);
            if (cycle != null) {
                currentOrganization = cycle.selectedOrganization();
                currentCivilization = cycle.civilizationAfter();
                if (cycle.worldTrace() != null) {
                    worldTraces.add(cycle.worldTrace());
                }
                if (cycle.orgTrace() != null) {
                    orgTraces.add(cycle.orgTrace());
                }
                if (cycle.trace() != null) {
                    civilizationTraces.add(cycle.trace());
                }
            }
            PlanningOutcome planningOutcome = cycle == null || cycle.organizationCycle() == null ? null : cycle.organizationCycle().planningOutcome();
            TaskGraph graph = planningOutcome == null ? null : planningOutcome.graph();
            if (graph == null || graph.isEmpty()) {
                GoalExecutionResult planFailure = syntheticFailure(currentGoal, planningContext, "planner produced empty task graph");
                EvaluationResult evaluation = cycle != null && cycle.organizationCycle() != null && cycle.organizationCycle().assessment() != null
                        ? cycle.organizationCycle().assessment().evaluation()
                        : new EvaluationResult(
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
                traces.add(safeGoalMemory.record(new GoalMemory.GoalTrace(
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
            GoalExecutionResult executionResult = cycle == null
                    ? syntheticFailure(currentGoal, planningContext, graph, "civilization runtime unavailable")
                    : cycle.organizationCycle() == null || cycle.organizationCycle().executionResult() == null
                    ? syntheticFailure(currentGoal, planningContext, graph, "execution department produced no result")
                    : cycle.organizationCycle().executionResult();
            if (executionMemoryFacade != null) {
                executionMemoryFacade.record(
                        executionResult.userId(),
                        executionResult.userInput(),
                        executionResult.finalResult(),
                        executionResult.executionTrace()
                );
            }
            OrganizationAssessment assessment = cycle == null || cycle.organizationCycle() == null ? null : cycle.organizationCycle().assessment();
            EvaluationResult evaluation = assessment == null || assessment.evaluation() == null
                    ? EvaluationResult.initial(currentGoal)
                    : assessment.evaluation();
            Goal nextGoal = switch (evaluation.goalStatus()) {
                case COMPLETED -> currentGoal.markCompleted();
                case FAILED -> currentGoal.markFailed();
                case ACTIVE, IN_PROGRESS -> currentGoal.markInProgress();
            };
            traces.add(safeGoalMemory.record(new GoalMemory.GoalTrace(
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
        return new AutonomousGoalRunResult(
                currentGoal,
                traces,
                worldTraces,
                currentOrganization,
                orgTraces,
                currentCivilization,
                civilizationTraces,
                stopReason,
                startedAt,
                Instant.now()
        );
    }

    private GoalExecutionResult syntheticFailure(Goal goal,
                                                 AutonomousPlanningContext context,
                                                 String message) {
        return syntheticFailure(goal, context, new TaskGraph(List.of(), List.of()), message);
    }

    private GoalExecutionResult syntheticFailure(Goal goal,
                                                 AutonomousPlanningContext context,
                                                 TaskGraph graph,
                                                 String message) {
        return new GoalExecutionResult(
                goal,
                graph == null ? new TaskGraph(List.of(), List.of()) : graph,
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
