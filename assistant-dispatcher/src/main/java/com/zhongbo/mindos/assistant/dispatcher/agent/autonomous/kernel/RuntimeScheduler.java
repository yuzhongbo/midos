package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RuntimeScheduler {

    private final CognitivePluginRegistry pluginRegistry;
    private final AGIMemory memory;
    private final RuntimeOptimizer optimizer;

    public RuntimeScheduler(CognitivePluginRegistry pluginRegistry,
                            AGIMemory memory,
                            RuntimeOptimizer optimizer) {
        this.pluginRegistry = pluginRegistry;
        this.memory = memory;
        this.optimizer = optimizer;
    }

    public ExecutionPlan schedule(Task task) {
        return schedule(task, null);
    }

    public ExecutionPlan schedule(Task task,
                                  RuntimeState currentState) {
        Task safeTask = task == null ? Task.fromGoal(null, ExecutionPolicy.AUTONOMOUS, Map.of()) : task;
        RuntimeState safeState = currentState == null ? RuntimeState.initial(safeTask) : currentState;
        CognitiveModule modules = pluginRegistry == null ? new CognitiveModule(null, null, null, null, null) : pluginRegistry.moduleSet();
        LinkedHashMap<String, Object> attributes = new LinkedHashMap<>(safeTask.metadata());
        attributes.putAll(safeState.context().attributes());
        if (optimizer != null) {
            attributes.putAll(optimizer.hints(safeState.handle()));
        }
        TaskGraph plannedGraph = safeTask.graph();
        Map<CognitiveCapability, String> assignedPlugins = new EnumMap<>(CognitiveCapability.class);
        for (CognitivePlugin plugin : modules.activePlugins()) {
            if (plugin == null) {
                continue;
            }
            CognitivePluginOutput output = plugin.run(new CognitivePluginContext(safeTask, safeState, memory, attributes));
            attributes.putAll(output.attributes());
            assignedPlugins.put(plugin.capability(), plugin.pluginId());
            if ((plannedGraph == null || plannedGraph.isEmpty())
                    && output.proposedGraph() != null
                    && !output.proposedGraph().isEmpty()) {
                plannedGraph = output.proposedGraph();
            }
        }
        if (plannedGraph == null) {
            plannedGraph = new TaskGraph(List.of(), List.of());
        }
        Node executionNode = safeState.context().assignedNode() == null ? Node.local() : safeState.context().assignedNode();
        Map<RuntimeResourceType, Double> resourceAllocation = resourceAllocation(safeTask.policy(), plannedGraph, attributes);
        List<RuntimeObject> runtimeObjects = runtimeObjects(safeTask, modules, executionNode, resourceAllocation, attributes);
        String summary = "scheduled policy=" + safeTask.policy().name().toLowerCase(java.util.Locale.ROOT)
                + ",plugins=" + assignedPlugins.values()
                + ",node=" + executionNode.nodeId();
        return new ExecutionPlan(
                safeTask,
                plannedGraph,
                safeTask.policy(),
                modules,
                assignedPlugins,
                runtimeObjects,
                resourceAllocation,
                executionNode,
                attributes,
                summary,
                Instant.now()
        );
    }

    private Map<RuntimeResourceType, Double> resourceAllocation(ExecutionPolicy policy,
                                                                TaskGraph graph,
                                                                Map<String, Object> attributes) {
        int nodeCount = graph == null || graph.isEmpty() ? 1 : Math.max(1, graph.nodes().size());
        double computeBoost = numberAttribute(attributes, "optimizer.computeBoost", 1.0);
        double memoryBoost = numberAttribute(attributes, "optimizer.memoryBoost", 1.0);
        Map<RuntimeResourceType, Double> allocation = new EnumMap<>(RuntimeResourceType.class);
        allocation.put(RuntimeResourceType.COMPUTE, baseCompute(policy, nodeCount) * computeBoost);
        allocation.put(RuntimeResourceType.MEMORY, (nodeCount * 8.0) * memoryBoost);
        allocation.put(RuntimeResourceType.TOOL_USAGE, Math.max(4.0, nodeCount * 3.0));
        allocation.put(RuntimeResourceType.AGENT_TIME, baseAgentTime(policy, nodeCount));
        allocation.put(RuntimeResourceType.TASK_PRIORITY, 4.0 + nodeCount + numberAttribute(attributes, "goal.priority", 0.0) * 8.0);
        return Map.copyOf(allocation);
    }

    private List<RuntimeObject> runtimeObjects(Task task,
                                               CognitiveModule modules,
                                               Node executionNode,
                                               Map<RuntimeResourceType, Double> resourceAllocation,
                                               Map<String, Object> attributes) {
        List<RuntimeObject> objects = new ArrayList<>();
        objects.add(new RuntimeObject(
                task.taskId(),
                RuntimeObjectType.TASK,
                "task",
                Map.of(
                        "goalId", task.goal().goalId(),
                        "policy", task.policy().name(),
                        "resourceAllocation", resourceAllocation
                )
        ));
        if (modules != null) {
            modules.activePlugins().forEach(plugin -> {
                if (plugin != null && plugin.runtimeObject() != null) {
                    objects.add(plugin.runtimeObject());
                }
            });
        }
        objects.add(new RuntimeObject(
                "rule:interruptible",
                RuntimeObjectType.RULE,
                "interruptible",
                Map.of("suspendable", true, "resumable", true, "migratable", true)
        ));
        objects.add(new RuntimeObject(
                "memory:" + task.taskId(),
                RuntimeObjectType.MEMORY_SEGMENT,
                "memory",
                Map.of("namespace", "task:" + task.taskId())
        ));
        objects.add(new RuntimeObject(
                executionNode.nodeId(),
                RuntimeObjectType.EXECUTION_NODE,
                "execution-node",
                executionNode.metadata()
        ));
        if (attributes != null && attributes.containsKey("tool.targets")) {
            objects.add(new RuntimeObject(
                    "tool:" + task.taskId(),
                    RuntimeObjectType.TOOL,
                    "tool-use",
                    Map.of("targets", attributes.get("tool.targets"))
            ));
        }
        return List.copyOf(objects);
    }

    private double baseCompute(ExecutionPolicy policy, int nodeCount) {
        return switch (policy == null ? ExecutionPolicy.AUTONOMOUS : policy) {
            case REALTIME -> nodeCount * 14.0;
            case BATCH -> nodeCount * 10.0;
            case SPECULATIVE -> nodeCount * 16.0;
            case LONG_RUNNING -> nodeCount * 8.0;
            case AUTONOMOUS -> nodeCount * 12.0;
        };
    }

    private double baseAgentTime(ExecutionPolicy policy, int nodeCount) {
        return switch (policy == null ? ExecutionPolicy.AUTONOMOUS : policy) {
            case REALTIME -> nodeCount * 6.0;
            case BATCH -> nodeCount * 8.0;
            case SPECULATIVE -> nodeCount * 10.0;
            case LONG_RUNNING -> nodeCount * 14.0;
            case AUTONOMOUS -> nodeCount * 12.0;
        };
    }

    private double numberAttribute(Map<String, Object> attributes, String key, double fallback) {
        if (attributes == null || key == null || key.isBlank()) {
            return fallback;
        }
        Object value = attributes.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ignore) {
            return fallback;
        }
    }
}
