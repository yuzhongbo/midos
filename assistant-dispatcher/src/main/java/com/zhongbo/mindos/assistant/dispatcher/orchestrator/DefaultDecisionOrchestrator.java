package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.PlanStepDto;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.skill.SkillEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class DefaultDecisionOrchestrator implements DecisionOrchestrator {
    private static final List<String> DEFAULT_PARALLEL_MCP_PRIORITY_ORDER = List.of(
            "mcp.serper.websearch",
            "mcp.serpapi.websearch",
            "mcp.bravesearch.websearch",
            "mcp.brave.websearch",
            "mcp.qwensearch.websearch",
            "mcp.qwen.websearch"
    );

    private final CandidatePlanner candidatePlanner;
    private final ParamValidator paramValidator;
    private final ConversationLoop conversationLoop;
    private final FallbackPlan fallbackPlan;
    private final SkillEngine skillEngine;
    private final PostExecutionMemoryRecorder memoryRecorder;
    private final boolean mcpParallelEnabled;
    private final long mcpPerSkillTimeoutMs;
    private final List<String> mcpPriorityOrder;

    public DefaultDecisionOrchestrator(CandidatePlanner candidatePlanner,
                                       ParamValidator paramValidator,
                                       ConversationLoop conversationLoop,
                                       FallbackPlan fallbackPlan,
                                       SkillEngine skillEngine,
                                       PostExecutionMemoryRecorder memoryRecorder,
                                       @Value("${mindos.dispatcher.parallel-routing.enabled:false}") boolean mcpParallelEnabled,
                                       @Value("${mindos.dispatcher.parallel-routing.per-skill-timeout-ms:2500}") long mcpPerSkillTimeoutMs) {
        this.candidatePlanner = candidatePlanner;
        this.paramValidator = paramValidator;
        this.conversationLoop = conversationLoop;
        this.fallbackPlan = fallbackPlan;
        this.skillEngine = skillEngine;
        this.memoryRecorder = memoryRecorder;
        this.mcpParallelEnabled = mcpParallelEnabled;
        this.mcpPerSkillTimeoutMs = Math.max(250, mcpPerSkillTimeoutMs);
        this.mcpPriorityOrder = DEFAULT_PARALLEL_MCP_PRIORITY_ORDER;
    }

    @Override
    public OrchestrationOutcome orchestrate(Decision decision, OrchestrationRequest request) {
        if (decision == null || decision.target() == null || decision.target().isBlank()) {
            return new OrchestrationOutcome(null, null, conversationLoop.requestClarification("", "missing target"), null, null, false);
        }
        Map<String, Object> params = decision.params() == null ? Map.of() : decision.params();
        List<String> candidates = new ArrayList<>(candidatePlanner.plan(decision.target()));
        if (candidates.isEmpty()) {
            candidates.add(decision.target());
        }
        candidates.addAll(fallbackPlan.fallbacks(decision.target()));

        List<String> validated = new ArrayList<>();
        String lastFailure = null;
        for (String candidate : candidates) {
            ParamValidator.ValidationResult namespaceValidation = validateNamespace(candidate);
            if (!namespaceValidation.valid()) {
                lastFailure = namespaceValidation.message();
                continue;
            }
            ParamValidator.ValidationResult validation = paramValidator.validate(candidate, params);
            if (!validation.valid()) {
                lastFailure = validation.message();
                continue;
            }
            validated.add(candidate);
        }
        if (decision.requiresClarify()) {
            return new OrchestrationOutcome(
                    null,
                    null,
                    conversationLoop.requestClarification(decision.target(), lastFailure),
                    null,
                    null,
                    false
            );
        }
        if (validated.isEmpty()) {
            return new OrchestrationOutcome(
                    null,
                    null,
                    conversationLoop.requestClarification(decision.target(), lastFailure),
                    null,
                    null,
                    false
            );
        }

        if (shouldRunMcpParallel(validated)) {
            return orchestrateMcpInParallel(validated, params, request.skillContext());
        }
        return orchestrateSequential(validated, params, request.skillContext());
    }

    @Override
    public void recordOutcome(String userId, String userInput, SkillResult result, ExecutionTraceDto trace) {
        memoryRecorder.record(userId, userInput, result, trace);
    }

    private OrchestrationOutcome orchestrateSequential(List<String> candidates,
                                                       Map<String, Object> params,
                                                       SkillContext skillContext) {
        List<PlanStepDto> steps = new ArrayList<>();
        String lastFailure = null;
        for (int i = 0; i < candidates.size(); i++) {
            String candidate = candidates.get(i);
            Instant start = Instant.now();
            SkillResult result = executeWithTimeout(candidate, params, skillContext);
            Instant end = Instant.now();
            if (result.success()) {
                steps.add(new PlanStepDto(stepName(i), "success", candidate, "executed", start, end));
                ExecutionTraceDto trace = new ExecutionTraceDto(
                        "decision-orchestrator",
                        i,
                        new CritiqueReportDto(true, "success", "none"),
                        steps
                );
                return new OrchestrationOutcome(result, new SkillDsl(candidate, params), null, trace, candidate, i > 0);
            }
            lastFailure = result.output();
            steps.add(new PlanStepDto(stepName(i), "failed", candidate, lastFailure, start, end));
        }
        ExecutionTraceDto trace = new ExecutionTraceDto(
                "decision-orchestrator",
                Math.max(0, steps.size() - 1),
                new CritiqueReportDto(false, lastFailure == null ? "unknown" : lastFailure, "clarify"),
                steps
        );
        return new OrchestrationOutcome(null, null,
                conversationLoop.requestClarification(candidates.get(0), lastFailure),
                trace,
                null,
                candidates.size() > 1);
    }

    private OrchestrationOutcome orchestrateMcpInParallel(List<String> candidates,
                                                          Map<String, Object> params,
                                                          SkillContext skillContext) {
        List<CompletableFuture<SkillResult>> futures = new ArrayList<>();
        for (String candidate : candidates) {
            CompletableFuture<SkillResult> execution = skillEngine.executeDslAsync(new SkillDsl(candidate, params), skillContext)
                    .completeOnTimeout(SkillResult.failure(candidate, "timeout"), mcpPerSkillTimeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(error -> SkillResult.failure(candidate, String.valueOf(error.getMessage())));
            futures.add(execution);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<PlanStepDto> steps = new ArrayList<>();
        List<SkillResult> successes = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            SkillResult result = futures.get(i).join();
            String candidate = candidates.get(i);
            boolean success = result.success();
            steps.add(new PlanStepDto(
                    stepName(i),
                    success ? "success" : "failed",
                    candidate,
                    result.output(),
                    Instant.now().minusMillis(1),
                    Instant.now()
            ));
            if (success) {
                successes.add(result);
            }
        }
        if (successes.isEmpty()) {
            ExecutionTraceDto trace = new ExecutionTraceDto(
                    "decision-orchestrator",
                    Math.max(0, steps.size() - 1),
                    new CritiqueReportDto(false, "all mcp candidates failed", "clarify"),
                    steps
            );
            return new OrchestrationOutcome(
                    null,
                    null,
                    conversationLoop.requestClarification(candidates.get(0), "MCP 调用失败或超时"),
                    trace,
                    null,
                    true
            );
        }
        SkillResult selected = successes.stream()
                .sorted((left, right) -> Integer.compare(priorityRank(left.skillName()), priorityRank(right.skillName())))
                .findFirst()
                .orElse(successes.get(0));
        ExecutionTraceDto trace = new ExecutionTraceDto(
                "decision-orchestrator",
                successes.size() > 1 ? 1 : 0,
                new CritiqueReportDto(true, "parallel mcp success", "none"),
                steps
        );
        return new OrchestrationOutcome(selected, new SkillDsl(selected.skillName(), params), null, trace, selected.skillName(), true);
    }

    private boolean shouldRunMcpParallel(List<String> candidates) {
        if (!mcpParallelEnabled) {
            return false;
        }
        if (candidates == null || candidates.size() < 2) {
            return false;
        }
        return candidates.stream().allMatch(this::isMcpSkill);
    }

    private boolean isMcpSkill(String target) {
        return target != null && target.startsWith("mcp.");
    }

    private SkillResult executeWithTimeout(String candidate,
                                           Map<String, Object> params,
                                           SkillContext skillContext) {
        try {
            CompletableFuture<SkillResult> future = skillEngine.executeDslAsync(new SkillDsl(candidate, params), skillContext);
            if (isMcpSkill(candidate)) {
                future = future.completeOnTimeout(
                        SkillResult.failure(candidate, "timeout"),
                        mcpPerSkillTimeoutMs,
                        TimeUnit.MILLISECONDS
                );
            }
            return future.join();
        } catch (Exception ex) {
            return SkillResult.failure(candidate, ex.getMessage());
        }
    }

    private String stepName(int index) {
        return index == 0 ? "primary" : "fallback-" + index;
    }

    private int priorityRank(String skillName) {
        if (skillName == null || skillName.isBlank() || mcpPriorityOrder.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        String normalized = skillName.trim().toLowerCase();
        for (int i = 0; i < mcpPriorityOrder.size(); i++) {
            String configured = mcpPriorityOrder.get(i);
            if (normalized.equals(configured)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private ParamValidator.ValidationResult validateNamespace(String target) {
        if (target == null || target.isBlank() || !target.startsWith("mcp.")) {
            return ParamValidator.ValidationResult.ok();
        }
        String[] parts = target.split("\\.");
        if (parts.length < 3 || parts[1].isBlank() || parts[2].isBlank()) {
            return ParamValidator.ValidationResult.error("MCP 名称需为 mcp.<alias>.<tool>");
        }
        return ParamValidator.ValidationResult.ok();
    }

}
