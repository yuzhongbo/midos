package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.ExecutionPlan;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.ExecutionPolicy;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.RuntimeState;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.Task;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.TaskHandle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record SharedDecisionContext(TaskHandle handle,
                                    Task task,
                                    RuntimeState runtimeState,
                                    ExecutionPlan plan,
                                    HumanIntent intent,
                                    HumanPreference preference,
                                    double trustScore,
                                    Map<String, Object> attributes) {

    public SharedDecisionContext {
        Task safeTask = task == null && runtimeState != null ? runtimeState.task() : task;
        task = safeTask == null ? Task.fromGoal(null, ExecutionPolicy.AUTONOMOUS, Map.of()) : safeTask;
        handle = handle == null ? new TaskHandle(task.taskId()) : handle;
        intent = intent == null ? HumanIntent.empty() : intent;
        preference = preference == null ? HumanPreference.defaultPreference() : preference;
        trustScore = clamp(trustScore, -1.0);
        attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
    }

    public static SharedDecisionContext from(TaskHandle handle,
                                             RuntimeState runtimeState,
                                             ExecutionPlan plan,
                                             HumanIntent intent,
                                             HumanPreference preference,
                                             double trustScore) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        if (runtimeState != null && runtimeState.context() != null) {
            merged.putAll(runtimeState.context().attributes());
        }
        if (plan != null) {
            merged.putAll(plan.attributes());
        }
        return new SharedDecisionContext(handle, runtimeState == null ? null : runtimeState.task(), runtimeState, plan, intent, preference, trustScore, merged);
    }

    public double confidence() {
        return numberAttribute("prediction.successProbability", 0.5);
    }

    public double risk() {
        return numberAttribute("prediction.risk", 0.0);
    }

    public double cost() {
        return numberAttribute("prediction.cost", 0.0);
    }

    public List<String> targets() {
        return stringListAttribute("tool.targets");
    }

    public boolean booleanAttribute(String key, boolean fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        Object value = attributes.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return fallback;
        }
        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "yes".equals(normalized) || "1".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "no".equals(normalized) || "0".equals(normalized)) {
            return false;
        }
        return fallback;
    }

    public String stringAttribute(String key, String fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        Object value = attributes.get(key);
        return value == null ? fallback : String.valueOf(value).trim();
    }

    public List<String> stringListAttribute(String key) {
        if (key == null || key.isBlank()) {
            return List.of();
        }
        Object value = attributes.get(key);
        if (value instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    values.add(String.valueOf(item).trim());
                }
            }
            return values.isEmpty() ? List.of() : List.copyOf(values);
        }
        if (value == null) {
            return List.of();
        }
        String raw = String.valueOf(value).trim();
        if (raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split("[,;；]");
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                values.add(part.trim());
            }
        }
        return values.isEmpty() ? List.of() : List.copyOf(values);
    }

    public double numberAttribute(String key, double fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        Object value = attributes.get(key);
        if (value instanceof Number number) {
            return clamp(number.doubleValue(), fallback);
        }
        if (value == null) {
            return fallback;
        }
        try {
            return clamp(Double.parseDouble(String.valueOf(value).trim()), fallback);
        } catch (NumberFormatException ignore) {
            return fallback;
        }
    }

    private static double clamp(double value, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        return Math.max(-1.0, Math.min(1.0, value));
    }
}
