package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.HumanAICoRuntime;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.HumanCycleOutcome;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.HumanPreference;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.InterventionEvent;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.SharedDecision;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.AGIRuntimeKernel;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.ExecutionHistory;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.ExecutionPolicy;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.ExecutionState;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.Node;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.RuntimeState;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.Task;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.TaskHandle;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.TaskState;
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

    private final AGIRuntimeKernel runtimeKernel;
    private final GoalMemory goalMemory;
    private final ExecutionMemoryFacade executionMemoryFacade;
    private final int maxIterations;
    private final HumanAICoRuntime humanAICoRuntime;

    public AutonomousLoopEngine(AGIRuntimeKernel runtimeKernel,
                                GoalMemory goalMemory,
                                ExecutionMemoryFacade executionMemoryFacade,
                                @Value("${mindos.autonomous.loop.max-iterations:5}") int maxIterations) {
        this(runtimeKernel, goalMemory, executionMemoryFacade, maxIterations, null);
    }

    @Autowired
    public AutonomousLoopEngine(AGIRuntimeKernel runtimeKernel,
                                GoalMemory goalMemory,
                                ExecutionMemoryFacade executionMemoryFacade,
                                @Value("${mindos.autonomous.loop.max-iterations:5}") int maxIterations,
                                HumanAICoRuntime humanAICoRuntime) {
        this.runtimeKernel = runtimeKernel;
        this.goalMemory = goalMemory;
        this.executionMemoryFacade = executionMemoryFacade;
        this.maxIterations = Math.max(1, maxIterations);
        this.humanAICoRuntime = humanAICoRuntime;
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
        HumanAICoRuntime safeCoRuntime = humanAICoRuntime;
        Goal currentGoal = (goal == null ? Goal.of("", 0.0) : goal).markInProgress();
        Instant startedAt = Instant.now();
        List<GoalMemory.GoalTrace> traces = new ArrayList<>();
        List<WorldMemory.ExecutionTrace> worldTraces = List.of();
        List<com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.OrgMemory.OrgExecutionTrace> orgTraces = List.of();
        List<com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization.CivilizationMemory.CivilizationTrace> civilizationTraces = List.of();
        List<SharedDecision> sharedDecisions = new ArrayList<>();
        List<InterventionEvent> interventionEvents = new ArrayList<>();
        HumanPreference humanPreference = HumanPreference.defaultPreference();
        double trustScore = 0.0;
        RuntimeState currentRuntimeState = null;
        ExecutionHistory runtimeHistory = null;
        EvaluationResult lastEvaluation = EvaluationResult.initial(currentGoal);
        Map<String, Object> effectiveProfileContext = profileContext == null ? Map.of() : Map.copyOf(new java.util.LinkedHashMap<>(profileContext));
        TaskHandle handle = new TaskHandle("");
        String stopReason = "";
        try {
            if (safeCoRuntime != null) {
                safeCoRuntime.startSession(userId, currentGoal, effectiveProfileContext);
                effectiveProfileContext = safeCoRuntime.enrichProfileContext(userId, currentGoal, effectiveProfileContext);
                humanPreference = safeCoRuntime.predictPreference(userId, currentGoal, effectiveProfileContext);
            }
            handle = submitTask(currentGoal, userId, effectiveProfileContext);
            for (int iteration = 1; iteration <= maxIterations && !currentGoal.isTerminal(); iteration++) {
                ExecutionState executionState = runtimeKernel == null ? null : runtimeKernel.resume(handle);
                currentRuntimeState = executionState == null ? null : executionState.runtimeState();
                runtimeHistory = executionState == null ? null : executionState.history();
                GoalExecutionResult executionResult = executionState == null ? null : executionState.result();
                EvaluationResult evaluation = executionState == null ? null : executionState.evaluation();
                HumanCycleOutcome humanCycle = currentRuntimeState == null || safeCoRuntime == null
                        ? HumanCycleOutcome.empty()
                        : safeCoRuntime.afterCycle(handle, currentRuntimeState, executionResult, evaluation);
                humanPreference = humanCycle.preference();
                trustScore = humanCycle.trustScore();
                if (safeCoRuntime != null && handle != null && !handle.isEmpty()) {
                    sharedDecisions = new ArrayList<>(safeCoRuntime.decisions(handle));
                    interventionEvents = new ArrayList<>(safeCoRuntime.interventions(handle));
                }
                if ((humanCycle.correctionApplied() || humanCycle.interrupted()) && runtimeKernel != null) {
                    currentRuntimeState = runtimeKernel.state(handle);
                    runtimeHistory = runtimeKernel.history(handle);
                }
                if (currentRuntimeState != null && currentRuntimeState.state() == TaskState.WAITING && executionResult == null) {
                    stopReason = currentRuntimeState.summary().isBlank() ? "waiting-human-approval" : currentRuntimeState.summary();
                    break;
                }
                TaskGraph graph = executionResult == null
                        ? (currentRuntimeState == null || currentRuntimeState.plan() == null ? null : currentRuntimeState.plan().graph())
                        : executionResult.graph();
                if (graph == null || graph.isEmpty()) {
                    GoalExecutionResult planFailure = syntheticFailure(
                            currentGoal,
                            iterationContext(userId, currentGoal, effectiveProfileContext, safeGoalMemory, iteration, null, lastEvaluation),
                            "runtime produced empty task graph"
                    );
                    EvaluationResult emptyEvaluation = evaluation != null
                            ? evaluation
                            : new EvaluationResult(
                            currentGoal.goalId(),
                            GoalStatus.FAILED,
                            false,
                            false,
                            false,
                            "runtime produced empty task graph",
                            List.of(),
                            List.of(),
                            currentRuntimeState == null || currentRuntimeState.pointer() == null ? List.of() : currentRuntimeState.pointer().failedTargets(),
                            0.0,
                            Instant.now()
                    );
                    Goal failedGoal = currentGoal.markFailed();
                    traces.add(safeGoalMemory.record(new GoalMemory.GoalTrace(
                            failedGoal,
                            new TaskGraph(List.of(), List.of()),
                            planFailure,
                            emptyEvaluation,
                            iteration,
                            Instant.now()
                    )));
                    currentGoal = failedGoal;
                    stopReason = "runtime-empty";
                    break;
                }
                if (executionResult == null) {
                    executionResult = syntheticFailure(
                            currentGoal,
                            iterationContext(userId, currentGoal, effectiveProfileContext, safeGoalMemory, iteration, null, lastEvaluation),
                            graph,
                            runtimeKernel == null ? "runtime kernel unavailable" : "execution engine produced no result"
                    );
                }
                if (executionMemoryFacade != null) {
                    executionMemoryFacade.record(
                            executionResult.userId(),
                            executionResult.userInput(),
                            executionResult.finalResult(),
                            executionResult.executionTrace()
                    );
                }
                EvaluationResult effectiveEvaluation = evaluation == null ? EvaluationResult.initial(currentGoal) : evaluation;
                Goal nextGoal = switch (effectiveEvaluation.goalStatus()) {
                    case COMPLETED -> currentGoal.markCompleted();
                    case FAILED -> currentGoal.markFailed();
                    case ACTIVE, IN_PROGRESS -> currentGoal.markInProgress();
                };
                traces.add(safeGoalMemory.record(new GoalMemory.GoalTrace(
                        nextGoal,
                        graph,
                        executionResult,
                        effectiveEvaluation,
                        iteration,
                        Instant.now()
                )));
                if (humanCycle.interrupted()) {
                    currentGoal = nextGoal.markInProgress();
                    stopReason = "interrupted-by-human";
                    break;
                }
                if (effectiveEvaluation.isSuccess()) {
                    currentGoal = nextGoal.markCompleted();
                    stopReason = "completed";
                    break;
                }
                if (humanCycle.correctionApplied()) {
                    currentGoal = nextGoal.markInProgress();
                    lastEvaluation = effectiveEvaluation;
                    continue;
                }
                if (currentRuntimeState != null && currentRuntimeState.state() == TaskState.SUSPENDED) {
                    currentGoal = nextGoal;
                    stopReason = "suspended";
                    break;
                }
                if (!effectiveEvaluation.needsReplan()) {
                    currentGoal = nextGoal.markFailed();
                    stopReason = "evaluation-stop";
                    break;
                }
                currentGoal = nextGoal;
                lastEvaluation = effectiveEvaluation;
            }
        } finally {
            if (safeCoRuntime != null) {
                safeCoRuntime.finishSession();
            }
        }
        if (stopReason.isBlank()) {
            if (currentRuntimeState != null && !currentRuntimeState.state().isTerminal()) {
                if (runtimeKernel != null && currentRuntimeState.state() != TaskState.WAITING) {
                    runtimeKernel.suspend(handle);
                    currentRuntimeState = runtimeKernel.state(handle);
                    runtimeHistory = runtimeKernel.history(handle);
                }
                stopReason = currentRuntimeState != null && currentRuntimeState.state() == TaskState.WAITING
                        ? (currentRuntimeState.summary().isBlank() ? "waiting-human-approval" : currentRuntimeState.summary())
                        : "suspended";
            } else {
                if (!currentGoal.isCompleted()) {
                    currentGoal = currentGoal.markFailed();
                }
                stopReason = currentGoal.isCompleted() ? "completed" : "max-iterations";
            }
        }
        return new AutonomousGoalRunResult(
                currentGoal,
                traces,
                worldTraces,
                null,
                orgTraces,
                null,
                civilizationTraces,
                currentRuntimeState,
                runtimeHistory,
                sharedDecisions,
                interventionEvents,
                humanPreference,
                trustScore,
                stopReason,
                startedAt,
                Instant.now()
        );
    }

    private TaskHandle submitTask(Goal goal,
                                  String userId,
                                  Map<String, Object> profileContext) {
        if (runtimeKernel == null) {
            return new TaskHandle("");
        }
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(profileContext == null ? Map.of() : profileContext);
        metadata.put("userId", userId == null ? "" : userId);
        metadata.put("input", goal == null ? "" : goal.description());
        metadata.put("goal.priority", goal == null ? 0.0 : goal.priority());
        Task task = Task.fromGoal(goal, resolvePolicy(profileContext), metadata);
        TaskHandle handle = runtimeKernel.submit(task);
        String migrationTarget = profileContext == null ? null : stringValue(profileContext.get("runtimeTargetNode"));
        if (migrationTarget != null && !migrationTarget.isBlank()) {
            runtimeKernel.migrate(handle, new Node(migrationTarget, migrationTarget, Map.of("source", "profileContext")));
        }
        return handle;
    }

    private AutonomousPlanningContext iterationContext(String userId,
                                                       Goal goal,
                                                       Map<String, Object> profileContext,
                                                       GoalMemory goalMemory,
                                                       int iteration,
                                                       GoalExecutionResult lastResult,
                                                       EvaluationResult lastEvaluation) {
        return new AutonomousPlanningContext(
                userId == null ? "" : userId,
                goal == null ? "" : goal.description(),
                profileContext == null ? Map.of() : profileContext,
                goalMemory,
                iteration,
                lastResult,
                lastEvaluation,
                lastEvaluation == null ? List.of() : lastEvaluation.failedTargets()
        );
    }

    private ExecutionPolicy resolvePolicy(Map<String, Object> profileContext) {
        String rawPolicy = profileContext == null ? "" : stringValue(profileContext.get("executionPolicy"));
        if (rawPolicy == null || rawPolicy.isBlank()) {
            return ExecutionPolicy.AUTONOMOUS;
        }
        try {
            return ExecutionPolicy.valueOf(rawPolicy.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ignore) {
            return ExecutionPolicy.AUTONOMOUS;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
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
