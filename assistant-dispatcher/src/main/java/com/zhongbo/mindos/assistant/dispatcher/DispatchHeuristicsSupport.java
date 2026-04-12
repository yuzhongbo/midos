package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.RoutingDecisionDto;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class DispatchHeuristicsSupport {

    private static final Set<String> SMALL_TALK_INPUTS = Set.of(
            "hi", "hello", "hey", "thanks", "thank you", "ok", "okay", "got it", "roger",
            "你好", "您好", "嗨", "谢谢", "多谢", "收到", "好的", "好", "嗯", "嗯嗯", "晚安", "早上好"
    );

    private final DispatchMemoryLifecycle dispatchMemoryLifecycle;
    private final boolean promptInjectionGuardEnabled;
    private final List<String> promptInjectionRiskTerms;
    private final boolean semanticAnalysisSkipShortSimpleEnabled;
    private final boolean realtimeIntentBypassEnabled;
    private final Set<String> realtimeIntentTerms;

    DispatchHeuristicsSupport(DispatchMemoryLifecycle dispatchMemoryLifecycle,
                              boolean promptInjectionGuardEnabled,
                              List<String> promptInjectionRiskTerms,
                              boolean semanticAnalysisSkipShortSimpleEnabled,
                              boolean realtimeIntentBypassEnabled,
                              Set<String> realtimeIntentTerms) {
        this.dispatchMemoryLifecycle = dispatchMemoryLifecycle;
        this.promptInjectionGuardEnabled = promptInjectionGuardEnabled;
        this.promptInjectionRiskTerms = promptInjectionRiskTerms == null ? List.of() : List.copyOf(promptInjectionRiskTerms);
        this.semanticAnalysisSkipShortSimpleEnabled = semanticAnalysisSkipShortSimpleEnabled;
        this.realtimeIntentBypassEnabled = realtimeIntentBypassEnabled;
        this.realtimeIntentTerms = realtimeIntentTerms == null ? Set.of() : Set.copyOf(realtimeIntentTerms);
    }

    static List<String> parseRiskTerms(String rawTerms) {
        if (rawTerms == null || rawTerms.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rawTerms.split(","))
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .toList();
    }

    String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    boolean isConversationalBypassInput(String normalizedInput) {
        if (normalizedInput == null || normalizedInput.isBlank()) {
            return true;
        }
        if (SMALL_TALK_INPUTS.contains(normalizedInput)) {
            return true;
        }
        return normalizedInput.length() <= 12
                && (normalizedInput.startsWith("谢谢")
                || normalizedInput.startsWith("收到")
                || normalizedInput.startsWith("好的")
                || normalizedInput.startsWith("hello")
                || normalizedInput.startsWith("thanks"));
    }

    boolean shouldSkipSemanticAnalysis(String userInput) {
        if (!semanticAnalysisSkipShortSimpleEnabled) {
            return false;
        }
        return isConversationalBypassInput(normalize(userInput));
    }

    DispatchResult handleConversationalBypass(String userId, String normalizedInput) {
        String reply = "";
        if (normalizedInput == null || normalizedInput.isBlank()) {
            reply = "";
        } else if (normalizedInput.startsWith("谢谢") || normalizedInput.startsWith("多谢") || normalizedInput.startsWith("thanks")) {
            reply = "不客气";
        } else if (normalizedInput.startsWith("收到")) {
            reply = "已收到";
        } else if (normalizedInput.startsWith("好的") || normalizedInput.equals("好") || normalizedInput.startsWith("ok") || normalizedInput.startsWith("okay")) {
            reply = "好的";
        } else if (normalizedInput.startsWith("hi") || normalizedInput.startsWith("hello") || normalizedInput.startsWith("你好")
                || normalizedInput.startsWith("嗨") || normalizedInput.startsWith("您好")) {
            reply = "你好！有什么我可以帮你的吗？";
        }
        dispatchMemoryLifecycle.recordAssistantReply(userId, reply);
        RoutingDecisionDto decision = new RoutingDecisionDto(
                "conversational-bypass",
                "conversational-bypass",
                1.0,
                List.of("short conversational input bypassed routing"),
                List.of()
        );
        ExecutionTraceDto trace = new ExecutionTraceDto("single-pass", 0, null, List.of(), decision);
        return new DispatchResult(reply == null ? "" : reply, "conversational-bypass", trace);
    }

    boolean isPromptInjectionAttempt(String userInput) {
        if (!promptInjectionGuardEnabled || userInput == null || userInput.isBlank()) {
            return false;
        }
        String normalized = normalize(userInput);
        for (String term : promptInjectionRiskTerms) {
            if (normalized.contains(term)) {
                return true;
            }
        }
        return false;
    }

    boolean isRealtimeIntent(String userInput) {
        return isRealtimeIntent(userInput, SemanticAnalysisResult.empty());
    }

    boolean isRealtimeIntent(String userInput, SemanticAnalysisResult semanticAnalysis) {
        return realtimeIntentBypassEnabled && RealtimeIntentHeuristics.isRealtimeIntent(userInput, realtimeIntentTerms, semanticAnalysis);
    }

    boolean isRealtimeLikeInput(String userInput) {
        return isRealtimeLikeInput(userInput, SemanticAnalysisResult.empty());
    }

    boolean isRealtimeLikeInput(String userInput, SemanticAnalysisResult semanticAnalysis) {
        return RealtimeIntentHeuristics.isRealtimeLikeInput(userInput, realtimeIntentTerms, semanticAnalysis);
    }
}
