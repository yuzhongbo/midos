package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSchedulerPluginTest {

    @Test
    void shouldScheduleUsingPluggablePlanningModule() {
        CognitivePlugin planningPlugin = new CognitivePlugin() {
            @Override
            public String pluginId() {
                return "planning.test";
            }

            @Override
            public CognitiveCapability capability() {
                return CognitiveCapability.PLANNING;
            }

            @Override
            public RuntimeObject runtimeObject() {
                return new RuntimeObject("plugin.planning.test", RuntimeObjectType.COGNITIVE_PLUGIN, "planning", Map.of("test", true));
            }

            @Override
            public CognitivePluginOutput run(CognitivePluginContext context) {
                return new CognitivePluginOutput(
                        new TaskGraph(List.of(
                                new TaskNode("task-1", "todo.create", Map.of("title", "ship"), List.of(), "result", false, 1)
                        )),
                        Map.of("planning.test.used", true),
                        0.9,
                        "test graph"
                );
            }

            @Override
            public int priority() {
                return 10;
            }
        };
        CognitivePluginRegistry registry = new CognitivePluginRegistry(List.of(planningPlugin, new ReasoningCognitivePlugin(), new MemoryCognitivePlugin()));
        RuntimeScheduler scheduler = new RuntimeScheduler(registry, new AGIMemory(), new RuntimeOptimizer());

        ExecutionPlan plan = scheduler.schedule(Task.fromGoal(Goal.of("创建待办", 0.7), ExecutionPolicy.AUTONOMOUS, Map.of("userId", "u-test")));

        assertTrue(plan.executable());
        assertEquals("planning.test", plan.assignedPlugins().get(CognitiveCapability.PLANNING));
        assertTrue((Boolean) plan.attributes().get("planning.test.used"));
        assertNotNull(plan.runtimeObjects());
        assertTrue(plan.runtimeObjects().stream().anyMatch(object -> "plugin.planning.test".equals(object.objectId())));
    }

    @Test
    void shouldAllowDynamicPluginReplacement() {
        CognitivePlugin lowPriority = new FixedPlanningPlugin("planning.low", 1, "todo.create");
        CognitivePlugin highPriority = new FixedPlanningPlugin("planning.high", 8, "code.generate");
        CognitivePluginRegistry registry = new CognitivePluginRegistry(List.of(lowPriority, highPriority));

        assertEquals("planning.high", registry.select(CognitiveCapability.PLANNING).pluginId());

        registry.replace(new FixedPlanningPlugin("planning.replaced", 2, "news.search"));

        assertEquals("planning.replaced", registry.select(CognitiveCapability.PLANNING).pluginId());
        assertEquals(1, registry.plugins(CognitiveCapability.PLANNING).size());
    }

    private static final class FixedPlanningPlugin implements CognitivePlugin {
        private final String pluginId;
        private final int priority;
        private final String target;

        private FixedPlanningPlugin(String pluginId, int priority, String target) {
            this.pluginId = pluginId;
            this.priority = priority;
            this.target = target;
        }

        @Override
        public String pluginId() {
            return pluginId;
        }

        @Override
        public CognitiveCapability capability() {
            return CognitiveCapability.PLANNING;
        }

        @Override
        public RuntimeObject runtimeObject() {
            return new RuntimeObject("plugin." + pluginId, RuntimeObjectType.COGNITIVE_PLUGIN, "planning", Map.of("target", target));
        }

        @Override
        public CognitivePluginOutput run(CognitivePluginContext context) {
            return new CognitivePluginOutput(
                    new TaskGraph(List.of(
                            new TaskNode("task-1", target, Map.of(), List.of(), "result", false, 1)
                    )),
                    Map.of("pluginId", pluginId),
                    0.8,
                    pluginId
            );
        }

        @Override
        public int priority() {
            return priority;
        }
    }
}
