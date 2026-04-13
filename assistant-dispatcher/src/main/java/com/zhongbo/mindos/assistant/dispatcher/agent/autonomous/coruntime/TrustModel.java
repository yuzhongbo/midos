package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.EvaluationResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.AGIMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.RuntimeContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.Task;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TrustModel {

    private static final double DEFAULT_TRUST = 0.55;

    private final AGIMemory memory;
    private final Map<String, Double> trustScores = new ConcurrentHashMap<>();

    public TrustModel(AGIMemory memory) {
        this.memory = memory;
    }

    public double trustScore(Task task) {
        return trustScore(task, null);
    }

    public double trustScore(Task task, RuntimeContext context) {
        String userId = resolveUserId(task, context);
        if (userId.isBlank()) {
            return DEFAULT_TRUST;
        }
        Double cached = trustScores.get(userId);
        if (cached != null) {
            return cached;
        }
        if (memory != null) {
            Map<String, Object> snapshot = memory.shortTerm().get(namespace(userId));
            Object rawValue = snapshot.get("trustScore");
            if (rawValue instanceof Number number) {
                double trust = clamp(number.doubleValue());
                trustScores.put(userId, trust);
                return trust;
            }
        }
        trustScores.put(userId, DEFAULT_TRUST);
        return DEFAULT_TRUST;
    }

    public double update(Task task,
                         EvaluationResult evaluation,
                         HumanFeedback feedback,
                         RuntimeContext context) {
        String userId = resolveUserId(task, context);
        if (userId.isBlank()) {
            return DEFAULT_TRUST;
        }
        double next = trustScore(task, context);
        if (evaluation != null) {
            next += evaluation.isSuccess() ? 0.12 : evaluation.needsReplan() ? -0.04 : -0.12;
            next += (evaluation.progressScore() - 0.5) * 0.06;
        }
        if (feedback != null && feedback.present()) {
            next += (feedback.satisfactionScore() - 0.5) * 0.12;
            if (!feedback.approvedOutcome()) {
                next -= 0.08;
            }
            if (feedback.requestRollback()) {
                next -= 0.12;
            }
            if (feedback.requestInterrupt()) {
                next -= 0.08;
            }
        }
        double trust = clamp(next);
        trustScores.put(userId, trust);
        checkpoint(userId, trust);
        return trust;
    }

    private void checkpoint(String userId, double trust) {
        if (memory == null || userId.isBlank()) {
            return;
        }
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("trustScore", trust);
        memory.shortTerm().put(namespace(userId), snapshot);
        memory.semantic().put(namespace(userId) + ":summary", "trust=" + round(trust));
        memory.longTerm().link(userId, namespace(userId));
    }

    private String resolveUserId(Task task, RuntimeContext context) {
        if (context != null && context.userId() != null && !context.userId().isBlank()) {
            return context.userId().trim().toLowerCase(Locale.ROOT);
        }
        if (task == null || task.metadata() == null) {
            return "";
        }
        Object value = task.metadata().get("userId");
        return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    private String namespace(String userId) {
        return "human:trust:" + userId;
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return DEFAULT_TRUST;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
