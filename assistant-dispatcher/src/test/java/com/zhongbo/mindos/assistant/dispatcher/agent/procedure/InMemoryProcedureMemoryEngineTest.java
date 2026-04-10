package com.zhongbo.mindos.assistant.dispatcher.agent.procedure;

import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class InMemoryProcedureMemoryEngineTest {

    @Test
    void shouldLearnAndMatchProcedureTemplate() {
        InMemoryProcedureMemoryEngine engine = new InMemoryProcedureMemoryEngine();
        TaskGraph graph = new TaskGraph(List.of(
                new TaskNode("fetch", "student.get", Map.of(), List.of(), "student", false),
                new TaskNode("plan", "teaching.plan", Map.of(), List.of("fetch"), "plan", false)
        ));

        engine.recordSuccessfulGraph("u1", "student.plan", "为学生生成计划", graph, Map.of("channel", "teacher"));
        List<ProcedureMatch> matches = engine.matchTemplates("u1", "请为学生生成计划", "student.plan", 3);

        assertFalse(matches.isEmpty());
        assertEquals("student.plan", matches.get(0).template().intent());
    }
}
