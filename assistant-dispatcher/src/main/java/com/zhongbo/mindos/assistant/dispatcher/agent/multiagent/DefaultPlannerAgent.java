package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent;

import com.zhongbo.mindos.assistant.dispatcher.agent.runtime.System2Planner;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class DefaultPlannerAgent implements PlannerAgent {

    private final System2Planner system2Planner;

    @Autowired
    public DefaultPlannerAgent(System2Planner system2Planner) {
        this.system2Planner = system2Planner;
    }

    @Override
    public String name() {
        return "planner-agent";
    }

    @Override
    public AgentRole role() {
        return AgentRole.PLANNER;
    }

    @Override
    public boolean supports(AgentTaskType type) {
        return type == AgentTaskType.PLAN_REQUEST;
    }

    @Override
    public AgentResponse handle(AgentMessage message, AgentContext context) {
        Decision baseDecision = context == null ? null : context.decision();
        if (baseDecision == null) {
            return AgentResponse.completed(
                    name(),
                    com.zhongbo.mindos.assistant.common.SkillResult.failure(name(), "missing decision"),
                    false,
                    List.of(),
                    Map.of(),
                    "missing decision"
            );
        }

        Map<String, Object> mergedParams = new LinkedHashMap<>();
        if (baseDecision.params() != null) {
            mergedParams.putAll(baseDecision.params());
        }
        context.memorySnapshot().ifPresent(snapshot -> mergedParams.putAll(snapshot.inferredFacts()));
        Decision planningDecision = new Decision(
                firstNonBlank(baseDecision.intent(), baseDecision.target()),
                firstNonBlank(baseDecision.target(), baseDecision.intent()),
                mergedParams,
                baseDecision.confidence(),
                baseDecision.requireClarify()
        );

        System2Planner.PlanResult planResult = system2Planner == null
                ? new System2Planner.PlanResult(new TaskGraph(List.of(), List.of()), null, "system2-unavailable")
                : system2Planner.plan(new com.zhongbo.mindos.assistant.dispatcher.agent.runtime.AgentDispatchRequest(
                planningDecision,
                context == null ? null : context.mergedRequest()
        ));

        TaskGraph graph = planResult.graph();
        String target = firstNonBlank(planningDecision.target(), planningDecision.intent());
        boolean fallbackGraph = false;
        if (graph == null || graph.isEmpty()) {
            fallbackGraph = true;
            graph = target.isBlank() ? new TaskGraph(List.of(), List.of()) : TaskGraph.linear(List.of(target), mergedParams);
        }
        String safeRationale = firstNonBlank(planResult.rationale(), fallbackGraph ? "fallback linear plan" : "plan ready");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("graph", graph);
        payload.put("decision", planningDecision);
        payload.put("rationale", safeRationale);
        payload.put("fallbackGraph", fallbackGraph);
        if (planResult.selectedCandidate() != null) {
            payload.put("selectedCandidate", planResult.selectedCandidate());
        }
        AgentMessage executeMessage = AgentMessage.reply(
                message,
                name(),
                "executor-agent",
                AgentTask.of(AgentTaskType.EXECUTE_GRAPH, context.userId(), context.userInput(), payload),
                Map.of("planner.rationale", safeRationale)
        );

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("multiAgent.plan.graph", graph);
        patch.put("multiAgent.plan.rationale", safeRationale);
        patch.put("multiAgent.plan.fallbackGraph", fallbackGraph);
        patch.put("multiAgent.plan.target", target);

        return AgentResponse.progress(
                name(),
                safeRationale,
                List.of(executeMessage),
                patch
        );
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
}
