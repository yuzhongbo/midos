package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.common.dto.RetrievedMemoryItemDto;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

@Component
public class LLMDecisionEngine {

    private static final Logger LOGGER = Logger.getLogger(LLMDecisionEngine.class.getName());
    // Keep dispatcher fallback memory-first and warn when the rolling ratio exceeds the 20% cost target.
    private static final double TARGET_USAGE_RATE = 0.20d;
    // Only skip the LLM when semantic/procedural memory is clearly relevant instead of marginally related.
    // Allow runtime override via system property `mindos.dispatcher.llm.relevant-memory-threshold`
    private static final double DEFAULT_RELEVANT_MEMORY_THRESHOLD = 0.55d;
    // Realtime and volatile queries should avoid stale memory answers unless the memory is very fresh.
    private static final double FRESH_MEMORY_MIN_RECENCY = 0.35d;
    private static final List<String> EXPLICIT_REQUEST_TERMS = List.of(
            "请详细分析", "请深入分析", "逐步推理", "step by step", "deep reasoning", "调用llm", "调用大模型", "请认真思考"
    );
    private static final List<String> COMPLEX_REASONING_TERMS = List.of(
            "为什么", "如何设计", "tradeoff", "权衡", "比较", "方案", "策略", "架构", "诊断", "根因", "优化", "plan"
    );
    private static final List<String> REALTIME_TERMS = List.of(
            "天气", "气温", "预报", "空气质量", "pm2.5", "雨", "雪", "温度", "新闻", "资讯", "快讯", "头条",
            "最新", "实时", "今天", "本周", "本月", "刚刚", "现在", "holiday", "weather", "forecast", "news", "latest", "today"
    );

    private final AtomicLong decisions = new AtomicLong();
    private final AtomicLong llmCalls = new AtomicLong();
    private final AtomicLong lastWarningDecision = new AtomicLong();

    private final double relevantMemoryThreshold;

    public LLMDecisionEngine() {
        double configured = DEFAULT_RELEVANT_MEMORY_THRESHOLD;
        try {
            String raw = System.getProperty("mindos.dispatcher.llm.relevant-memory-threshold");
            if (raw != null && !raw.isBlank()) {
                configured = Double.parseDouble(raw.trim());
            }
        } catch (Exception e) {
            // ignore and fall back to default
        }
        this.relevantMemoryThreshold = Math.max(0.0, Math.min(1.0, configured));
    }

    public boolean shouldCallLLM(QueryContext context) {
        QueryContext safeContext = context == null ? new QueryContext("", "", null, false, false) : context;
        boolean explicit = safeContext.explicitLlmRequest() || containsAny(safeContext.userQuery(), EXPLICIT_REQUEST_TERMS);
        boolean complex = safeContext.complexReasoningRequired() || containsAny(safeContext.userQuery(), COMPLEX_REASONING_TERMS);
        boolean realtime = requiresRealtimeAnswer(safeContext.userQuery());
        boolean hasRelevantMemory = hasRelevantMemory(safeContext.promptMemoryContext(), realtime);
        boolean shouldCall = explicit || complex || realtime || !hasRelevantMemory;

        long total = decisions.incrementAndGet();
        long calls = shouldCall ? llmCalls.incrementAndGet() : llmCalls.get();
        double usageRate = total <= 0 ? 0.0d : calls / (double) total;
        String reason = explicit
                ? "explicit-request"
                : (complex
                ? "complex-reasoning"
                : (realtime ? "realtime-query" : (hasRelevantMemory ? "memory-hit" : "memory-miss")));
        LOGGER.info("dispatcher.llm.decision userId=" + safeContext.userId()
                + " shouldCallLlm=" + shouldCall
                + " reason=" + reason
                + " usageRate=" + String.format(Locale.ROOT, "%.3f", usageRate));
        if (usageRate > TARGET_USAGE_RATE && shouldEmitTargetWarning(total)) {
            LOGGER.warning("dispatcher.llm.usage-rate targetExceeded usageRate="
                    + String.format(Locale.ROOT, "%.3f", usageRate)
                    + " target=" + String.format(Locale.ROOT, "%.2f", TARGET_USAGE_RATE));
        }
        return shouldCall;
    }

    public double usageRate() {
        long total = decisions.get();
        if (total <= 0) {
            return 0.0d;
        }
        return llmCalls.get() / (double) total;
    }

    public long decisionCount() {
        return decisions.get();
    }

    public long llmCallCount() {
        return llmCalls.get();
    }

    private boolean hasRelevantMemory(PromptMemoryContextDto promptMemoryContext, boolean preferFresh) {
        if (promptMemoryContext == null || promptMemoryContext.debugTopItems() == null) {
            return false;
        }
        return promptMemoryContext.debugTopItems().stream()
                .anyMatch(item -> isRelevantMemoryItem(item, preferFresh));
    }

    private boolean isRelevantMemoryItem(RetrievedMemoryItemDto item, boolean preferFresh) {
        if (item == null || item.type() == null || item.type().isBlank()) {
            return false;
        }
        String type = item.type().toLowerCase(Locale.ROOT);
        if ("episodic".equals(type)
                || "procedural".equals(type)
                || "semantic-routing".equals(type)
                || "semantic-summary".equals(type)) {
            return false;
        }
        if (looksLikeSummaryOrRoutingMemory(item.text())) {
            return false;
        }
        if (preferFresh && item.recencyScore() < FRESH_MEMORY_MIN_RECENCY) {
            return false;
        }
        return item.finalScore() >= this.relevantMemoryThreshold;
    }

    private boolean looksLikeSummaryOrRoutingMemory(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("semantic-summary")
                || normalized.contains("[意图摘要]".toLowerCase(Locale.ROOT))
                || normalized.contains("[助手上下文]".toLowerCase(Locale.ROOT))
                || normalized.contains("[会话摘要]".toLowerCase(Locale.ROOT))
                || normalized.contains("[复盘聚焦]".toLowerCase(Locale.ROOT))
                || normalized.contains("reply=")
                || (normalized.contains("intent=") && normalized.contains("channel="));
    }

    private boolean containsAny(String text, List<String> terms) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (normalized.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean requiresRealtimeAnswer(String userQuery) {
        return RealtimeIntentHeuristics.isRealtimeIntent(userQuery, REALTIME_TERMS);
    }

    private boolean shouldEmitTargetWarning(long decisionCount) {
        long previous = lastWarningDecision.get();
        if (decisionCount - previous < 50) {
            return false;
        }
        return lastWarningDecision.compareAndSet(previous, decisionCount);
    }
}
