package com.zhongbo.mindos.assistant.memory.model;

public record PreferenceProfile(
        String assistantName,
        String role,
        String style,
        String language,
        String timezone,
        String preferredChannel
) {

    public static PreferenceProfile empty() {
        return new PreferenceProfile(null, null, null, null, null, null);
    }

    public PreferenceProfile merge(PreferenceProfile override) {
        if (override == null) {
            return this;
        }
        return new PreferenceProfile(
                pick(override.assistantName(), assistantName),
                pick(override.role(), role),
                pick(override.style(), style),
                pick(override.language(), language),
                pick(override.timezone(), timezone),
                pick(override.preferredChannel(), preferredChannel)
        );
    }

    private static String pick(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }
}

