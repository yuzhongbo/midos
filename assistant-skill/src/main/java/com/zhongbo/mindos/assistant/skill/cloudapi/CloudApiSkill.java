package com.zhongbo.mindos.assistant.skill.cloudapi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link Skill} that calls a configured cloud REST API endpoint.
 *
 * <p>Semantic matching is done via keyword list defined in {@link CloudApiSkillDefinition#keywords()}.
 * The skill is selected when the normalized user input contains any of the configured keywords.
 *
 * <p>Template placeholders are resolved at call time from {@link SkillContext}:
 * <ul>
 *   <li>{@code ${input}}           — full user input text</li>
 *   <li>{@code ${input.fieldName}} — context attribute by field name</li>
 *   <li>{@code ${apiKey}}          — definition's apiKey field</li>
 *   <li>{@code ${env.VAR_NAME}}    — system environment variable</li>
 * </ul>
 */
public class CloudApiSkill implements Skill, SkillDescriptorProvider {

    private static final Logger LOGGER = Logger.getLogger(CloudApiSkill.class.getName());
    // Matches the dispatcher's llm-reply max-chars budget to stay within system-wide output limits
    private static final int MAX_RESULT_CHARS = 1200;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final CloudApiSkillDefinition definition;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public CloudApiSkill(CloudApiSkillDefinition definition) {
        this(definition,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build(),
                new ObjectMapper().findAndRegisterModules());
    }

    CloudApiSkill(CloudApiSkillDefinition definition, HttpClient httpClient, ObjectMapper objectMapper) {
        this.definition = definition;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return definition.name();
    }

    @Override
    public String description() {
        return definition.description();
    }

    @Override
    public SkillDescriptor skillDescriptor() {
        return new SkillDescriptor(name(), description(), definition.keywords());
    }

    @Override
    public SkillResult run(SkillContext context) {
        try {
            String resolvedUrl = resolveTemplate(definition.url(), context);
            String queryString = buildQueryString(context);
            String fullUrl = queryString.isBlank() ? resolvedUrl : resolvedUrl + "?" + queryString;

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(fullUrl))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json");

            for (Map.Entry<String, String> header : definition.headers().entrySet()) {
                String key = resolveTemplate(header.getKey(), context);
                String value = resolveTemplate(header.getValue(), context);
                if (!key.isBlank() && !value.isBlank()) {
                    requestBuilder.header(key, value);
                }
            }

            if (definition.apiKey() != null && !definition.apiKey().isBlank()) {
                String resolvedKey = resolveTemplate(definition.apiKey(), context);
                if (!resolvedKey.isBlank()) {
                    requestBuilder.header(definition.apiKeyHeader(), resolvedKey);
                }
            }

            String method = definition.method();
            if ("GET".equals(method) || "DELETE".equals(method)) {
                requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                String body = buildRequestBody(context);
                requestBuilder.header("Content-Type", "application/json");
                requestBuilder.method(method, HttpRequest.BodyPublishers.ofString(body));
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return SkillResult.failure(name(),
                        "Cloud API returned HTTP " + response.statusCode() + ": "
                                + truncate(response.body(), 200));
            }

            String output = extractResult(response.body(), context);
            return SkillResult.success(name(), output);

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "CloudApiSkill " + name() + ": request failed", e);
            return SkillResult.failure(name(), "Cloud API call failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SkillResult.failure(name(), "Cloud API call interrupted");
        }
    }

    private String buildQueryString(SkillContext context) {
        if (definition.queryParams().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : definition.queryParams().entrySet()) {
            String key = URLEncoder.encode(resolveTemplate(entry.getKey(), context), StandardCharsets.UTF_8);
            String value = URLEncoder.encode(resolveTemplate(entry.getValue(), context), StandardCharsets.UTF_8);
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(key).append('=').append(value);
        }
        return sb.toString();
    }

    private String buildRequestBody(SkillContext context) {
        if (definition.bodyTemplate() != null && !definition.bodyTemplate().isBlank()) {
            return resolveTemplate(definition.bodyTemplate(), context);
        }
        Map<String, Object> body = new LinkedHashMap<>(context.attributes());
        if (!body.containsKey("input") && context.input() != null) {
            body.put("input", context.input());
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (IOException e) {
            return "{}";
        }
    }

    private String extractResult(String responseBody, SkillContext context) throws IOException {
        if (definition.resultPath() == null || definition.resultPath().isBlank()) {
            return truncate(responseBody, MAX_RESULT_CHARS);
        }

        Map<String, Object> jsonObj = objectMapper.readValue(responseBody, new TypeReference<>() {});
        Object extracted = extractByPath(jsonObj, definition.resultPath());

        if (definition.resultTemplate() != null && !definition.resultTemplate().isBlank()) {
            return renderResultTemplate(definition.resultTemplate(), extracted, context);
        }

        if (extracted instanceof String s) {
            return s;
        }
        try {
            return truncate(objectMapper.writeValueAsString(extracted), MAX_RESULT_CHARS);
        } catch (IOException e) {
            return String.valueOf(extracted);
        }
    }

    private Object extractByPath(Map<String, Object> root, String path) {
        String[] segments = path.split("\\.");
        Object current = root;
        for (String segment : segments) {
            if (!(current instanceof Map<?, ?> map)) {
                return current;
            }
            current = map.get(segment);
        }
        return current;
    }

    private String renderResultTemplate(String template, Object data, SkillContext context) {
        String result = template;
        if (data instanceof Map<?, ?> dataMap) {
            for (Map.Entry<?, ?> entry : dataMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String value = objectSafeString(entry.getValue());
                result = result.replace("${" + key + "}", value);
            }
        }
        return resolveTemplate(result, context);
    }

    /**
     * Resolves {@code ${input}}, {@code ${input.field}}, {@code ${apiKey}}, and
     * {@code ${env.VAR}} placeholders in {@code template}.
     */
    String resolveTemplate(String template, SkillContext context) {
        if (template == null || template.isBlank()) {
            return template == null ? "" : template;
        }
        String result = template;

        if (context.input() != null) {
            result = result.replace("${input}", context.input());
        }

        for (Map.Entry<String, Object> attr : context.attributes().entrySet()) {
            result = result.replace("${input." + attr.getKey() + "}", objectSafeString(attr.getValue()));
        }

        if (definition.apiKey() != null) {
            result = result.replace("${apiKey}", definition.apiKey());
        }

        int envStart;
        int guard = 0;
        while ((envStart = result.indexOf("${env.")) >= 0 && guard++ < 20) {
            int envEnd = result.indexOf("}", envStart);
            if (envEnd < 0) {
                break;
            }
            String varName = result.substring(envStart + 6, envEnd);
            String envVal = System.getenv(varName);
            // Replace only the matched placeholder to avoid re-processing substituted values
            result = result.substring(0, envStart)
                    + (envVal == null ? "" : envVal)
                    + result.substring(envEnd + 1);
        }

        return result;
    }

    private String objectSafeString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            return String.valueOf(value);
        }
    }

    private String truncate(String text, int maxLen) {
        return text != null && text.length() > maxLen
                ? text.substring(0, maxLen) + "..."
                : (text == null ? "" : text);
    }
}
