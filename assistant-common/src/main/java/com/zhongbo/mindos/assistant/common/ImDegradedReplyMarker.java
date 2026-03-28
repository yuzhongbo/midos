package com.zhongbo.mindos.assistant.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class ImDegradedReplyMarker {

    private static final String PREFIX = "[[MINDOS_IM_DEGRADED|";
    private static final String SUFFIX = "]]";

    private ImDegradedReplyMarker() {
    }

    public static String encode(String provider, String errorCategory) {
        return PREFIX
                + "provider=" + normalize(provider)
                + "|category=" + normalize(errorCategory)
                + SUFFIX;
    }

    public static Optional<Parsed> parse(String text) {
        if (text == null || !text.startsWith(PREFIX)) {
            return Optional.empty();
        }
        int suffixIndex = text.indexOf(SUFFIX, PREFIX.length());
        if (suffixIndex < 0) {
            return Optional.empty();
        }
        String payload = text.substring(PREFIX.length(), suffixIndex);
        Map<String, String> fields = new LinkedHashMap<>();
        for (String token : payload.split("\\|")) {
            int separator = token.indexOf('=');
            if (separator <= 0 || separator == token.length() - 1) {
                continue;
            }
            fields.put(token.substring(0, separator).trim(), token.substring(separator + 1).trim());
        }
        String provider = normalize(fields.get("provider"));
        String category = normalize(fields.get("category"));
        String remainder = text.substring(suffixIndex + SUFFIX.length()).trim();
        return Optional.of(new Parsed(provider, category, remainder));
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase();
    }

    public record Parsed(String provider, String errorCategory, String remainder) {
    }
}

