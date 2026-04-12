package com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph;

import com.zhongbo.mindos.assistant.common.SkillContext;

import java.util.List;
import java.util.Map;

public class StructuredExecutionRuntime {

    private final DAGExecutor dagExecutor;

    public StructuredExecutionRuntime() {
        this(new DAGExecutor());
    }

    public StructuredExecutionRuntime(DAGExecutor dagExecutor) {
        this.dagExecutor = dagExecutor == null ? new DAGExecutor() : dagExecutor;
    }

    public TaskGraphExecutionResult execute(TaskGraph graph,
                                            SkillContext baseContext,
                                            DAGExecutor.TaskNodeRunner runner) {
        return dagExecutor.execute(graph, baseContext, runner);
    }

    public TaskGraphExecutionResult executeSingle(String nodeId,
                                                  String target,
                                                  Map<String, Object> params,
                                                  String saveAs,
                                                  SkillContext baseContext,
                                                  DAGExecutor.TaskNodeRunner runner) {
        TaskNode node = new TaskNode(
                nodeId,
                target,
                params == null ? Map.of() : params,
                List.of(),
                saveAs,
                false
        );
        return execute(new TaskGraph(List.of(node)), baseContext, runner);
    }
}
