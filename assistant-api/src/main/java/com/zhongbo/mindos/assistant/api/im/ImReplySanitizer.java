package com.zhongbo.mindos.assistant.api.im;

import java.util.ArrayList;
import java.util.List;

final class ImReplySanitizer {

    static final String FRIENDLY_IM_FALLBACK_REPLY = "抱歉，我这边的智能回复暂时有点忙。你可以稍后再试，或换一种更明确的说法发我，我继续帮你。";
    static final String BLANK_IM_FALLBACK_REPLY = "我刚刚没有成功生成回复，请稍后再试。";

    private ImReplySanitizer() {
    }

    static String sanitize(String reply) {
        return inspect(reply).sanitizedReply();
    }

    static Decision inspect(String reply) {
        if (reply == null || reply.isBlank()) {
            return new Decision(reply, BLANK_IM_FALLBACK_REPLY, "blank", List.of("blank_reply"), true);
        }
        String normalized = reply.trim();
        List<String> reasons = collectReasons(normalized);
        if (!reasons.isEmpty()) {
            return new Decision(reply, FRIENDLY_IM_FALLBACK_REPLY, "friendly", List.copyOf(reasons), true);
        }
        return new Decision(reply, normalized, "none", List.of(), false);
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

    record Decision(String originalReply,
                    String sanitizedReply,
                    String fallbackKind,
                    List<String> reasons,
                    boolean sanitized) {
    }
}

