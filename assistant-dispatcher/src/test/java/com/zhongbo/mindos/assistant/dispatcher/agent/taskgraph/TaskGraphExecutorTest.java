package com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskGraphExecutorTest {

    @Test
    void shouldExecuteDagInDependencyOrder() {
        TaskGraph graph = new TaskGraph(
                List.of(
                        new TaskNode("fetch", "student.get", Map.of(), List.of(), "student", false),
                        new TaskNode("analyze", "student.analyze", Map.of("source", "${task.student.output}"), List.of("fetch"), "analysis", false),
                        new TaskNode("plan", "teaching.plan", Map.of("input", "${task.analysis.output}"), List.of("analyze"), "plan", false)
                ),
                List.of(
                        new TaskEdge("fetch", "analyze"),
                        new TaskEdge("analyze", "plan")
                )
        );

        TaskGraphExecutionResult result = new TaskGraphExecutor().execute(
                graph,
                new SkillContext("u1", "plan", Map.of()),
                (node, context) -> new TaskGraphExecutor.NodeExecution(
                        SkillResult.success(node.target(), node.target() + ":" + context.attributes().getOrDefault("source", context.attributes().getOrDefault("input", "ok"))),
                        false
                )
        );

        assertTrue(result.success());
        assertEquals(List.of("fetch", "analyze", "plan"), result.executionOrder());
        assertEquals("teaching.plan", result.finalResult().skillName());
    }

    @Test
    void shouldRejectCycle() {
        TaskGraph graph = new TaskGraph(
                List.of(
                        new TaskNode("a", "step.a", Map.of(), List.of("b"), "", false),
                        new TaskNode("b", "step.b", Map.of(), List.of("a"), "", false)
                ),
                List.of(
                        new TaskEdge("a", "b"),
                        new TaskEdge("b", "a")
                )
        );
        assertThrows(IllegalArgumentException.class,
                () -> new TaskGraphExecutor().execute(graph, new SkillContext("u1", "cycle", Map.of()), (node, context) -> null));
    }

    @Test
    void shouldRetryNodeUntilSuccessWithinConfiguredAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        TaskGraph graph = new TaskGraph(List.of(
                new TaskNode("retry", "code.generate", Map.of("task", "controller"), List.of(), "result", false, 2)
        ));

        TaskGraphExecutionResult result = new TaskGraphExecutor().execute(
                graph,
                new SkillContext("u1", "retry", Map.of()),
                (node, context) -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt == 1) {
                        return new TaskGraphExecutor.NodeExecution(SkillResult.failure(node.target(), "temporary failure"), false);
                    }
                    return new TaskGraphExecutor.NodeExecution(SkillResult.success(node.target(), "recovered"), false);
                }
        );

        assertTrue(result.success());
        assertEquals(2, result.nodeResults().get(0).attempts());
        assertEquals(2, attempts.get());
    }

    @Test
    void shouldContinueWhenOptionalDependencyFails() {
        TaskGraph graph = new TaskGraph(
                List.of(
                        new TaskNode("optional-search", "file.search", Map.of("query", "missing"), List.of(), "search", true, 1),
                        new TaskNode("generate", "code.generate", Map.of("task", "controller"), List.of("optional-search"), "result", false, 1)
                ),
                List.of(new TaskEdge("optional-search", "generate"))
        );

        TaskGraphExecutionResult result = new TaskGraphExecutor().execute(
                graph,
                new SkillContext("u1", "optional", Map.of()),
                (node, context) -> {
                    if ("optional-search".equals(node.id())) {
                        return new TaskGraphExecutor.NodeExecution(SkillResult.failure(node.target(), "not found"), false);
                    }
                    return new TaskGraphExecutor.NodeExecution(SkillResult.success(node.target(), "generated"), false);
                }
        );

        assertEquals(List.of("optional-search", "generate"), result.executionOrder());
        assertEquals("code.generate", result.finalResult().skillName());
        assertTrue(result.successfulNodeIds().contains("generate"));
    }
}
