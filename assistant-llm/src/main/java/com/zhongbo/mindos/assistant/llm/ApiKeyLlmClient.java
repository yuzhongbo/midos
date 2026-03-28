package com.zhongbo.mindos.assistant.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.common.LlmCacheMetricsReader;
import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.dto.LlmCallMetricDto;
import com.zhongbo.mindos.assistant.common.dto.LlmCacheMetricsDto;
import com.zhongbo.mindos.assistant.common.dto.LlmCacheWindowMetricsDto;
import com.zhongbo.mindos.assistant.common.dsl.SkillDSL;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ApiKeyLlmClient implements LlmClient, LlmCacheMetricsReader {

    private static final Map<String, String> PROVIDER_ALIASES = Map.ofEntries(
            Map.entry("qwen", "qwen"),
            Map.entry("tongyi", "qwen"),
            Map.entry("dashscope", "qwen"),
            Map.entry("deepseek", "deepseek"),
            Map.entry("kimi", "kimi"),
            Map.entry("moonshot", "kimi"),
            Map.entry("doubao", "doubao"),
            Map.entry("volcengine", "doubao"),
            Map.entry("hunyuan", "hunyuan"),
            Map.entry("tencent", "hunyuan"),
            Map.entry("ernie", "ernie"),
            Map.entry("baidu", "ernie"),
            Map.entry("glm", "glm"),
            Map.entry("zhipu", "glm")
    );

    private static final Map<String, String> BUILTIN_PROVIDER_ENDPOINTS = Map.ofEntries(
            Map.entry("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"),
            Map.entry("deepseek", "https://api.deepseek.com/v1/chat/completions"),
            Map.entry("kimi", "https://api.moonshot.cn/v1/chat/completions"),
            Map.entry("doubao", "https://ark.cn-beijing.volces.com/api/v3/chat/completions"),
            Map.entry("hunyuan", "https://api.hunyuan.cloud.tencent.com/v1/chat/completions"),
            Map.entry("ernie", "https://qianfan.baidubce.com/v2/chat/completions"),
            Map.entry("glm", "https://open.bigmodel.cn/api/paas/v4/chat/completions")
    );
    private static final String IM_FRIENDLY_DEGRADED_REPLY = "抱歉，我这边的智能回复暂时有点忙。你可以稍后再试，或换一种更明确的说法发我，我继续帮你。";

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
    private final boolean httpEnabled;
    private final boolean responseCacheEnabled;
    private final long responseCacheTtlMillis;
    private final int responseCacheMaxEntries;
    private final ConcurrentHashMap<String, CachedResponse> responseCache = new ConcurrentHashMap<>();
    private final AtomicLong cacheHitCount = new AtomicLong();
    private final AtomicLong cacheMissCount = new AtomicLong();
    private final Object cacheAccessWindowLock = new Object();
    private final ArrayDeque<CacheAccessEvent> cacheAccessEvents = new ArrayDeque<>();
    private static final int MAX_CACHE_ACCESS_EVENTS = 10_000;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

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
                           @Value("${mindos.llm.http.enabled:false}") boolean httpEnabled,
                           @Value("${mindos.llm.cache.enabled:false}") boolean responseCacheEnabled,
                           @Value("${mindos.llm.cache.ttl-seconds:60}") long responseCacheTtlSeconds,
                           @Value("${mindos.llm.cache.max-entries:256}") int responseCacheMaxEntries,
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
        this.httpEnabled = httpEnabled;
        this.responseCacheEnabled = responseCacheEnabled;
        this.responseCacheTtlMillis = Math.max(1_000L, responseCacheTtlSeconds * 1_000L);
        this.responseCacheMaxEntries = Math.max(16, responseCacheMaxEntries);
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
            String output = buildMissingApiKeyReply(safeContext, selectedProvider);
            recordMetric(startedAt, safeContext, selectedProvider, selectedEndpoint, false, false, prompt, output, "missing_api_key");
            return output;
        }

        String cacheKey = buildCacheKey(prompt, safeContext, selectedProvider, selectedEndpoint);
        String cached = getCachedResponse(cacheKey);
        if (cached != null) {
            recordMetric(startedAt, safeContext, selectedProvider, selectedEndpoint, true, false, prompt, cached, null);
            return cached;
        }

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String output = callProvider(selectedProvider, selectedEndpoint, prompt, safeContext, apiKey);
                putCachedResponse(cacheKey, output);
                recordMetric(startedAt, safeContext, selectedProvider, selectedEndpoint, true, attempt > 1, prompt, output, null);
                return output;
            } catch (RuntimeException ex) {
                lastError = ex;
                if (attempt < maxRetries) {
                    sleepBeforeRetry();
                }
            }
        }

        String output = buildRequestFailureReply(safeContext, selectedProvider, maxRetries);
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

    @Override
    public LlmCacheMetricsDto snapshotCacheMetrics() {
        long hits = cacheHitCount.get();
        long misses = cacheMissCount.get();
        long total = hits + misses;
        double hitRate = total == 0 ? 0.0 : (double) hits / total;
        return new LlmCacheMetricsDto(
                responseCacheEnabled,
                hits,
                misses,
                hitRate,
                responseCache.size(),
                Math.max(1L, responseCacheTtlMillis / 1_000L),
                responseCacheMaxEntries
        );
    }

    @Override
    public double snapshotWindowCacheHitRate(int windowMinutes) {
        return snapshotWindowCacheMetrics(windowMinutes).hitRate();
    }

    @Override
    public LlmCacheWindowMetricsDto snapshotWindowCacheMetrics(int windowMinutes) {
        int effectiveWindowMinutes = Math.max(1, windowMinutes);
        long cutoffEpochMillis = System.currentTimeMillis() - (effectiveWindowMinutes * 60_000L);
        long windowHits = 0L;
        long windowMisses = 0L;
        synchronized (cacheAccessWindowLock) {
            while (!cacheAccessEvents.isEmpty() && cacheAccessEvents.peekFirst().epochMillis() < cutoffEpochMillis) {
                cacheAccessEvents.pollFirst();
            }
            for (CacheAccessEvent event : cacheAccessEvents) {
                if (event.hit()) {
                    windowHits++;
                } else {
                    windowMisses++;
                }
            }
        }
        long total = windowHits + windowMisses;
        double hitRate = total == 0 ? 0.0 : (double) windowHits / total;
        return new LlmCacheWindowMetricsDto(windowHits, windowMisses, hitRate);
    }

    private String callProvider(String providerName,
                                String endpointValue,
                                String prompt,
                                Map<String, Object> context,
                                String apiKey) {
        String normalized = normalizeProvider(providerName);
        if (httpEnabled && isOpenAiCompatibleProvider(normalized, endpointValue)) {
            return callOpenAiCompatibleProvider(normalized, endpointValue, prompt, context, apiKey);
        }
        if (normalized.contains("openai")
                || "deepseek".equals(normalized)
                || "qwen".equals(normalized)
                || "kimi".equals(normalized)
                || "doubao".equals(normalized)
                || "hunyuan".equals(normalized)
                || "ernie".equals(normalized)
                || "glm".equals(normalized)
                || "gemini".equals(normalized)
                || "grok".equals(normalized)) {
            return buildSkeletonReply(context, normalized, endpointValue, apiKey);
        }
        if (normalized.contains("local") || normalized.contains("llama") || normalized.contains("mpt")) {
            return buildSkeletonReply(context, "local", endpointValue, apiKey);
        }

        return buildSkeletonReply(context, providerName, endpointValue, apiKey);
    }

    private boolean isOpenAiCompatibleProvider(String normalizedProvider, String endpointValue) {
        if (normalizedProvider.contains("local") || normalizedProvider.contains("llama") || normalizedProvider.contains("mpt")) {
            return false;
        }
        if (normalizedProvider.contains("openai")
                || "deepseek".equals(normalizedProvider)
                || "qwen".equals(normalizedProvider)
                || "kimi".equals(normalizedProvider)
                || "doubao".equals(normalizedProvider)
                || "hunyuan".equals(normalizedProvider)
                || "ernie".equals(normalizedProvider)
                || "glm".equals(normalizedProvider)
                || "gemini".equals(normalizedProvider)
                || "grok".equals(normalizedProvider)) {
            return true;
        }
        return endpointValue != null && endpointValue.contains("/chat/completions");
    }

    private String callOpenAiCompatibleProvider(String normalizedProvider,
                                                String endpointValue,
                                                String prompt,
                                                Map<String, Object> context,
                                                String apiKey) {
        String endpointText = endpointValue == null ? "" : endpointValue.trim();
        if (endpointText.isBlank()) {
            throw new RuntimeException("empty endpoint");
        }
        try {
            Map<String, Object> requestPayload = new LinkedHashMap<>();
            requestPayload.put("model", resolveModel(context, normalizedProvider));
            requestPayload.put("messages", List.of(Map.of("role", "user", "content", prompt)));

            Double temperature = resolveTemperature(context);
            if (temperature != null) {
                requestPayload.put("temperature", temperature);
            }
            Integer maxTokens = resolveMaxTokens(context);
            if (maxTokens != null) {
                requestPayload.put("max_tokens", maxTokens);
            }

            String requestBody = objectMapper.writeValueAsString(requestPayload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpointText))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            if (status < 200 || status >= 300) {
                throw new RuntimeException("http_" + status + ": " + abbreviate(body, 300));
            }

            String content = extractAssistantText(body);
            if (content == null || content.isBlank()) {
                throw new RuntimeException("empty_response_content");
            }
            return content;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("http_call_failed: " + ex.getMessage(), ex);
        }
    }

    private String extractAssistantText(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody == null ? "" : responseBody);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (contentNode.isTextual()) {
                return contentNode.asText("").trim();
            }
            if (contentNode.isArray()) {
                StringBuilder builder = new StringBuilder();
                for (JsonNode item : contentNode) {
                    String text = item.path("text").asText("");
                    if (!text.isBlank()) {
                        if (!builder.isEmpty()) {
                            builder.append('\n');
                        }
                        builder.append(text.trim());
                    }
                }
                if (!builder.isEmpty()) {
                    return builder.toString();
                }
            }
            String outputText = root.path("output_text").asText("").trim();
            if (!outputText.isBlank()) {
                return outputText;
            }
            return null;
        } catch (Exception ex) {
            throw new RuntimeException("invalid_response_json", ex);
        }
    }

    private String resolveModel(Map<String, Object> context, String normalizedProvider) {
        if (context != null) {
            String model = asText(context.get("model"));
            if (model != null) {
                return model;
            }
        }
        return normalizedProvider;
    }

    private Double resolveTemperature(Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object raw = context.get("temperature");
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer resolveMaxTokens(Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object raw = context.get("maxTokens");
        if (raw == null) {
            raw = context.get("max_tokens");
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength) + "...";
    }

    private String buildMissingApiKeyReply(Map<String, Object> context, String providerName) {
        if (isImContext(context)) {
            return IM_FRIENDLY_DEGRADED_REPLY;
        }
        return "[LLM " + normalizeProvider(providerName) + "] unavailable: missing API key.";
    }

    private String buildRequestFailureReply(Map<String, Object> context, String providerName, int attempts) {
        if (isImContext(context)) {
            return IM_FRIENDLY_DEGRADED_REPLY;
        }
        return "[LLM " + normalizeProvider(providerName) + "] request failed after " + Math.max(1, attempts)
                + " attempt(s). Please retry later.";
    }

    private String buildSkeletonReply(Map<String, Object> context,
                                      String providerName,
                                      String endpointValue,
                                      String apiKey) {
        if (isImContext(context)) {
            return IM_FRIENDLY_DEGRADED_REPLY;
        }
        String normalizedProvider = normalizeProvider(providerName);
        String resolvedEndpoint = endpointValue == null || endpointValue.isBlank() ? "n/a" : endpointValue;
        return "[LLM " + normalizedProvider + "] fallback mode active. endpoint=" + resolvedEndpoint
                + ", apiKey=" + mask(apiKey);
    }

    private boolean isImContext(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return false;
        }
        String interactionChannel = asText(context.get("interactionChannel"));
        if ("im".equalsIgnoreCase(interactionChannel)) {
            return true;
        }
        if (asText(context.get("imPlatform")) != null
                || asText(context.get("imSenderId")) != null
                || asText(context.get("imChatId")) != null) {
            return true;
        }
        Map<String, Object> profile = asObjectMap(context.get("profile"));
        return asText(profile.get("imPlatform")) != null
                || asText(profile.get("imSenderId")) != null
                || asText(profile.get("imChatId")) != null;
    }

    private String resolveInteractionCacheKey(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return "default";
        }
        if (isImContext(context)) {
            String imPlatform = asText(context.get("imPlatform"));
            if (imPlatform == null) {
                imPlatform = asText(asObjectMap(context.get("profile")).get("imPlatform"));
            }
            return "im:" + (imPlatform == null ? "unknown" : imPlatform.toLowerCase(Locale.ROOT));
        }
        String interactionChannel = asText(context.get("interactionChannel"));
        return interactionChannel == null ? "default" : interactionChannel.toLowerCase(Locale.ROOT);
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
        String builtin = BUILTIN_PROVIDER_ENDPOINTS.get(normalizeProvider(providerName));
        if (builtin != null && !builtin.isBlank()) {
            return builtin;
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
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return PROVIDER_ALIASES.getOrDefault(normalized, normalized);
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
        if (simple.isBlank()) {
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

    private String buildCacheKey(String prompt,
                                 Map<String, Object> context,
                                 String providerName,
                                 String endpointValue) {
        if (!responseCacheEnabled) {
            return null;
        }
        String userId = asText(context.get("userId"));
        String routeStage = asText(context.get("routeStage"));
        String profileProvider = asText(context.get("llmProvider"));
        String profilePreset = asText(context.get("llmPreset"));
        String interactionCacheKey = resolveInteractionCacheKey(context);
        return String.join("\u001F",
                normalizeProvider(providerName),
                endpointValue == null ? "" : endpointValue,
                userId == null ? "" : userId,
                routeStage == null ? "" : routeStage,
                profileProvider == null ? "" : profileProvider,
                profilePreset == null ? "" : profilePreset,
                interactionCacheKey,
                prompt == null ? "" : prompt);
    }

    private String getCachedResponse(String cacheKey) {
        if (!responseCacheEnabled || cacheKey == null || cacheKey.isBlank()) {
            return null;
        }
        CachedResponse cached = responseCache.get(cacheKey);
        if (cached == null) {
            cacheMissCount.incrementAndGet();
            recordCacheAccess(false);
            return null;
        }
        if ((System.currentTimeMillis() - cached.createdAtEpochMillis()) > responseCacheTtlMillis) {
            responseCache.remove(cacheKey, cached);
            cacheMissCount.incrementAndGet();
            recordCacheAccess(false);
            return null;
        }
        cacheHitCount.incrementAndGet();
        recordCacheAccess(true);
        return cached.output();
    }

    private void putCachedResponse(String cacheKey, String output) {
        if (!responseCacheEnabled || cacheKey == null || cacheKey.isBlank() || output == null || output.isBlank()) {
            return;
        }
        responseCache.put(cacheKey, new CachedResponse(output, System.currentTimeMillis()));
        evictCacheIfNeeded();
    }

    private void evictCacheIfNeeded() {
        if (responseCache.size() <= responseCacheMaxEntries) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, CachedResponse> entry : responseCache.entrySet()) {
            if ((now - entry.getValue().createdAtEpochMillis()) > responseCacheTtlMillis) {
                responseCache.remove(entry.getKey(), entry.getValue());
            }
        }
        if (responseCache.size() <= responseCacheMaxEntries) {
            return;
        }
        List<Map.Entry<String, CachedResponse>> snapshot = new ArrayList<>(responseCache.entrySet());
        snapshot.sort(Comparator.comparingLong(entry -> entry.getValue().createdAtEpochMillis()));
        int overflow = responseCache.size() - responseCacheMaxEntries;
        for (int i = 0; i < overflow && i < snapshot.size(); i++) {
            Map.Entry<String, CachedResponse> candidate = snapshot.get(i);
            responseCache.remove(candidate.getKey(), candidate.getValue());
        }
    }

    private record CachedResponse(String output, long createdAtEpochMillis) {
    }

    private void recordCacheAccess(boolean hit) {
        synchronized (cacheAccessWindowLock) {
            cacheAccessEvents.addLast(new CacheAccessEvent(System.currentTimeMillis(), hit));
            while (cacheAccessEvents.size() > MAX_CACHE_ACCESS_EVENTS) {
                cacheAccessEvents.pollFirst();
            }
        }
    }

    private record CacheAccessEvent(long epochMillis, boolean hit) {
    }
}
