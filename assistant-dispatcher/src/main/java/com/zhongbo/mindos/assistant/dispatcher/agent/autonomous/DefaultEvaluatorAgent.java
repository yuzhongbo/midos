package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.MasterOrchestrationResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class DefaultEvaluatorAgent implements EvaluatorAgent {

    @Override
    public AutonomousEvaluation evaluate(AutonomousGoal goal, MasterOrchestrationResult result) {
        return evaluate(goal, result, null);
    }

    @Override
    public AutonomousEvaluation evaluate(AutonomousGoal goal,
                                         MasterOrchestrationResult result,
                                         String userFeedback) {
        SkillResult skillResult = result == null ? null : result.result();
        ExecutionTraceDto trace = result == null ? null : result.trace();
        CritiqueReportDto critique = trace == null ? null : trace.critique();
        boolean success = skillResult != null && skillResult.success();

        List<String> reasons = new ArrayList<>();
        reasons.add(success ? "result-success" : "result-failure");
        if (goal != null && goal.priority() > 0) {
            reasons.add("goalPriority=" + goal.priority());
        }
        int replanCount = trace == null ? 0 : Math.max(0, trace.replanCount());
        if (replanCount > 0) {
            reasons.add("replanCount=" + replanCount);
        }
        if (critique != null) {
            if (critique.reason() != null && !critique.reason().isBlank()) {
                reasons.add("traceReason=" + critique.reason().trim());
            }
            if (critique.action() != null && !critique.action().isBlank()) {
                reasons.add("traceAction=" + critique.action().trim());
            }
            if (!critique.success()) {
                reasons.add("traceCritique=failure");
            }
        }
        long failedSteps = 0L;
        if (trace != null && trace.steps() != null) {
            reasons.add("steps=" + trace.steps().size());
            failedSteps = trace.steps().stream()
                    .filter(step -> step != null && step.status() != null && !"success".equalsIgnoreCase(step.status()))
                    .count();
            if (failedSteps > 0L) {
                reasons.add("failedSteps=" + failedSteps);
            }
        }
        String normalizedUserFeedback = normalize(userFeedback);
        if (!normalizedUserFeedback.isBlank()) {
            reasons.add("userFeedback=" + truncate(normalizedUserFeedback, 120));
        }

        double score = success ? 1.0 : 0.0;
        if (success) {
            score -= Math.min(0.20, replanCount * 0.05);
            score -= Math.min(0.35, failedSteps * 0.10);
            if (critique != null && !critique.success()) {
                score -= 0.05;
            }
            score += feedbackBonus(normalizedUserFeedback);
        } else {
            score += feedbackBonus(normalizedUserFeedback) * 0.25;
        }
        score = clamp(score);

        String feedback = !normalizedUserFeedback.isBlank()
                ? normalizedUserFeedback
                : success
                ? "目标完成，可固化为流程"
                : firstNonBlank(
                        critique == null ? "" : critique.reason(),
                        skillResult == null ? "" : skillResult.output(),
                        "目标未完成，需要重新规划"
                );
        String nextAction = success
                ? (hasLatencyConcern(normalizedUserFeedback) ? "optimize-latency" : "learn-procedure")
                : firstNonBlank(critique == null ? "" : critique.action(), "replan");

        return new AutonomousEvaluation(
                goal == null ? "" : goal.goalId(),
                goal == null ? AutonomousGoalType.FALLBACK : goal.type(),
                success,
                score,
                feedback,
                nextAction,
                skillResult == null ? "" : skillResult.skillName(),
                skillResult == null ? "" : skillResult.output(),
                reasons,
                java.time.Instant.now()
        );
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private double feedbackBonus(String userFeedback) {
        if (userFeedback == null || userFeedback.isBlank()) {
            return 0.0;
        }
        String normalized = userFeedback.toLowerCase(Locale.ROOT);
        double bonus = 0.0;
        if (containsAny(normalized, "耗时", "较长", "慢", "延迟", "卡", "拖")) {
            bonus -= 0.15;
        }
        if (containsAny(normalized, "失败", "错误", "异常", "问题", "不满意")) {
            bonus -= 0.10;
        }
        if (containsAny(normalized, "很好", "顺利", "满意", "稳定", "高效", "快速")) {
            bonus += 0.05;
        }
        return bonus;
    }

    private boolean hasLatencyConcern(String userFeedback) {
        if (userFeedback == null || userFeedback.isBlank()) {
            return false;
        }
        return containsAny(userFeedback.toLowerCase(Locale.ROOT), "耗时", "较长", "慢", "延迟", "卡", "拖");
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || text.isBlank() || keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 1)) + "…";
    }
}
