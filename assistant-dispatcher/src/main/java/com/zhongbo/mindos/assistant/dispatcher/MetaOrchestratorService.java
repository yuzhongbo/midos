package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.PlanStepDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@Service
public class MetaOrchestratorService {

    private final boolean enabled;

    public MetaOrchestratorService(@Value("${mindos.dispatcher.meta-orchestrator.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    public CompletableFuture<MetaOrchestrationResult> orchestrate(
            Supplier<CompletableFuture<SkillResult>> primaryAttempt,
            Supplier<CompletableFuture<SkillResult>> fallbackAttempt) {
        if (!enabled) {
            Instant start = Instant.now();
            return primaryAttempt.get().thenApply(result -> {
                Instant finish = Instant.now();
                ExecutionTraceDto trace = new ExecutionTraceDto(
                        "single-pass",
                        0,
                        new CritiqueReportDto(true, "meta-orchestrator disabled", "none"),
                        List.of(new PlanStepDto("primary", "success", result.skillName(), "primary execution", start, finish))
                );
                return new MetaOrchestrationResult(result, trace);
            });
        }

        List<PlanStepDto> steps = new ArrayList<>();
        Instant primaryStart = Instant.now();
        return primaryAttempt.get()
                .handle((primaryResult, primaryError) -> {
                    if (primaryError == null && primaryResult != null && primaryResult.success()) {
                        steps.add(new PlanStepDto(
                                "primary",
                                "success",
                                primaryResult.skillName(),
                                "primary execution",
                                primaryStart,
                                Instant.now()));
                        ExecutionTraceDto trace = new ExecutionTraceDto(
                                "meta-replan",
                                0,
                                new CritiqueReportDto(true, "primary succeeded", "none"),
                                steps
                        );
                        return CompletableFuture.completedFuture(new MetaOrchestrationResult(primaryResult, trace));
                    }

                    String failureReason = primaryError != null
                            ? String.valueOf(primaryError.getMessage())
                            : (primaryResult == null ? "unknown primary result" : primaryResult.output());
                    steps.add(new PlanStepDto(
                            "primary",
                            "failed",
                            primaryResult == null ? "unknown" : primaryResult.skillName(),
                            failureReason,
                            primaryStart,
                            Instant.now()));

                    Instant fallbackStart = Instant.now();
                    return fallbackAttempt.get().thenApply(fallbackResult -> {
                        steps.add(new PlanStepDto(
                                "fallback",
                                "success",
                                fallbackResult.skillName(),
                                "fallback after critique",
                                fallbackStart,
                                Instant.now()));
                        ExecutionTraceDto trace = new ExecutionTraceDto(
                                "meta-replan",
                                1,
                                new CritiqueReportDto(false, failureReason, "replan_to_fallback"),
                                steps
                        );
                        return new MetaOrchestrationResult(fallbackResult, trace);
                    });
                })
                .thenCompose(future -> future);
    }

    public record MetaOrchestrationResult(SkillResult result, ExecutionTraceDto trace) {
    }
}

