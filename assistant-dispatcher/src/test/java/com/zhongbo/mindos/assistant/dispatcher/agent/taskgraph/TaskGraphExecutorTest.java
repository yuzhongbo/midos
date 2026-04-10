package com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskGraphExecutorTest {

    @Test
    void shouldExecuteDagInDependencyOrder() {
        TaskGraph graph = new TaskGraph(List.of(
                new TaskNode("fetch", "student.get", Map.of(), List.of(), "student", false),
                new TaskNode("analyze", "student.analyze", Map.of("source", "${task.student.output}"), List.of("fetch"), "analysis", false),
                new TaskNode("plan", "teaching.plan", Map.of("input", "${task.analysis.output}"), List.of("analyze"), "plan", false)
        ));

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
        TaskGraph graph = new TaskGraph(List.of(
                new TaskNode("a", "step.a", Map.of(), List.of("b"), "", false),
                new TaskNode("b", "step.b", Map.of(), List.of("a"), "", false)
        ));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskGraphExecutor().execute(graph, new SkillContext("u1", "cycle", Map.of()), (node, context) -> null));
    }
}
