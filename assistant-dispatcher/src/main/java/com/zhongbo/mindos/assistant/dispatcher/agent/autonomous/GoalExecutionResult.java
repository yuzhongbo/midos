package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.PlanStepDto;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutionResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public record GoalExecutionResult(Goal goal,
                                  TaskGraph graph,
                                  TaskGraphExecutionResult graphResult,
                                  SkillResult finalResult,
                                  String userId,
                                  String userInput,
                                  int iteration,
                                  Instant startedAt,
                                  Instant finishedAt) {

    public GoalExecutionResult {
        userId = userId == null ? "" : userId.trim();
        userInput = userInput == null ? "" : userInput.trim();
        iteration = Math.max(1, iteration);
        startedAt = startedAt == null ? Instant.now() : startedAt;
        finishedAt = finishedAt == null ? startedAt : finishedAt;
    }

    public boolean success() {
        return finalResult != null && finalResult.success();
    }

    public long durationMs() {
        return Math.max(0L, Duration.between(startedAt, finishedAt).toMillis());
    }

    public List<String> successfulTaskIds() {
        return graphResult == null ? List.of() : graphResult.successfulNodeIds();
    }

    public List<String> failedTargets() {
        return graphResult == null ? List.of() : graphResult.failedTargets();
    }

    public ExecutionTraceDto executionTrace() {
        List<PlanStepDto> steps = graphResult == null || graphResult.nodeResults() == null
                ? List.of()
                : graphResult.nodeResults().stream()
                .map(node -> new PlanStepDto(
                        node.nodeId(),
                        node.status(),
                        node.target(),
                        node.result() == null ? "" : node.result().output(),
                        startedAt,
                        finishedAt
                ))
                .toList();
        return new ExecutionTraceDto(
                "autonomous-goal",
                Math.max(0, iteration - 1),
                new CritiqueReportDto(
                        success(),
                        success()
                                ? "goal execution succeeded"
                                : finalResult == null
                                ? "goal execution failed"
                                : finalResult.output(),
                        success() ? "none" : "replan"
                ),
                steps
        );
    }
}
