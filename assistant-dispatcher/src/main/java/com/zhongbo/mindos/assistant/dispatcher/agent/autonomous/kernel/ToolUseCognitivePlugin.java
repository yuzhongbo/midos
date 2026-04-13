package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousGraphExecutor;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
public class ToolUseCognitivePlugin implements CognitivePlugin {

    private final AutonomousGraphExecutor graphExecutor;

    public ToolUseCognitivePlugin(AutonomousGraphExecutor graphExecutor) {
        this.graphExecutor = graphExecutor;
    }

    @Override
    public String pluginId() {
        return "tool.graph-executor";
    }

    @Override
    public CognitiveCapability capability() {
        return CognitiveCapability.TOOL_USE;
    }

    @Override
    public RuntimeObject runtimeObject() {
        return new RuntimeObject(
                "plugin.tool.graph-executor",
                RuntimeObjectType.COGNITIVE_PLUGIN,
                "tool-use",
                Map.of("executor", "graph-executor")
        );
    }

    @Override
    public CognitivePluginOutput run(CognitivePluginContext context) {
        Task task = context == null ? null : context.task();
        RuntimeState state = context == null ? null : context.runtimeState();
        TaskGraph graph = task == null ? null : task.graph();
        if ((graph == null || graph.isEmpty()) && state != null && state.plan() != null) {
            graph = state.plan().graph();
        }
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        if (graph != null && graph.nodes() != null) {
            for (TaskNode node : graph.nodes()) {
                if (node != null && node.target() != null && !node.target().isBlank()) {
                    targets.add(node.target());
                }
            }
        }
        LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("tool.executionAvailable", graphExecutor != null);
        attributes.put("tool.targets", targets.isEmpty() ? List.of() : List.copyOf(targets));
        return new CognitivePluginOutput(
                null,
                attributes,
                graphExecutor == null ? 0.0 : 0.75,
                "tool targets=" + targets.size()
        );
    }

    @Override
    public int priority() {
        return 5;
    }
}
