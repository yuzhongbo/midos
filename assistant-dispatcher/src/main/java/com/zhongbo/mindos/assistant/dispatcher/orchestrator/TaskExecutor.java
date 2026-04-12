package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.PlanStepDto;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TaskExecutor {

    private final int maxSteps;

    public TaskExecutor(@Value("${mindos.dispatcher.task-plan.max-steps:3}") int maxSteps) {
        this.maxSteps = Math.max(1, Math.min(3, maxSteps));
    }

    public TaskExecutionResult execute(TaskPlan plan,
                                       SkillContext baseContext,
                                       TaskStepRunner runner) {
        if (plan == null || plan.isEmpty()) {
            return TaskExecutionResult.empty(baseContext == null ? Map.of() : baseContext.attributes());
        }
        Map<String, Object> contextAttributes = new LinkedHashMap<>(baseContext == null ? Map.of() : baseContext.attributes());
        List<PlanStepDto> steps = new ArrayList<>();
        SkillResult lastResult = null;
        String lastSelectedSkill = null;
        boolean usedFallback = false;
        int executed = 0;
        for (TaskStep step : plan.steps()) {
            if (executed >= maxSteps) {
                break;
            }
            executed++;
            Instant startedAt = Instant.now();
            Map<String, Object> resolvedParams = resolveParams(step.params(), contextAttributes);
            SkillContext stepContext = new SkillContext(
                    baseContext == null ? "" : baseContext.userId(),
                    baseContext == null ? "" : baseContext.input(),
                    mergeAttributes(contextAttributes, resolvedParams)
            );
            StepExecutionResult execution = runner.run(step, stepContext);
            Instant finishedAt = Instant.now();
            lastResult = execution.result();
            lastSelectedSkill = execution.selectedSkill();
            usedFallback = usedFallback || execution.usedFallback();
            steps.add(new PlanStepDto(
                    step.id(),
                    lastResult != null && lastResult.success() ? "success" : "failed",
                    lastSelectedSkill == null || lastSelectedSkill.isBlank() ? step.target() : lastSelectedSkill,
                    lastResult == null ? "no result" : lastResult.output(),
                    startedAt,
                    finishedAt
            ));
            if (lastResult != null) {
                contextAttributes.put("task.last.output", lastResult.output());
                contextAttributes.put("task.last.skill", lastResult.skillName());
                contextAttributes.put("task.last.success", lastResult.success());
                if (!step.saveAs().isBlank()) {
                    contextAttributes.put("task." + step.saveAs() + ".output", lastResult.output());
                    contextAttributes.put("task." + step.saveAs() + ".skill", lastResult.skillName());
                    contextAttributes.put("task." + step.saveAs() + ".success", lastResult.success());
                }
            }
            if (lastResult == null || !lastResult.success()) {
                if (step.optional()) {
                    continue;
                }
                break;
            }
        }
        return new TaskExecutionResult(lastResult, steps, Map.copyOf(contextAttributes), lastSelectedSkill, usedFallback);
    }

    private Map<String, Object> resolveParams(Map<String, Object> rawParams, Map<String, Object> contextAttributes) {
        if (rawParams == null || rawParams.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        rawParams.forEach((key, value) -> resolved.put(key, resolveValue(value, contextAttributes)));
        return Map.copyOf(resolved);
    }

    private Object resolveValue(Object value, Map<String, Object> contextAttributes) {
        if (!(value instanceof String text) || text.isBlank()) {
            return value;
        }
        String resolved = text;
        for (Map.Entry<String, Object> entry : contextAttributes.entrySet()) {
            String placeholder = "${" + entry.getKey() + "}";
            if (resolved.contains(placeholder)) {
                resolved = resolved.replace(placeholder, entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
            }
        }
        return resolved;
    }

    private Map<String, Object> mergeAttributes(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        if (extra != null && !extra.isEmpty()) {
            merged.putAll(extra);
        }
        return Map.copyOf(merged);
    }

    @FunctionalInterface
    public interface TaskStepRunner {
        StepExecutionResult run(TaskStep step, SkillContext stepContext);
    }

    public record StepExecutionResult(SkillResult result, String selectedSkill, boolean usedFallback) {
    }

    public record TaskExecutionResult(SkillResult result,
                                      List<PlanStepDto> steps,
                                      Map<String, Object> contextAttributes,
                                      String selectedSkill,
                                      boolean usedFallback) {
        public static TaskExecutionResult empty(Map<String, Object> contextAttributes) {
            return new TaskExecutionResult(null, List.of(), contextAttributes == null ? Map.of() : Map.copyOf(contextAttributes), null, false);
        }
    }
}
