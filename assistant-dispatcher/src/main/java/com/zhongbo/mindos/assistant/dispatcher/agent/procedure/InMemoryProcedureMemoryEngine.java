package com.zhongbo.mindos.assistant.dispatcher.agent.procedure;

import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemoryGateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class InMemoryProcedureMemoryEngine implements ProcedureMemoryEngine {

    private final Map<String, Map<String, ProcedureTemplate>> templatesByUser = new ConcurrentHashMap<>();
    private final DispatcherMemoryFacade dispatcherMemoryFacade;

    public InMemoryProcedureMemoryEngine() {
        this(null);
    }

    public InMemoryProcedureMemoryEngine(GraphMemoryGateway graphMemoryGateway) {
        this.dispatcherMemoryFacade = new DispatcherMemoryFacade((com.zhongbo.mindos.assistant.memory.MemoryGateway) null, graphMemoryGateway, null);
    }

    @Override
    public Procedure recordSuccessfulGraph(String userId,
                                           String intent,
                                           String trigger,
                                           TaskGraph graph,
                                           Map<String, Object> contextAttributes) {
        if (graph == null || graph.isEmpty()) {
            return new Procedure("", safeText(intent), safeText(trigger), List.of(), 0.0, 0);
        }
        String templateId = buildTemplateId(intent, graph);
        Map<String, ProcedureTemplate> userTemplates = templatesByUser.computeIfAbsent(safeUserId(userId), ignored -> new ConcurrentHashMap<>());
        ProcedureTemplate existing = userTemplates.get(templateId);
        List<ProcedureStepTemplate> steps = graph.nodes().stream()
                .map(node -> new ProcedureStepTemplate(node.target(), node.params(), node.dependsOn(), node.saveAs()))
                .toList();
        int reuseCount = existing == null ? 1 : existing.reuseCount() + 1;
        double successRate = existing == null ? 1.0 : Math.min(1.0, (existing.successRate() * existing.reuseCount() + 1.0) / reuseCount);
        ProcedureTemplate template = new ProcedureTemplate(
                templateId,
                safeUserId(userId),
                safeText(intent),
                safeText(trigger),
                steps,
                successRate,
                reuseCount,
                Instant.now(),
                contextAttributes == null ? Map.of() : Map.copyOf(contextAttributes)
        );
        userTemplates.put(templateId, template);
        return toProcedure(template);
    }

    @Override
    public List<Procedure> listProcedures(String userId) {
        return templatesByUser.getOrDefault(safeUserId(userId), Map.of()).values().stream()
                .map(this::toProcedure)
                .sorted((left, right) -> {
                    int successCompare = Double.compare(right.successRate(), left.successRate());
                    if (successCompare != 0) {
                        return successCompare;
                    }
                    int reuseCompare = Integer.compare(right.reuseCount(), left.reuseCount());
                    if (reuseCompare != 0) {
                        return reuseCompare;
                    }
                    return left.id().compareTo(right.id());
                })
                .toList();
    }

    @Override
    public boolean deleteProcedure(String userId, String procedureId) {
        String normalizedUserId = safeUserId(userId);
        String normalizedProcedureId = safeText(procedureId);
        if (normalizedUserId.isBlank() || normalizedProcedureId.isBlank()) {
            return false;
        }
        Map<String, ProcedureTemplate> userTemplates = templatesByUser.get(normalizedUserId);
        if (userTemplates == null) {
            return false;
        }
        ProcedureTemplate removed = userTemplates.remove(normalizedProcedureId);
        if (removed == null) {
            return false;
        }
        return true;
    }

    @Override
    public List<ProcedureMatch> matchTemplates(String userId, String userInput, String suggestedTarget, int limit) {
        String normalizedInput = normalize(userInput);
        List<ProcedureMatch> matches = new ArrayList<>();
        for (ProcedureTemplate template : templatesByUser.getOrDefault(safeUserId(userId), Map.of()).values()) {
            double score = 0.0;
            List<String> reasons = new ArrayList<>();
            if (!safeText(suggestedTarget).isBlank() && template.intent().equalsIgnoreCase(safeText(suggestedTarget))) {
                score += 0.45;
                reasons.add("intent-match");
            }
            if (!template.trigger().isBlank() && normalizedInput.contains(normalize(template.trigger()))) {
                score += 0.30;
                reasons.add("trigger-match");
            }
            if (template.steps().stream().anyMatch(step -> normalizedInput.contains(normalize(step.target())))) {
                score += 0.15;
                reasons.add("step-match");
            }
            score += Math.min(0.10, template.successRate() * 0.10);
            if (score > 0.0) {
                matches.add(new ProcedureMatch(template, Math.round(score * 1000.0) / 1000.0, reasons));
            }
        }
        matches.sort((left, right) -> Double.compare(right.score(), left.score()));
        return matches.stream().limit(Math.max(1, limit)).toList();
    }

    private String buildTemplateId(String intent, TaskGraph graph) {
        String signature = graph.nodes().stream().map(TaskNode::target).reduce((left, right) -> left + "->" + right).orElse("graph");
        return safeText(intent) + ":" + Math.abs(signature.hashCode());
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

    private String normalize(String value) {
        return safeText(value).toLowerCase(Locale.ROOT);
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeUserId(String userId) {
        return userId == null ? "" : userId.trim();
    }
}
