package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.DAGExecutor;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutor;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DefaultExecutorAgent implements ExecutorAgent {

    private final TaskGraphExecutor taskGraphExecutor = new TaskGraphExecutor();

    @Override
    public String name() {
        return "executor-agent";
    }

    @Override
    public AgentRole role() {
        return AgentRole.EXECUTOR;
    }

    @Override
    public AgentResponse execute(AgentMessage message, AgentContext context) {
        Map<String, Object> payload = message == null ? Map.of() : message.payloadMap();
        TaskGraph graph = objectValue(payload.get("graph"), TaskGraph.class);
        if (graph == null || graph.isEmpty()) {
            return AgentResponse.completed(
                    name(),
                    SkillResult.failure(name(), "missing task graph"),
                    false,
                    List.of(),
                    Map.of(),
                    "missing task graph"
            );
        }

        String graphId = firstNonBlank(stringValue(payload.get("graphId")), message.taskId());
        TaskGraphExecutionResult graphResult = taskGraphExecutor.execute(graph, context.mergedSkillContext(), (node, nodeContext) ->
                executeNode(message, context, node, nodeContext, graphId)
        );

        SkillResult finalResult = graphResult.finalResult() == null
                ? SkillResult.failure(name(), "task graph produced no result")
                : graphResult.finalResult();
        boolean usedFallback = graphResult.nodeResults().stream().anyMatch(TaskGraphExecutionResult.NodeResult::usedFallback);

        Map<String, Object> contextPatch = new LinkedHashMap<>();
        contextPatch.put("multiAgent.graph", graph);
        contextPatch.put("multiAgent.graphId", graphId);
        contextPatch.put("multiAgent.graphResult", graphResult);

        List<AgentMessage> outbound = new ArrayList<>();
        Map<String, Object> memoryPayload = new LinkedHashMap<>();
        memoryPayload.put("kind", "procedure");
        memoryPayload.put("graph", graph);
        memoryPayload.put("graphResult", graphResult);
        memoryPayload.put("contextAttributes", graphResult.contextAttributes());
        memoryPayload.put("intent", context.decision() == null ? "" : context.decision().intent());
        memoryPayload.put("trigger", context.userInput());
        memoryPayload.put("userInput", context.userInput());
        memoryPayload.put("result", finalResult);
        memoryPayload.put("graphId", graphId);
        outbound.add(AgentMessage.reply(
                message,
                name(),
                "memory-agent",
                AgentTask.of(AgentTaskType.MEMORY_WRITE, context.userId(), context.userInput(), memoryPayload)
        ));

        return AgentResponse.completed(
                name(),
                finalResult,
                usedFallback,
                outbound,
                contextPatch,
                finalResult.success() ? "graph executed" : "graph failed"
        );
    }

    @Override
    public AgentResponse plan(AgentMessage message, AgentContext context) {
        return AgentResponse.unsupported(name(), "plan");
    }

    @Override
    public AgentResponse observe(AgentMessage message, AgentContext context) {
        return AgentResponse.unsupported(name(), "observe");
    }

    private DAGExecutor.NodeExecution executeNode(AgentMessage parentMessage,
                                                  AgentContext context,
                                                  TaskNode node,
                                                  com.zhongbo.mindos.assistant.common.SkillContext nodeContext,
                                                  String graphId) {
        if (context.gateway() == null) {
            return new DAGExecutor.NodeExecution(
                    SkillResult.failure(node.target(), "multi-agent gateway unavailable"),
                    false
            );
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("skillName", node.target());
        payload.put("params", nodeContext.attributes());
        payload.put("nodeId", node.id());
        payload.put("saveAs", node.saveAs());
        payload.put("graphId", graphId);
        payload.put("usedFallback", false);
        AgentMessage toolMessage = AgentMessage.reply(
                parentMessage,
                name(),
                "tool-agent",
                AgentTask.of(AgentTaskType.TOOL_CALL, context.userId(), context.userInput(), payload)
        );
        AgentResponse toolResponse = context.gateway().send(toolMessage);
        if (toolResponse != null && context.gateway() != null && !toolResponse.outboundMessages().isEmpty()) {
            for (AgentMessage outbound : toolResponse.outboundMessages()) {
                context.gateway().send(outbound);
            }
        }
        SkillResult result = toolResponse == null || toolResponse.result() == null
                ? SkillResult.failure(node.target(), "tool agent returned no result")
                : toolResponse.result();
        return new DAGExecutor.NodeExecution(result, toolResponse != null && toolResponse.usedFallback());
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private <T> T objectValue(Object value, Class<T> type) {
        if (type == null || value == null || !type.isInstance(value)) {
            return null;
        }
        return type.cast(value);
    }
}
