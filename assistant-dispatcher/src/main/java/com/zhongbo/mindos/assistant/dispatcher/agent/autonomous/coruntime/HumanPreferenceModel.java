package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.AGIMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.RuntimeContext;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class HumanPreferenceModel {

    private final AGIMemory memory;
    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final Map<String, HumanPreference> preferences = new ConcurrentHashMap<>();

    public HumanPreferenceModel(AGIMemory memory) {
        this(memory, null);
    }

    @Autowired
    public HumanPreferenceModel(AGIMemory memory,
                                DispatcherMemoryFacade dispatcherMemoryFacade) {
        this.memory = memory;
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
    }

    public void learn(HumanFeedback feedback) {
        if (feedback == null || !feedback.present() || feedback.userId().isBlank()) {
            return;
        }
        HumanPreference current = load(feedback.userId());
        double autonomy = blend(
                current.autonomyLevel(),
                feedback.suggestedAutonomy() == null
                        ? (feedback.approvedOutcome() ? 0.72 : 0.42)
                        : feedback.suggestedAutonomy(),
                feedback.suggestedAutonomy() == null ? 0.18 : 0.45
        );
        double riskTolerance = blend(
                current.riskTolerance(),
                feedback.suggestedRiskTolerance() == null
                        ? (feedback.requestRollback() ? Math.max(0.2, current.riskTolerance() - 0.15) : current.riskTolerance())
                        : feedback.suggestedRiskTolerance(),
                feedback.suggestedRiskTolerance() == null ? 0.25 : 0.50
        );
        double costSensitivity = blend(
                current.costSensitivity(),
                feedback.suggestedCostSensitivity() == null
                        ? (feedback.requestInterrupt() ? Math.min(1.0, current.costSensitivity() + 0.10) : current.costSensitivity())
                        : feedback.suggestedCostSensitivity(),
                feedback.suggestedCostSensitivity() == null ? 0.20 : 0.50
        );
        HumanPreference learned = new HumanPreference(
                autonomy,
                riskTolerance,
                costSensitivity,
                feedback.preferredStyle().isBlank() ? current.decisionStyle() : feedback.preferredStyle(),
                feedback.language().isBlank() ? current.language() : feedback.language(),
                feedback.preferredChannel().isBlank() ? current.preferredChannel() : feedback.preferredChannel(),
                current.prefersExplanations() || !feedback.notes().isBlank(),
                Instant.now()
        );
        preferences.put(feedback.userId(), learned);
        checkpoint(feedback.userId(), learned);
        persistProfile(feedback.userId(), learned);
    }

    public HumanPreference predict(RuntimeContext context) {
        RuntimeContext safeContext = context == null ? RuntimeContext.empty() : context;
        HumanPreference base = load(safeContext.userId());
        Map<String, Object> attributes = safeContext.attributes();
        List<String> seededKeys = seededPreferenceKeys(attributes);
        return new HumanPreference(
                seededKeys.contains("human.preference.autonomy")
                        ? base.autonomyLevel()
                        : numberAttribute(attributes, "human.preference.autonomy", base.autonomyLevel()),
                seededKeys.contains("human.preference.riskTolerance")
                        ? base.riskTolerance()
                        : numberAttribute(attributes, "human.preference.riskTolerance", base.riskTolerance()),
                seededKeys.contains("human.preference.costSensitivity")
                        ? base.costSensitivity()
                        : numberAttribute(attributes, "human.preference.costSensitivity", base.costSensitivity()),
                seededKeys.contains("human.preference.style")
                        ? base.decisionStyle()
                        : stringAttribute(attributes, "human.preference.style", base.decisionStyle()),
                seededKeys.contains("human.preference.language")
                        ? base.language()
                        : stringAttribute(attributes, "human.preference.language", base.language()),
                seededKeys.contains("human.preference.channel")
                        ? base.preferredChannel()
                        : stringAttribute(attributes, "human.preference.channel", base.preferredChannel()),
                seededKeys.contains("human.preference.prefersExplanations")
                        ? base.prefersExplanations()
                        : booleanAttribute(attributes, "human.preference.prefersExplanations", base.prefersExplanations()),
                Instant.now()
        );
    }

    private HumanPreference load(String userId) {
        if (userId == null || userId.isBlank()) {
            return HumanPreference.defaultPreference();
        }
        HumanPreference cached = preferences.get(userId.trim());
        if (cached != null) {
            return cached;
        }
        HumanPreference fromMemory = loadFromMemory(userId);
        if (fromMemory != null) {
            preferences.put(userId.trim(), fromMemory);
            return fromMemory;
        }
        PreferenceProfile profile = dispatcherMemoryFacade == null
                ? PreferenceProfile.empty()
                : dispatcherMemoryFacade.getPreferenceProfile(userId);
        HumanPreference learned = new HumanPreference(
                0.6,
                0.5,
                0.5,
                profile == null || profile.style() == null ? "balanced" : profile.style(),
                profile == null || profile.language() == null ? "" : profile.language(),
                profile == null || profile.preferredChannel() == null ? "" : profile.preferredChannel(),
                true,
                Instant.now()
        );
        preferences.put(userId.trim(), learned);
        return learned;
    }

    private HumanPreference loadFromMemory(String userId) {
        if (memory == null || userId == null || userId.isBlank()) {
            return null;
        }
        Map<String, Object> values = memory.shortTerm().get(namespace(userId));
        if (values.isEmpty()) {
            return null;
        }
        return new HumanPreference(
                numberAttribute(values, "autonomyLevel", 0.6),
                numberAttribute(values, "riskTolerance", 0.5),
                numberAttribute(values, "costSensitivity", 0.5),
                stringAttribute(values, "decisionStyle", "balanced"),
                stringAttribute(values, "language", ""),
                stringAttribute(values, "preferredChannel", ""),
                booleanAttribute(values, "prefersExplanations", true),
                Instant.now()
        );
    }

    private void checkpoint(String userId, HumanPreference preference) {
        if (memory == null || userId == null || userId.isBlank() || preference == null) {
            return;
        }
        memory.shortTerm().put(namespace(userId), preference.asMap());
        memory.semantic().put(namespace(userId) + ":summary",
                "style=" + preference.decisionStyle()
                        + ",autonomy=" + round(preference.autonomyLevel())
                        + ",risk=" + round(preference.riskTolerance())
                        + ",cost=" + round(preference.costSensitivity()));
        memory.longTerm().link(userId, namespace(userId));
    }

    private void persistProfile(String userId, HumanPreference preference) {
        if (dispatcherMemoryFacade == null || userId == null || userId.isBlank() || preference == null) {
            return;
        }
        PreferenceProfile existing = dispatcherMemoryFacade.getPreferenceProfile(userId);
        PreferenceProfile merged = (existing == null ? PreferenceProfile.empty() : existing).merge(new PreferenceProfile(
                null,
                "human-ai-co-runtime",
                preference.decisionStyle(),
                preference.language(),
                null,
                preference.preferredChannel()
        ));
        dispatcherMemoryFacade.updatePreferenceProfile(userId, merged);
    }

    private String namespace(String userId) {
        return "human:preference:" + userId.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> seededPreferenceKeys(Map<String, Object> attributes) {
        Object raw = attributes.get("coruntime.seededPreferenceKeys");
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item != null && !String.valueOf(item).isBlank())
                    .map(item -> String.valueOf(item).trim())
                    .toList();
        }
        return java.util.List.of();
    }

    private double blend(double current, double target, double weight) {
        return clamp(current * (1.0 - weight) + target * weight);
    }

    private double numberAttribute(Map<String, Object> attributes, String key, double fallback) {
        Object value = attributes.get(key);
        if (value instanceof Number number) {
            return clamp(number.doubleValue());
        }
        if (value == null) {
            return fallback;
        }
        try {
            return clamp(Double.parseDouble(String.valueOf(value).trim()));
        } catch (NumberFormatException ignore) {
            return fallback;
        }
    }

    private boolean booleanAttribute(Map<String, Object> attributes, String key, boolean fallback) {
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

    private String stringAttribute(Map<String, Object> attributes, String key, String fallback) {
        Object value = attributes.get(key);
        if (value == null) {
            return fallback;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? fallback : normalized;
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
