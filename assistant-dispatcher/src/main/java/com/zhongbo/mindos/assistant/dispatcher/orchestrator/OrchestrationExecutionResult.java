package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.PlanStepDto;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.ReflectionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record OrchestrationExecutionResult(TaskGraphPlan plan,
                                           DecisionOrchestrator.OrchestrationRequest request,
                                           TaskGraph graph,
                                           DecisionOrchestrator.OrchestrationOutcome outcome,
                                           ReflectionResult reflection) {

    private static final FailureNormalizer FAILURE_NORMALIZER = new FailureNormalizer();

    public OrchestrationExecutionResult {
        request = request == null
                ? new DecisionOrchestrator.OrchestrationRequest("", "", null, Map.of())
                : request;
        graph = graph == null ? new TaskGraph(List.of(), List.of()) : graph;
        reflection = reflection == null
                ? new ReflectionResult(true, "", "", "none", Map.of(), List.of(), false, false, Instant.now(), com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch.empty())
                : reflection;
    }

    public OrchestrationExecutionResult withReflection(ReflectionResult reflection) {
        return new OrchestrationExecutionResult(plan, request, graph, outcome, reflection);
    }

    public Decision decision() {
        return plan == null ? null : plan.decision();
    }

    public SkillResult response() {
        if (outcome == null) {
            return SkillResult.failure("decision.orchestrator", "missing execution outcome");
        }
        return outcome.hasResult() ? outcome.result() : outcome.clarification();
    }

    public SkillResult recordableResult() {
        return outcome == null ? null : outcome.result();
    }

    public ExecutionTraceDto trace() {
        return outcome == null ? null : outcome.trace();
    }

    public String userId() {
        return request == null ? "" : request.userId();
    }

    public String userInput() {
        return request == null ? "" : request.userInput();
    }

    public Map<String, Object> params() {
        Decision decision = decision();
        return decision == null || decision.params() == null ? Map.of() : decision.params();
    }

    public OrchestrationExecutionResult normalizeFailures() {
        if (outcome == null || outcome.hasClarification()) {
            return this;
        }
        if (outcome.hasResult() && outcome.result() != null && outcome.result().success()) {
            return this;
        }
        SkillResult normalizedFailure = FAILURE_NORMALIZER.unifiedFailureResult(
                userInput(),
                decision() == null ? "" : decision().intent(),
                attemptedCandidates(),
                outcome.hasResult() && outcome.result() != null
                        ? outcome.result()
                        : SkillResult.failure("decision.orchestrator", "all candidates failed")
        );
        DecisionOrchestrator.OrchestrationOutcome normalizedOutcome = new DecisionOrchestrator.OrchestrationOutcome(
                normalizedFailure,
                outcome.skillDsl(),
                outcome.clarification(),
                outcome.trace(),
                outcome.selectedSkill(),
                outcome.usedFallback()
        );
        return new OrchestrationExecutionResult(plan, request, graph, normalizedOutcome, reflection);
    }

    private List<String> attemptedCandidates() {
        if (trace() == null || trace().steps() == null) {
            return List.of();
        }
        return trace().steps().stream()
                .map(PlanStepDto::channel)
                .filter(channel -> channel != null && !channel.isBlank())
                .distinct()
                .toList();
    }
}
