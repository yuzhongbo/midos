package com.zhongbo.mindos.assistant.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.dto.LlmCallMetricDto;
import com.zhongbo.mindos.assistant.common.dsl.SkillDSL;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ApiKeyLlmClient implements LlmClient {

    private final String provider;
    private final String routingMode;
    private final String endpoint;
    private final String globalApiKey;
    private final Map<String, String> providerEndpoints;
    private final Map<String, String> providerApiKeys;
    private final Map<String, String> stageProviderMap;
    private final Map<String, String> presetProviderMap;
    private final Map<String, String> userApiKeys;
    private final UserApiKeyService userApiKeyService;
    private final LlmMetricsService llmMetricsService;
    private final int maxRetries;
    private final long retryDelayMs;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApiKeyLlmClient(@Value("${mindos.llm.provider:openai}") String provider,
                           @Value("${mindos.llm.routing.mode:fixed}") String routingMode,
                           @Value("${mindos.llm.endpoint:https://api.example.com/v1/chat/completions}") String endpoint,
                           @Value("${mindos.llm.api-key:}") String globalApiKey,
                           @Value("${mindos.llm.provider-endpoints:}") String providerEndpoints,
                           @Value("${mindos.llm.provider-keys:}") String providerKeys,
                           @Value("${mindos.llm.routing.stage-map:llm-dsl:openai,llm-fallback:openai}") String stageProviderMap,
                           @Value("${mindos.llm.routing.preset-map:cost:openai,balanced:openai,quality:openai}") String presetProviderMap,
                           @Value("${mindos.llm.user-keys:}") String userKeys,
                           @Value("${mindos.llm.retry.max-attempts:3}") int maxRetries,
                           @Value("${mindos.llm.retry.delay-ms:300}") long retryDelayMs,
                           UserApiKeyService userApiKeyService,
                           LlmMetricsService llmMetricsService) {
        this.provider = provider;
        this.routingMode = routingMode == null ? "fixed" : routingMode.trim().toLowerCase(Locale.ROOT);
        this.endpoint = endpoint;
        this.globalApiKey = globalApiKey;
        this.providerEndpoints = parseProviderConfig(providerEndpoints);
        this.providerApiKeys = parseProviderConfig(providerKeys);
        this.stageProviderMap = parseProviderConfig(stageProviderMap);
        this.presetProviderMap = parseProviderConfig(presetProviderMap);
        this.userApiKeys = parseUserKeys(userKeys);
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(0L, retryDelayMs);
        this.userApiKeyService = userApiKeyService;
        this.llmMetricsService = llmMetricsService;
    }

    @Override
    public String generateResponse(String prompt, Map<String, Object> context) {
        Instant startedAt = Instant.now();
        Map<String, Object> safeContext = context == null ? Map.of() : context;
        if (prompt == null || prompt.isBlank()) {
            String output = "[LLM error] Prompt cannot be empty.";
            recordMetric(startedAt, safeContext, null, null, false, false, prompt, output, "empty_prompt");
            return output;
        }

        String selectedProvider = resolveProvider(safeContext);
        String selectedEndpoint = resolveEndpoint(selectedProvider);
        String apiKey = resolveApiKey(safeContext, selectedProvider);
        if (apiKey == null || apiKey.isBlank()) {
            String output = "[LLM stub] No API key resolved. Configure mindos.llm.api-key, mindos.llm.provider-keys, or mindos.llm.user-keys.";
            recordMetric(startedAt, safeContext, selectedProvider, selectedEndpoint, false, false, prompt, output, "missing_api_key");
            return output;
        }

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String output = callProvider(selectedProvider, selectedEndpoint, prompt, safeContext, apiKey);
                recordMetric(startedAt, safeContext, selectedProvider, selectedEndpoint, true, attempt > 1, prompt, output, null);
                return output;
            } catch (RuntimeException ex) {
                lastError = ex;
                if (attempt < maxRetries) {
                    sleepBeforeRetry();
                }
            }
        }

        String reason = lastError == null ? "unknown" : lastError.getMessage();
        String output = "[LLM error] Failed after " + maxRetries + " attempt(s): " + reason;
        recordMetric(startedAt, safeContext, selectedProvider, selectedEndpoint, false, maxRetries > 1, prompt, output,
                lastError == null ? "runtime_error" : simplifyErrorType(lastError));
        return output;
    }

    @Override
    public SkillDSL parseSkillCall(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return null;
        }

        String trimmed = userInput.trim();
        if (trimmed.startsWith("{")) {
            try {
                Map<String, Object> payload = objectMapper.readValue(trimmed, new TypeReference<>() {
                });
                Object skill = payload.get("skill");
                if (!(skill instanceof String skillName) || skillName.isBlank()) {
                    return null;
                }
                Map<String, Object> input = asObjectMap(payload.get("input"));
                Map<String, Object> metadata = asObjectMap(payload.get("metadata"));
                Map<String, Object> context = asObjectMap(payload.get("context"));
                return new SkillDSL(skillName, input, List.of(), metadata, context);
            } catch (JsonProcessingException ignored) {
                return null;
            }
        }

        if (trimmed.startsWith("skill:")) {
            String[] tokens = trimmed.split("\\s+");
            if (tokens.length == 0) {
                return null;
            }
            String skillName = tokens[0].substring("skill:".length()).trim();
            if (skillName.isBlank()) {
                return null;
            }

            Map<String, Object> input = new LinkedHashMap<>();
            for (int i = 1; i < tokens.length; i++) {
                int separator = tokens[i].indexOf('=');
                if (separator > 0 && separator < tokens[i].length() - 1) {
                    input.put(tokens[i].substring(0, separator), tokens[i].substring(separator + 1));
                }
            }
            return new SkillDSL(skillName, input, List.of(), Map.of(), Map.of());
        }

        return null;
    }

    private String callProvider(String providerName,
                                String endpointValue,
                                String prompt,
                                Map<String, Object> context,
                                String apiKey) {
        String normalized = normalizeProvider(providerName);
        if (normalized.contains("openai")) {
            return "[LLM openai] skeleton call to " + endpointValue + " with apiKey=" + mask(apiKey) + ": " + prompt;
        }
        if (normalized.contains("local") || normalized.contains("llama") || normalized.contains("mpt")) {
            return "[LLM local] skeleton call to " + endpointValue + " with apiKey=" + mask(apiKey) + ": " + prompt;
        }

        String userId = context == null ? "unknown" : String.valueOf(context.getOrDefault("userId", "unknown"));
        return "[LLM " + providerName + "] skeleton response for user " + userId + ": " + prompt;
    }

    private String resolveApiKey(Map<String, Object> context, String providerName) {
        if (context != null) {
            Object explicitKey = context.get("apiKey");
            if (explicitKey instanceof String key && !key.isBlank()) {
                return key;
            }

            Object userId = context.get("userId");
            if (userId != null) {
                String userIdValue = String.valueOf(userId);
                String dbKey = userApiKeyService.resolveDecryptedApiKey(userIdValue).orElse(null);
                if (dbKey != null && !dbKey.isBlank()) {
                    return dbKey;
                }

                String perUser = userApiKeys.get(userIdValue);
                if (perUser != null && !perUser.isBlank()) {
                    return perUser;
                }
            }
        }

        String providerKey = providerApiKeys.get(normalizeProvider(providerName));
        if (providerKey != null && !providerKey.isBlank()) {
            return providerKey;
        }

        return globalApiKey;
    }

    private String resolveProvider(Map<String, Object> context) {
        if (context != null) {
            Object requestedProvider = context.get("llmProvider");
            if (requestedProvider instanceof String providerValue && !providerValue.isBlank()) {
                String explicit = providerValue.trim();
                if (!"auto".equalsIgnoreCase(explicit)) {
                    return explicit;
                }
            }
            Object requestedPreset = context.get("llmPreset");
            if (requestedPreset instanceof String presetValue && !presetValue.isBlank()) {
                String mapped = presetProviderMap.get(presetValue.trim().toLowerCase(Locale.ROOT));
                if (mapped != null && !mapped.isBlank()) {
                    return mapped;
                }
            }
            if ("auto".equals(routingMode)) {
                Object routeStage = context.get("routeStage");
                if (routeStage instanceof String stage && !stage.isBlank()) {
                    String mapped = stageProviderMap.get(stage.trim().toLowerCase(Locale.ROOT));
                    if (mapped != null && !mapped.isBlank()) {
                        return mapped;
                    }
                }
            }
        }
        return provider;
    }

    private String resolveEndpoint(String providerName) {
        String configured = providerEndpoints.get(normalizeProvider(providerName));
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return endpoint;
    }

    private Map<String, String> parseUserKeys(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }

        // Format: userA:keyA,userB:keyB
        Map<String, String> parsed = new LinkedHashMap<>();
        String[] entries = raw.split(",");
        for (String entry : entries) {
            int separator = entry.indexOf(':');
            if (separator > 0 && separator < entry.length() - 1) {
                String userId = entry.substring(0, separator).trim();
                String key = entry.substring(separator + 1).trim();
                if (!userId.isBlank() && !key.isBlank()) {
                    parsed.put(userId, key);
                }
            }
        }
        return Map.copyOf(parsed);
    }

    private Map<String, String> parseProviderConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }

        // Format: providerA:valueA,providerB:valueB
        Map<String, String> parsed = new LinkedHashMap<>();
        String[] entries = raw.split(",");
        for (String entry : entries) {
            int separator = entry.indexOf(':');
            if (separator > 0 && separator < entry.length() - 1) {
                String providerName = normalizeProvider(entry.substring(0, separator));
                String value = entry.substring(separator + 1).trim();
                if (!providerName.isBlank() && !value.isBlank()) {
                    parsed.put(providerName, value);
                }
            }
        }
        return Map.copyOf(parsed);
    }

    private String normalizeProvider(String value) {
        if (value == null || value.isBlank()) {
            return "openai";
        }
        return value.trim().toLowerCase();
    }

    private void recordMetric(Instant startedAt,
                              Map<String, Object> context,
                              String providerName,
                              String endpointValue,
                              boolean success,
                              boolean retried,
                              String prompt,
                              String output,
                              String errorType) {
        long latencyMs = Math.max(0L, java.time.Duration.between(startedAt, Instant.now()).toMillis());
        String providerValue = providerName == null || providerName.isBlank() ? resolveProvider(context) : providerName;
        String endpointResolved = endpointValue == null || endpointValue.isBlank()
                ? resolveEndpoint(providerValue)
                : endpointValue;
        String routeStage = context == null ? null : asText(context.get("routeStage"));
        String userId = context == null ? null : asText(context.get("userId"));
        int promptTokens = estimateTokens(prompt);
        int responseTokens = estimateTokens(output);

        llmMetricsService.record(new LlmCallMetricDto(
                Instant.now(),
                userId,
                normalizeProvider(providerValue),
                endpointResolved,
                routeStage,
                success,
                retried,
                latencyMs,
                promptTokens,
                responseTokens,
                promptTokens + responseTokens,
                errorType
        ));
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        String normalized = text.trim();
        int approxByChars = (int) Math.ceil(normalized.length() / 4.0);
        int approxByWords = normalized.split("\\s+").length;
        return Math.max(1, Math.max(approxByChars, approxByWords));
    }

    private String simplifyErrorType(RuntimeException ex) {
        String simple = ex.getClass().getSimpleName();
        if (simple == null || simple.isBlank()) {
            return "runtime_error";
        }
        return simple.toLowerCase(Locale.ROOT);
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? null : normalized;
    }

    private Map<String, Object> asObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        rawMap.forEach((key, item) -> mapped.put(String.valueOf(key), item));
        return Map.copyOf(mapped);
    }

    private void sleepBeforeRetry() {
        if (retryDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(retryDelayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private String mask(String apiKey) {
        if (apiKey == null || apiKey.length() < 6) {
            return "***";
        }
        return apiKey.substring(0, 3) + "***" + apiKey.substring(apiKey.length() - 2);
    }
}
