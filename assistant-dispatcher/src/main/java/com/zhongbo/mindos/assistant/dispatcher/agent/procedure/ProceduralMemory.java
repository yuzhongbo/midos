package com.zhongbo.mindos.assistant.dispatcher.agent.procedure;

import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ProceduralMemory {

    private final ProcedureMemoryEngine procedureMemoryEngine;

    public ProceduralMemory(ProcedureMemoryEngine procedureMemoryEngine) {
        this.procedureMemoryEngine = procedureMemoryEngine;
    }

    public Procedure recordSuccess(String userId,
                                   String intent,
                                   String trigger,
                                   TaskGraph graph,
                                   Map<String, Object> contextAttributes) {
        if (procedureMemoryEngine == null) {
            return new Procedure("", intent, trigger, extractSteps(graph), 1.0, 1);
        }
        return procedureMemoryEngine.recordSuccessfulGraph(userId, intent, trigger, graph, contextAttributes);
    }

    public Optional<ReusableProcedure> matchReusableProcedure(String userId,
                                                             String userInput,
                                                             String suggestedTarget,
                                                             Map<String, Object> effectiveParams) {
        if (procedureMemoryEngine == null) {
            return Optional.empty();
        }
        return procedureMemoryEngine.matchTemplates(userId, userInput, suggestedTarget, 1).stream()
                .findFirst()
                .map(match -> new ReusableProcedure(
                        toProcedure(match.template()),
                        materializeGraph(match.template(), effectiveParams),
                        match.score(),
                        match.reasons()
                ));
    }

    private TaskGraph materializeGraph(ProcedureTemplate template, Map<String, Object> effectiveParams) {
        if (template == null || template.steps().isEmpty()) {
            return new TaskGraph(List.of(), List.of());
        }
        List<TaskNode> nodes = new ArrayList<>();
        int index = 1;
        for (ProcedureStepTemplate step : template.steps()) {
            List<String> dependsOn = step.dependsOn();
            if ((dependsOn == null || dependsOn.isEmpty()) && !nodes.isEmpty()) {
                dependsOn = List.of(nodes.get(nodes.size() - 1).id());
            }
            Map<String, Object> stepParams = index == 1
                    ? merge(effectiveParams, step.params())
                    : step.params();
            nodes.add(new TaskNode(
                    "procedure-step-" + index,
                    step.target(),
                    stepParams,
                    dependsOn,
                    step.saveAs(),
                    false
            ));
            index++;
        }
        return new TaskGraph(nodes);
    }

    private Procedure toProcedure(ProcedureTemplate template) {
        return new Procedure(
                template.id(),
                template.intent(),
                template.trigger(),
                template.steps().stream().map(ProcedureStepTemplate::target).toList(),
                template.successRate(),
                template.reuseCount()
        );
    }

    private List<String> extractSteps(TaskGraph graph) {
        if (graph == null || graph.isEmpty()) {
            return List.of();
        }
        return graph.nodes().stream().map(TaskNode::target).toList();
    }

    private Map<String, Object> merge(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> merged = new LinkedHashMap<>(base == null ? Map.of() : base);
        if (extra != null) {
            merged.putAll(extra);
        }
        return Map.copyOf(merged);
    }

    public record ReusableProcedure(Procedure procedure,
                                    TaskGraph taskGraph,
                                    double score,
                                    List<String> reasons) {
        public ReusableProcedure {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }
}
