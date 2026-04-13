package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.DAGExecutor;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.StructuredExecutionRuntime;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import com.zhongbo.mindos.assistant.skill.SkillExecutionGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class AutonomousGraphExecutor {

    private final SkillExecutionGateway skillExecutionGateway;
    private final StructuredExecutionRuntime executionRuntime = new StructuredExecutionRuntime();
    private final long mcpPerSkillTimeoutMs;

    @Autowired
    public AutonomousGraphExecutor(SkillExecutionGateway skillExecutionGateway,
                                   @Value("${mindos.dispatcher.parallel-routing.per-skill-timeout-ms:2500}") long mcpPerSkillTimeoutMs) {
        this.skillExecutionGateway = skillExecutionGateway;
        this.mcpPerSkillTimeoutMs = Math.max(250L, mcpPerSkillTimeoutMs);
    }

    public GoalExecutionResult execute(Goal goal,
                                       TaskGraph graph,
                                       AutonomousPlanningContext context) {
        Goal safeGoal = goal == null ? Goal.of("", 0.0) : goal;
        AutonomousPlanningContext safeContext = AutonomousPlanningContext.safe(context);
        String userId = safeContext.userId().isBlank()
                ? String.valueOf(safeGoal.metadata().getOrDefault("userId", ""))
                : safeContext.userId();
        String userInput = safeContext.userInput().isBlank() ? safeGoal.description() : safeContext.userInput();
        Map<String, Object> attributes = new LinkedHashMap<>(safeContext.profileContext());
        attributes.putAll(safeGoal.metadata());
        attributes.put("autonomousGoalId", safeGoal.goalId());
        attributes.put("autonomousGoalDescription", safeGoal.description());
        attributes.put("autonomousGoalStatus", safeGoal.status().name());
        attributes.put("autonomousIteration", safeContext.iteration());
        SkillContext baseContext = new SkillContext(userId, userInput, attributes);
        Instant startedAt = Instant.now();
        TaskGraph safeGraph = graph == null ? new TaskGraph(List.of(), List.of()) : graph;
        TaskGraphExecutionResult executionResult = executionRuntime.execute(safeGraph, baseContext, this::executeNode);
        SkillResult finalResult = executionResult.finalResult() == null
                ? SkillResult.failure("autonomous.executor", "missing execution result")
                : executionResult.finalResult();
        return new GoalExecutionResult(
                safeGoal,
                safeGraph,
                executionResult,
                finalResult,
                userId,
                userInput,
                safeContext.iteration(),
                startedAt,
                Instant.now()
        );
    }

    private DAGExecutor.NodeExecution executeNode(TaskNode node, SkillContext nodeContext) {
        if (node == null || node.target().isBlank()) {
            return new DAGExecutor.NodeExecution(SkillResult.failure("autonomous.executor", "missing task target"), false);
        }
        if (skillExecutionGateway == null) {
            return new DAGExecutor.NodeExecution(SkillResult.failure(node.target(), "skill execution gateway unavailable"), false);
        }
        Map<String, Object> params = resolvedParams(nodeContext);
        try {
            CompletableFuture<SkillResult> future = skillExecutionGateway.executeDslAsync(new SkillDsl(node.target(), params), nodeContext);
            if (node.target().startsWith("mcp.")) {
                future = future.completeOnTimeout(
                        SkillResult.failure(node.target(), "timeout"),
                        mcpPerSkillTimeoutMs,
                        TimeUnit.MILLISECONDS
                );
            }
            return new DAGExecutor.NodeExecution(future.join(), false);
        } catch (Exception ex) {
            String message = ex.getMessage() == null || ex.getMessage().isBlank()
                    ? ex.getClass().getSimpleName()
                    : ex.getMessage();
            return new DAGExecutor.NodeExecution(SkillResult.failure(node.target(), message), false);
        }
    }

    private Map<String, Object> resolvedParams(SkillContext context) {
        if (context == null || context.attributes() == null) {
            return Map.of();
        }
        Object raw = context.attributes().get(DAGExecutor.RESOLVED_NODE_PARAMS_KEY);
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> params.put(String.valueOf(key), value));
        return params.isEmpty() ? Map.of() : Map.copyOf(params);
    }
}
