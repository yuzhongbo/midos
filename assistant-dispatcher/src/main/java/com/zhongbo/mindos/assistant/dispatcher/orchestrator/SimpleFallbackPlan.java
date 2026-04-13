package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class SimpleFallbackPlan implements FallbackPlan {

    private final Map<String, List<String>> configuredFallbacks;

    @Autowired
    public SimpleFallbackPlan(@Value("${mindos.dispatcher.orchestrator.fallbacks:}") String configuredFallbacks) {
        this.configuredFallbacks = parseConfigured(configuredFallbacks);
    }

    public SimpleFallbackPlan() {
        this("");
    }

    @Override
    public List<String> fallbacks(String primary) {
        if (primary == null || primary.isBlank()) {
            return List.of();
        }
        return configuredFallbacks.getOrDefault(primary, List.of());
    }

    private Map<String, List<String>> parseConfigured(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        String[] pairs = raw.split("[;,]");
        for (String pair : pairs) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            String[] kv = pair.split("=");
            if (kv.length != 2) {
                continue;
            }
            String primary = kv[0].trim();
            if (primary.isBlank()) {
                continue;
            }
            List<String> fallbacks = List.of(kv[1].split("\\|")).stream()
                    .map(String::trim)
                    .filter(v -> !v.isBlank())
                    .collect(Collectors.toList());
            if (!fallbacks.isEmpty()) {
                parsed.put(primary, fallbacks);
            }
        }
        return parsed;
    }
}
