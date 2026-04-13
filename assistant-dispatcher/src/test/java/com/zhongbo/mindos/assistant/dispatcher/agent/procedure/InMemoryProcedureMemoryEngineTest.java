package com.zhongbo.mindos.assistant.dispatcher.agent.procedure;

import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void shouldExposeProcedureForWeatherNotifyFlow() {
        InMemoryProcedureMemoryEngine engine = new InMemoryProcedureMemoryEngine();
        TaskGraph graph = new TaskGraph(List.of(
                new TaskNode("query", "query_weather", Map.of(), List.of(), "weather", false),
                new TaskNode("notify", "send_dingtalk", Map.of(), List.of("query"), "notify", false)
        ));

        Procedure procedure = engine.recordSuccessfulGraph("u1", "weather.notify", "查天气并发钉钉", graph, Map.of());

        assertEquals(List.of("query_weather", "send_dingtalk"), procedure.steps());
        assertTrue(procedure.successRate() > 0.9);
    }

    @Test
    void shouldDeleteProcedureWithoutGraphSideEffects() {
        GraphMemory graphMemory = new GraphMemory();
        InMemoryProcedureMemoryEngine engine = new InMemoryProcedureMemoryEngine(graphMemory);
        TaskGraph graph = new TaskGraph(List.of(
                new TaskNode("query", "query_weather", Map.of(), List.of(), "weather", false),
                new TaskNode("notify", "send_dingtalk", Map.of(), List.of("query"), "notify", false)
        ));

        Procedure procedure = engine.recordSuccessfulGraph("u1", "weather.notify", "查天气并发钉钉", graph, Map.of());

        assertFalse(engine.listProcedures("u1").isEmpty());
        assertTrue(graphMemory.searchNodes("u1", "procedure", 5).isEmpty());
        assertTrue(engine.deleteProcedure("u1", procedure.id()));
        assertTrue(engine.listProcedures("u1").isEmpty());
        assertTrue(graphMemory.searchNodes("u1", "procedure", 5).isEmpty());
    }
}
