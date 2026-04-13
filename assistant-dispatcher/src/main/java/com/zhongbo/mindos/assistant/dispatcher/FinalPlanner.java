package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.EvaluationResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalDecomposer;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalTask;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionTargetResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class FinalPlanner {

    public static final String PLANNER_METADATA_PREFIX = "_planner.";
    public static final String PLANNER_ROUTE_SOURCE_KEY = "_plannerRouteSource";
    public static final String PLANNER_ROUTE_SOURCE_METADATA_KEY = PLANNER_METADATA_PREFIX + "routeSource";
    public static final String PLANNER_CANDIDATES_KEY = PLANNER_METADATA_PREFIX + "candidates";
    public static final String PLANNER_SIGNALS_KEY = PLANNER_METADATA_PREFIX + "signals";
    public static final String PLANNER_FAILED_TARGETS_KEY = PLANNER_METADATA_PREFIX + "failedTargets";
    public static final String PLANNER_CLARIFY_MESSAGE_KEY = PLANNER_METADATA_PREFIX + "clarifyMessage";
    public static final String RULE_FALLBACK_SOURCE = "rule-fallback";

    private final PlannerSignalCollector signalCollector;
    private final SignalFusionEngine fusionEngine;
    private final ConfidenceGate confidenceGate;
    private final PlannerDecisionFactory decisionFactory;
    private final ReplanEngine replanEngine;
    private final GoalDecomposer goalDecomposer;
    private final DecisionTargetResolver targetResolver = new DecisionTargetResolver();

    public FinalPlanner() {
        this(null, null);
    }

    public FinalPlanner(com.zhongbo.mindos.assistant.skill.SkillCatalogFacade skillEngine) {
        this(skillEngine, null);
    }

    public FinalPlanner(com.zhongbo.mindos.assistant.skill.SkillCatalogFacade skillEngine,
                        DispatcherMemoryFacade dispatcherMemoryFacade) {
        this(
                new DecisionParamAssembler(new SkillCommandAssembler(new SkillDslParser(new SkillDslValidator()), false)),
                new PlannerSignalCollector(skillEngine),
                new SignalFusionEngine(dispatcherMemoryFacade),
                new ConfidenceGate(),
                new GoalDecomposer()
        );
    }

    FinalPlanner(DecisionParamAssembler decisionParamAssembler,
                 PlannerSignalCollector signalCollector,
                 SignalFusionEngine fusionEngine,
                 ConfidenceGate confidenceGate,
                 GoalDecomposer goalDecomposer) {
        this.signalCollector = signalCollector == null ? new PlannerSignalCollector() : signalCollector;
        this.fusionEngine = fusionEngine == null ? new SignalFusionEngine() : fusionEngine;
        this.confidenceGate = confidenceGate == null ? new ConfidenceGate() : confidenceGate;
        this.decisionFactory = new PlannerDecisionFactory(decisionParamAssembler);
        this.replanEngine = new ReplanEngine(this.fusionEngine, this.decisionFactory);
        this.goalDecomposer = goalDecomposer == null ? new GoalDecomposer() : goalDecomposer;
    }

    public Decision plan(DecisionOrchestrator.UserInput input) {
        DecisionOrchestrator.UserInput safeInput = DecisionOrchestrator.UserInput.safe(input);
        List<DecisionSignal> signals = signalCollector.collect(safeInput);
        return confidenceGate.check(decisionFactory.build(
                safeInput,
                fusionEngine.fuse(safeInput, signals, java.util.Set.of()),
                java.util.Set.of()
        ));
    }

    public Decision plan(DecisionOrchestrator.UserInput input, List<DecisionSignal> signals) {
        DecisionOrchestrator.UserInput safeInput = DecisionOrchestrator.UserInput.safe(input);
        List<DecisionSignal> effectiveSignals = signals == null || signals.isEmpty()
                ? signalCollector.collect(safeInput)
                : List.copyOf(signals);
        return confidenceGate.check(decisionFactory.build(
                safeInput,
                fusionEngine.fuse(safeInput, effectiveSignals, java.util.Set.of()),
                java.util.Set.of()
        ));
    }

    public Decision replan(DecisionOrchestrator.UserInput input, Decision failedDecision) {
        DecisionOrchestrator.UserInput safeInput = DecisionOrchestrator.UserInput.safe(input);
        List<DecisionSignal> rememberedSignals = decisionFactory.signalsOf(failedDecision);
        List<DecisionSignal> effectiveSignals = rememberedSignals.isEmpty()
                ? signalCollector.collect(safeInput)
                : rememberedSignals;
        return confidenceGate.check(replanEngine.replan(safeInput, effectiveSignals, failedDecision));
    }

    public TaskGraph plan(Goal goal, AutonomousPlanningContext context) {
        return buildGoalGraph(goal, AutonomousPlanningContext.safe(context), Set.of(), canonicalTargetsOf(context == null ? List.of() : context.excludedTargets()));
    }

    public TaskGraph replan(Goal goal,
                            GoalExecutionResult result,
                            EvaluationResult evaluation,
                            AutonomousPlanningContext context) {
        AutonomousPlanningContext safeContext = AutonomousPlanningContext.safe(context);
        Set<String> completedTaskIds = new LinkedHashSet<>();
        if (safeContext.goalMemory() != null && goal != null) {
            completedTaskIds.addAll(safeContext.goalMemory().completedTaskIds(goal.goalId()));
        }
        if (evaluation != null) {
            completedTaskIds.addAll(evaluation.completedTaskIds());
        }
        Set<String> excludedTargets = new LinkedHashSet<>(canonicalTargetsOf(safeContext.excludedTargets()));
        if (evaluation != null) {
            excludedTargets.addAll(canonicalTargetsOf(evaluation.failedTargets()));
        }
        if (result != null) {
            excludedTargets.addAll(canonicalTargetsOf(result.failedTargets()));
        }
        return buildGoalGraph(goal, safeContext, completedTaskIds, excludedTargets);
    }

    private TaskGraph buildGoalGraph(Goal goal,
                                     AutonomousPlanningContext context,
                                     Set<String> completedTaskIds,
                                     Set<String> excludedTargets) {
        Goal safeGoal = goal == null ? Goal.of("", 0.0) : goal;
        AutonomousPlanningContext safeContext = AutonomousPlanningContext.safe(context);
        Optional<TaskGraph> learnedGraph = reuseLearnedGraph(safeGoal, safeContext, completedTaskIds, excludedTargets);
        if (learnedGraph.isPresent()) {
            return learnedGraph.get();
        }
        List<GoalTask> tasks = goalDecomposer.decompose(safeGoal);
        if (tasks.isEmpty()) {
            return new TaskGraph(List.of(), List.of());
        }
        Map<String, PlannedGoalTask> plannedTasks = new LinkedHashMap<>();
        for (GoalTask task : tasks) {
            if (task == null || completedTaskIds.contains(task.taskId())) {
                continue;
            }
            Decision decision = planGoalTask(safeGoal, task, safeContext, excludedTargets);
            if (decision == null || decision.target() == null || decision.target().isBlank() || decision.requireClarify()) {
                continue;
            }
            plannedTasks.put(task.taskId(), new PlannedGoalTask(task, decision));
        }
        if (plannedTasks.isEmpty()) {
            return new TaskGraph(List.of(), List.of());
        }
        List<TaskNode> nodes = new ArrayList<>();
        for (PlannedGoalTask plannedTask : plannedTasks.values()) {
            GoalTask task = plannedTask.task();
            Decision decision = plannedTask.decision();
            List<String> dependsOn = task.dependsOn().stream()
                    .filter(plannedTasks::containsKey)
                    .filter(dependency -> !completedTaskIds.contains(dependency))
                    .toList();
            Map<String, Object> nodeParams = new LinkedHashMap<>(task.params());
            if (decision.params() != null && !decision.params().isEmpty()) {
                nodeParams.putAll(decision.params());
            }
            nodeParams.putAll(goalMetadata(safeGoal, safeContext, task, decision.target()));
            nodes.add(new TaskNode(
                    task.taskId(),
                    decision.target(),
                    nodeParams,
                    dependsOn,
                    task.taskId(),
                    task.optional(),
                    task.maxAttempts()
            ));
        }
        return nodes.isEmpty() ? new TaskGraph(List.of(), List.of()) : new TaskGraph(nodes);
    }

    private Decision planGoalTask(Goal goal,
                                  GoalTask task,
                                  AutonomousPlanningContext context,
                                  Set<String> excludedTargets) {
        Map<String, Object> attributes = new LinkedHashMap<>(context.profileContext());
        attributes.putAll(goal.metadata());
        attributes.putAll(task.params());
        attributes.putAll(goalMetadata(goal, context, task, task.targetHint()));
        if (!task.targetHint().isBlank()) {
            attributes.put("explicitTarget", task.targetHint());
        }
        SkillContext skillContext = new SkillContext(context.userId(), task.description(), attributes);
        DecisionOrchestrator.UserInput taskInput = new DecisionOrchestrator.UserInput(
                context.userId(),
                task.description(),
                skillContext,
                context.profileContext()
        );
        Decision decision = plan(taskInput);
        if (isUsableTaskDecision(decision, excludedTargets)) {
            return decision;
        }
        if (task.targetHint().isBlank()) {
            return null;
        }
        Decision hintedDecision = plan(taskInput, List.of(new DecisionSignal(task.targetHint(), 0.99, "goal-decomposition")));
        return isUsableTaskDecision(hintedDecision, excludedTargets) ? hintedDecision : null;
    }

    private boolean isUsableTaskDecision(Decision decision, Set<String> excludedTargets) {
        if (decision == null || decision.requireClarify()) {
            return false;
        }
        String canonicalTarget = targetResolver.canonicalize(decision.target());
        return !canonicalTarget.isBlank() && !excludedTargets.contains(canonicalTarget);
    }

    private Map<String, Object> goalMetadata(Goal goal,
                                             AutonomousPlanningContext context,
                                             GoalTask task,
                                             String targetHint) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("autonomousGoalId", goal.goalId());
        metadata.put("autonomousGoalDescription", goal.description());
        metadata.put("autonomousGoalStatus", goal.status().name());
        metadata.put("autonomousGoalPriority", goal.priority());
        metadata.put("autonomousIteration", context.iteration());
        metadata.put("autonomousTaskId", task.taskId());
        metadata.put("autonomousTaskDescription", task.description());
        if (context.lastEvaluation() != null && !context.lastEvaluation().summary().isBlank()) {
            metadata.put("autonomousLastEvaluation", context.lastEvaluation().summary());
        }
        if (context.lastResult() != null && context.lastResult().finalResult() != null) {
            metadata.put("autonomousLastResultSkill", context.lastResult().finalResult().skillName());
            metadata.put("autonomousLastResultOutput", context.lastResult().finalResult().output());
        }
        if (targetHint != null && !targetHint.isBlank()) {
            metadata.put("autonomousTargetHint", targetHint);
        }
        return metadata;
    }

    private Optional<TaskGraph> reuseLearnedGraph(Goal goal,
                                                  AutonomousPlanningContext context,
                                                  Set<String> completedTaskIds,
                                                  Set<String> excludedTargets) {
        if (context.goalMemory() == null || context.lastResult() != null) {
            return Optional.empty();
        }
        Optional<TaskGraph> template = context.goalMemory().latestSuccessfulGraph(goal);
        if (template.isEmpty()) {
            return Optional.empty();
        }
        LinkedHashSet<String> keptNodeIds = new LinkedHashSet<>();
        for (TaskNode node : template.get().nodes()) {
            if (node == null || completedTaskIds.contains(node.id())) {
                continue;
            }
            String canonicalTarget = targetResolver.canonicalize(node.target());
            if (!canonicalTarget.isBlank() && !excludedTargets.contains(canonicalTarget)) {
                keptNodeIds.add(node.id());
            }
        }
        if (keptNodeIds.isEmpty()) {
            return Optional.empty();
        }
        List<TaskNode> copiedNodes = new ArrayList<>();
        for (TaskNode node : template.get().nodes()) {
            if (node == null || !keptNodeIds.contains(node.id())) {
                continue;
            }
            Map<String, Object> params = new LinkedHashMap<>(node.params());
            params.putAll(goal.metadata());
            params.put("autonomousGoalId", goal.goalId());
            params.put("autonomousGoalDescription", goal.description());
            params.put("autonomousGoalStatus", goal.status().name());
            params.put("autonomousIteration", context.iteration());
            List<String> dependsOn = node.dependsOn().stream()
                    .filter(keptNodeIds::contains)
                    .toList();
            copiedNodes.add(new TaskNode(
                    node.id(),
                    node.target(),
                    params,
                    dependsOn,
                    node.saveAs(),
                    node.optional(),
                    node.maxAttempts()
            ));
        }
        return copiedNodes.isEmpty() ? Optional.empty() : Optional.of(new TaskGraph(copiedNodes));
    }

    private Set<String> canonicalTargetsOf(List<String> rawTargets) {
        if (rawTargets == null || rawTargets.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> canonicalTargets = new LinkedHashSet<>();
        for (String rawTarget : rawTargets) {
            String canonicalTarget = targetResolver.canonicalize(rawTarget);
            if (!canonicalTarget.isBlank()) {
                canonicalTargets.add(canonicalTarget);
            }
        }
        return canonicalTargets.isEmpty() ? Set.of() : Set.copyOf(canonicalTargets);
    }

    private record PlannedGoalTask(GoalTask task, Decision decision) {
    }
}
