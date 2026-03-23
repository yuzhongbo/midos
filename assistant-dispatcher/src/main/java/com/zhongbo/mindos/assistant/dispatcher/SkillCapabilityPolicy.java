package com.zhongbo.mindos.assistant.dispatcher;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class SkillCapabilityPolicy {

    private static final Set<String> KNOWN_CAPABILITIES = Set.of("fs.read", "fs.write", "exec", "net");

    private final boolean enabled;
    private final Set<String> allowedCapabilities;
    private final Map<String, Set<String>> requiredCapabilitiesBySkill;

    public SkillCapabilityPolicy(
            @Value("${mindos.security.skill.capability.guard.enabled:true}") boolean enabled,
            @Value("${mindos.security.skill.allowed-capabilities:fs.read,fs.write,exec,net}") String allowedCapabilities,
            @Value("${mindos.security.skill.capability-map:}") String capabilityMap) {
        this.enabled = enabled;
        this.allowedCapabilities = parseCapabilities(allowedCapabilities);
        this.requiredCapabilitiesBySkill = parseCapabilityMap(capabilityMap);
        validateCapabilities();
    }

    public boolean isAllowed(String skillName) {
        if (!enabled) {
            return true;
        }
        Set<String> required = requiredCapabilitiesBySkill.get(normalize(skillName));
        if (required == null || required.isEmpty()) {
            return true;
        }
        return allowedCapabilities.containsAll(required);
    }

    public Set<String> missingCapabilities(String skillName) {
        Set<String> required = requiredCapabilitiesBySkill.get(normalize(skillName));
        if (required == null || required.isEmpty()) {
            return Set.of();
        }
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(allowedCapabilities);
        return Set.copyOf(missing);
    }

    private Map<String, Set<String>> parseCapabilityMap(String rawMap) {
        if (rawMap == null || rawMap.isBlank()) {
            return Map.of();
        }
        Map<String, Set<String>> parsed = new LinkedHashMap<>();
        String[] entries = rawMap.split(",");
        for (String entry : entries) {
            int sep = entry.indexOf(':');
            if (sep <= 0 || sep >= entry.length() - 1) {
                throw new IllegalArgumentException("Invalid skill capability mapping entry: " + entry);
            }
            String skillName = normalize(entry.substring(0, sep));
            String rawCapabilities = entry.substring(sep + 1);
            Set<String> capabilities = parseCapabilities(rawCapabilities.replace('|', ';').replace('+', ';').replace('&', ';'));
            if (!skillName.isBlank() && !capabilities.isEmpty()) {
                parsed.put(skillName, capabilities);
            } else {
                throw new IllegalArgumentException("Invalid skill capability mapping entry: " + entry);
            }
        }
        return Map.copyOf(parsed);
    }

    private Set<String> parseCapabilities(String rawCapabilities) {
        if (rawCapabilities == null || rawCapabilities.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(rawCapabilities.split("[;,]"))
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private void validateCapabilities() {
        validateCapabilitySet("mindos.security.skill.allowed-capabilities", allowedCapabilities);
        for (Map.Entry<String, Set<String>> entry : requiredCapabilitiesBySkill.entrySet()) {
            validateCapabilitySet("mindos.security.skill.capability-map for skill " + entry.getKey(), entry.getValue());
        }
    }

    private void validateCapabilitySet(String source, Set<String> capabilities) {
        for (String capability : capabilities) {
            if (!KNOWN_CAPABILITIES.contains(capability)) {
                throw new IllegalArgumentException("Unsupported capability '" + capability + "' in " + source
                        + ". Supported capabilities: " + KNOWN_CAPABILITIES);
            }
        }
    }
}

