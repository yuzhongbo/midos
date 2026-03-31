package com.zhongbo.mindos.assistant.api.im;

import com.zhongbo.mindos.assistant.common.ImDegradedReplyMarker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ImReplySanitizer {

    static final String FRIENDLY_IM_FALLBACK_REPLY = "抱歉，我这边的智能回复暂时有点忙。你可以稍后再试，或换一种更明确的说法发我，我继续帮你。";
    static final String BLANK_IM_FALLBACK_REPLY = "我刚刚没有成功生成回复，请稍后再试。";
    static final String TIMEOUT_IM_FALLBACK_REPLY = "我刚刚连接模型服务超时了，请稍后再试，或换个更短的问题。";
    static final String AUTH_IM_FALLBACK_REPLY = "我这边的模型服务配置暂时有点问题，请稍后再试。";
    static final String UPSTREAM_IM_FALLBACK_REPLY = "我刚刚连接模型服务时出了点问题，请稍后再试。";
    static final String EMPTY_RESPONSE_IM_FALLBACK_REPLY = "我刚刚没有成功生成完整回复，请稍后再试，或换个更明确的问题。";
    private static final Pattern LLM_PROVIDER_PATTERN = Pattern.compile("^\\[LLM\\s+([^\\]]+)]", Pattern.CASE_INSENSITIVE);

    private ImReplySanitizer() {
    }

    static String sanitize(String reply) {
        return inspect(reply).sanitizedReply();
    }

    static Decision inspect(String reply) {
        if (reply == null || reply.isBlank()) {
            return new Decision(reply, BLANK_IM_FALLBACK_REPLY, "blank", List.of("blank_reply"), true, "unknown", "blank_reply");
        }
        String normalized = reply.trim();
        var marker = ImDegradedReplyMarker.parse(normalized).orElse(null);
        if (marker != null) {
            String errorCategory = normalizeCategory(marker.errorCategory());
            return new Decision(
                    reply,
                    friendlyReplyFor(errorCategory),
                    "friendly",
                    List.of("im_degraded_marker", errorCategory),
                    true,
                    marker.provider(),
                    errorCategory
            );
        }
        List<String> reasons = collectReasons(normalized);
        if (!reasons.isEmpty()) {
            String errorCategory = inferLegacyErrorCategory(normalized, reasons);
            return new Decision(
                    reply,
                    friendlyReplyFor(errorCategory),
                    "friendly",
                    List.copyOf(reasons),
                    true,
                    extractLegacyProvider(normalized),
                    errorCategory
            );
        }
        return new Decision(reply, normalized, "none", List.of(), false, "unknown", "none");
    }

    private static List<String> collectReasons(String reply) {
        String normalized = reply.toLowerCase();
        List<String> reasons = new ArrayList<>();
        if (normalized.startsWith("[llm")) {
            reasons.add("llm_marker");
        }
        if (normalized.contains("fallback mode active")) {
            reasons.add("llm_fallback_mode");
        }
        if (normalized.contains("stub response") || normalized.contains("stub mode")) {
            reasons.add("stub_mode");
        }
        if (normalized.contains("skeleton response") || normalized.contains("skeleton call")) {
            reasons.add("skeleton_mode");
        }
        if (normalized.contains("missing api key") || normalized.contains("no api key resolved")) {
            reasons.add("missing_api_key");
        }
        if (normalized.contains("request failed after")) {
            reasons.add("request_failure");
        }
        if (normalized.contains("http_call_failed") || normalized.contains("http_")) {
            reasons.add("http_failure");
        }
        if (normalized.contains("invalid_response_json")
                || normalized.contains("empty_response_content")
                || normalized.contains("empty endpoint")) {
            reasons.add("invalid_model_response");
        }
        if (reply.contains("Answer naturally using the context when helpful.")) {
            reasons.add("prompt_template_leak");
        }
        if (reply.contains("Recent conversation:")) {
            reasons.add("recent_conversation_leak");
        }
        if (reply.contains("Relevant knowledge:")) {
            reasons.add("knowledge_context_leak");
        }
        if (reply.contains("User skill habits:")) {
            reasons.add("skill_habits_leak");
        }
        if (reply.contains("\nUser input: ") || reply.startsWith("User input: ")) {
            reasons.add("user_input_echo_leak");
        }
        if (reply.contains("memoryContext")) {
            reasons.add("memory_context_leak");
        }
        if (reply.contains("imSenderId") || reply.contains("imChatId")) {
            reasons.add("im_context_leak");
        }
        return reasons;
    }

    private static String friendlyReplyFor(String errorCategory) {
        return switch (normalizeCategory(errorCategory)) {
            case "timeout" -> TIMEOUT_IM_FALLBACK_REPLY;
            case "auth_failure" -> AUTH_IM_FALLBACK_REPLY;
            case "upstream_5xx" -> UPSTREAM_IM_FALLBACK_REPLY;
            case "empty_response" -> EMPTY_RESPONSE_IM_FALLBACK_REPLY;
            default -> FRIENDLY_IM_FALLBACK_REPLY;
        };
    }

    private static String inferLegacyErrorCategory(String reply, List<String> reasons) {
        String normalized = reply.toLowerCase(Locale.ROOT);
        if (reasons.contains("missing_api_key") || normalized.contains("http_401") || normalized.contains("http_403")) {
            return "auth_failure";
        }
        if (normalized.contains("timeout") || normalized.contains("timed out") || normalized.contains("http_408") || normalized.contains("http_504")) {
            return "timeout";
        }
        if (normalized.contains("http_500") || normalized.contains("http_502") || normalized.contains("http_503")
                || normalized.contains("http_501") || normalized.contains("http_505")) {
            return "upstream_5xx";
        }
        if (reasons.contains("invalid_model_response")) {
            return "empty_response";
        }
        return "unavailable";
    }

    private static String extractLegacyProvider(String reply) {
        Matcher matcher = LLM_PROVIDER_PATTERN.matcher(reply);
        if (!matcher.find()) {
            return "unknown";
        }
        String provider = matcher.group(1);
        return provider == null || provider.isBlank() ? "unknown" : provider.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeCategory(String value) {
        if (value == null || value.isBlank()) {
            return "unavailable";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    record Decision(String originalReply,
                    String sanitizedReply,
                    String fallbackKind,
                    List<String> reasons,
                    boolean sanitized,
                    String provider,
                    String errorCategory) {
    }
}

