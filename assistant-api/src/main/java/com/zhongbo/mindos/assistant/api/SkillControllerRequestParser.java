package com.zhongbo.mindos.assistant.api;

import java.util.LinkedHashMap;
import java.util.Map;

final class SkillControllerRequestParser {

    record ValidationResult<T>(T value, Map<String, Object> errorResponse) {
        boolean valid() {
            return errorResponse == null;
        }
    }

    record LoadJarRequest(String url) {
    }

    record LoadMcpRequest(String alias, String url, Map<String, String> headers) {
    }

    record GenerateSkillRequest(String prompt, String skillName, String userId, Map<String, Object> hints) {
    }

    ValidationResult<LoadJarRequest> parseLoadJar(Map<String, String> request) {
        String jarUrl = request == null ? null : asTrimmedString(request.get("url"));
        if (jarUrl == null) {
            return invalid("Field 'url' is required.");
        }
        return valid(new LoadJarRequest(jarUrl));
    }

    ValidationResult<LoadMcpRequest> parseLoadMcp(Map<String, Object> request) {
        String alias = request == null ? null : asTrimmedString(request.get("alias"));
        if (alias == null) {
            return invalid("Field 'alias' is required.");
        }
        String url = request == null ? null : asTrimmedString(request.get("url"));
        if (url == null) {
            return invalid("Field 'url' is required.");
        }
        Map<String, String> headers = extractHeaders(request == null ? null : request.get("headers"));
        return valid(new LoadMcpRequest(alias, url, headers));
    }

    ValidationResult<GenerateSkillRequest> parseGenerate(Map<String, Object> request) {
        String prompt = request == null ? null : asTrimmedString(request.get("request"));
        if (prompt == null) {
            return invalid("Field 'request' is required.");
        }
        String skillName = request == null ? null : asTrimmedString(request.get("skillName"));
        String userId = request == null ? null : asTrimmedString(request.get("userId"));
        Map<String, Object> hints = extractObjectMap(request == null ? null : request.get("hints"));
        return valid(new GenerateSkillRequest(prompt, skillName, userId, hints));
    }

    private <T> ValidationResult<T> valid(T value) {
        return new ValidationResult<>(value, null);
    }

    private <T> ValidationResult<T> invalid(String message) {
        return new ValidationResult<>(null, Map.of("status", "error", "error", message));
    }

    private String asTrimmedString(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        String value = String.valueOf(rawValue).trim();
        return value.isBlank() ? null : value;
    }

    private Map<String, String> extractHeaders(Object rawHeaders) {
        if (!(rawHeaders instanceof Map<?, ?> headerMap)) {
            return Map.of();
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : headerMap.entrySet()) {
            String key = asTrimmedString(entry.getKey());
            String value = asTrimmedString(entry.getValue());
            if (key == null || value == null) {
                continue;
            }
            parsed.put(key, value);
        }
        return parsed.isEmpty() ? Map.of() : Map.copyOf(parsed);
    }

    private Map<String, Object> extractObjectMap(Object rawValue) {
        if (!(rawValue instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> parsed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            String key = asTrimmedString(entry.getKey());
            Object value = entry.getValue();
            if (key == null || value == null) {
                continue;
            }
            parsed.put(key, value);
        }
        return parsed.isEmpty() ? Map.of() : Map.copyOf(parsed);
    }
}
