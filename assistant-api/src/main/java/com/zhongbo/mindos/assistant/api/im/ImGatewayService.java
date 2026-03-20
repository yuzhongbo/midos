package com.zhongbo.mindos.assistant.api.im;

import com.zhongbo.mindos.assistant.common.nlu.MemoryIntentNlu;
import com.zhongbo.mindos.assistant.dispatcher.DispatchResult;
import com.zhongbo.mindos.assistant.dispatcher.DispatcherService;
import com.zhongbo.mindos.assistant.memory.MemoryConsolidationService;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionPlan;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionStep;
import com.zhongbo.mindos.assistant.memory.model.MemoryStyleProfile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ImGatewayService {

    private final DispatcherService dispatcherService;
    private final MemoryManager memoryManager;
    private final MemoryConsolidationService memoryConsolidationService;
    private final Map<String, String> pendingKeyPointReviews = new ConcurrentHashMap<>();
    private final Map<String, List<String>> pendingTodoFromReview = new ConcurrentHashMap<>();

    ImGatewayService(DispatcherService dispatcherService,
                     MemoryManager memoryManager,
                     MemoryConsolidationService memoryConsolidationService) {
        this.dispatcherService = dispatcherService;
        this.memoryManager = memoryManager;
        this.memoryConsolidationService = memoryConsolidationService;
    }

    String chat(ImPlatform platform, String senderId, String chatId, String text) {
        String normalizedText = text == null ? "" : text.trim();
        if (normalizedText.isBlank()) {
            return "请发送文本消息，我会继续协助你。";
        }

        String userId = buildUserId(platform, senderId);
        Map<String, Object> profileContext = new LinkedHashMap<>();
        profileContext.put("imPlatform", platform.name().toLowerCase());
        profileContext.put("imSenderId", senderId == null ? "" : senderId);
        profileContext.put("imChatId", chatId == null ? "" : chatId);

        String memoryReply = tryHandleMemoryPlanningIntent(userId, normalizedText);
        if (memoryReply != null) {
            return memoryReply;
        }

        DispatchResult result = dispatcherService.dispatch(userId, normalizedText, profileContext);
        return result.reply();
    }

    private String tryHandleMemoryPlanningIntent(String userId, String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String normalized = message.trim();
        if (MemoryIntentNlu.isAffirmativeIntent(normalized)) {
            String pendingSource = pendingKeyPointReviews.remove(userId);
            if (pendingSource != null && !pendingSource.isBlank()) {
                return buildKeyPointReviewReply(userId, pendingSource);
            }
        }
        if (isTodoGenerationIntent(normalized)) {
            List<String> pendingPoints = pendingTodoFromReview.remove(userId);
            if (pendingPoints != null && !pendingPoints.isEmpty()) {
                return buildTodoChecklistReply(pendingPoints);
            }
        }

        String sample = MemoryIntentNlu.extractAutoTuneSample(normalized);
        if (sample != null || normalized.contains("自动微调记忆风格") || normalized.contains("微调记忆风格")) {
            MemoryStyleProfile updated = memoryManager.updateMemoryStyleProfile(
                    userId,
                    new MemoryStyleProfile(null, null, null),
                    true,
                    sample == null ? normalized : sample
            );
            return "记忆风格已微调: " + updated.styleName()
                    + "，语气=" + updated.tone()
                    + "，格式=" + updated.outputFormat();
        }
        MemoryIntentNlu.StyleUpdateIntent styleUpdateIntent = MemoryIntentNlu.extractStyleUpdateIntent(normalized);
        if (styleUpdateIntent != null && styleUpdateIntent.hasValues()) {
            MemoryStyleProfile updated = memoryManager.updateMemoryStyleProfile(userId,
                    new MemoryStyleProfile(styleUpdateIntent.styleName(), styleUpdateIntent.tone(), styleUpdateIntent.outputFormat()));
            return "记忆风格已更新: " + updated.styleName()
                    + "，语气=" + updated.tone()
                    + "，格式=" + updated.outputFormat();
        }
        if (MemoryIntentNlu.isStyleShowIntent(normalized)) {
            MemoryStyleProfile style = memoryManager.getMemoryStyleProfile(userId);
            return "当前记忆风格: " + style.styleName()
                    + "，语气=" + style.tone()
                    + "，格式=" + style.outputFormat();
        }

        MemoryIntentNlu.CompressionIntent compressionIntent = MemoryIntentNlu.extractCompressionIntent(normalized);
        if (compressionIntent == null) {
            return null;
        }

        String source = compressionIntent.source() == null || compressionIntent.source().isBlank()
                ? normalized
                : compressionIntent.source();
        String focus = compressionIntent.focus();

        MemoryCompressionPlan plan = memoryManager.buildMemoryCompressionPlan(
                userId,
                source,
                new MemoryStyleProfile(null, null, null),
                focus
        );
        String styled = plan.steps().stream()
                .filter(step -> "STYLED".equals(step.stage()))
                .map(MemoryCompressionStep::content)
                .findFirst()
                .orElse("已生成记忆压缩规划。");
        return styled + "\n" + summarizeCompression(userId, source, styled);
    }

    private String summarizeCompression(String userId, String rawText, String styledText) {
        String raw = memoryConsolidationService.normalizeText(rawText);
        String styled = memoryConsolidationService.normalizeText(styledText);
        int rawLength = raw.length();
        int styledLength = styled.length();
        double ratio = rawLength == 0 ? 0.0 : (double) styledLength / rawLength;
        String ratioText = String.format("压缩后约为原文的 %.1f%%", ratio * 100.0);

        boolean keySignalIn = memoryConsolidationService.containsKeySignal(raw);
        boolean keySignalOut = memoryConsolidationService.containsKeySignal(styled);
        String keySignalHint;
        if (!keySignalIn) {
            pendingKeyPointReviews.remove(userId);
            pendingTodoFromReview.remove(userId);
            keySignalHint = "未发现明显的硬性约束。";
        } else if (keySignalOut) {
            pendingKeyPointReviews.remove(userId);
            pendingTodoFromReview.remove(userId);
            keySignalHint = "关键约束已保留。";
        } else {
            pendingKeyPointReviews.put(userId, raw);
            pendingTodoFromReview.remove(userId);
            keySignalHint = "我识别到关键约束，但压缩后可能有少量信息被弱化。"
                    + "如果你愿意，我可以再列出原文关键点给你逐条复核。";
        }
        return "我已帮你完成记忆整理，" + ratioText + "。" + keySignalHint;
    }

    private String buildKeyPointReviewReply(String userId, String sourceText) {
        List<String> points = extractReviewPoints(sourceText);
        if (points.isEmpty()) {
            return "我这边暂时没提炼出明确关键点。你可以直接发原文，我再按条帮你复核。";
        }
        StringBuilder builder = new StringBuilder("好的，我为你整理了原文关键点，请快速复核：");
        for (int i = 0; i < points.size(); i++) {
            builder.append("\n").append(i + 1).append(") ").append(points.get(i));
        }
        pendingTodoFromReview.put(userId, List.copyOf(points));
        builder.append("\n如果你愿意，回复“生成待办”，我可以把这些关键点转成执行清单。");
        return builder.toString();
    }

    private String buildTodoChecklistReply(List<String> points) {
        List<String> sorted = sortByPriority(points);
        List<String> today = new ArrayList<>();
        List<String> thisWeek = new ArrayList<>();
        List<String> later = new ArrayList<>();
        for (String point : sorted) {
            switch (classifyBucket(point)) {
                case "today" -> today.add(point);
                case "this-week" -> thisWeek.add(point);
                default -> later.add(point);
            }
        }

        StringBuilder builder = new StringBuilder("好的，已根据关键点整理执行清单：");
        appendBucket(builder, "今天（today）", today);
        appendBucket(builder, "本周（this week）", thisWeek);
        appendBucket(builder, "后续（later）", later);
        if (today.isEmpty() && thisWeek.isEmpty() && later.isEmpty()) {
            builder.append("\n1) 暂无可执行条目，请补充更具体的行动描述。");
        }
        return builder.toString();
    }

    private void appendBucket(StringBuilder builder, String title, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        builder.append("\n[").append(title).append("]");
        for (int i = 0; i < items.size(); i++) {
            String formatted = formatTodoItem(items.get(i));
            builder.append("\n").append(i + 1).append(") ").append(formatted);
        }
    }

    private String formatTodoItem(String point) {
        int score = priorityScore(point);
        String priority = score >= 45 ? "P1" : (score >= 25 ? "P2" : "P3");
        String action = actionVerb(point);
        String cleaned = normalizeActionText(point);
        return priority + " " + action + "：" + cleaned;
    }

    private String actionVerb(String point) {
        String normalized = memoryConsolidationService.normalizeText(point).toLowerCase();
        if (containsAny(normalized, "提交", "send", "commit")) {
            return "提交";
        }
        if (containsAny(normalized, "检查", "核对", "确认", "review", "check")) {
            return "核对";
        }
        if (containsAny(normalized, "联系", "沟通", "通知", "call", "contact")) {
            return "联系";
        }
        if (containsAny(normalized, "安排", "计划", "prepare", "plan")) {
            return "安排";
        }
        return "执行";
    }

    private String normalizeActionText(String point) {
        String text = memoryConsolidationService.normalizeText(point);
        return text
                .replaceFirst("^(请|需要|要|必须|务必|尽快)\\s*", "")
                .trim();
    }

    private List<String> sortByPriority(List<String> points) {
        return points.stream()
                .sorted((left, right) -> Integer.compare(priorityScore(right), priorityScore(left)))
                .toList();
    }

    private int priorityScore(String point) {
        String normalized = memoryConsolidationService.normalizeText(point).toLowerCase();
        int score = 0;
        if (containsAny(normalized, "今天", "今日", "今晚", "today", "立即", "马上")) {
            score += 30;
        }
        if (containsAny(normalized, "明天", "后天", "本周", "这周", "周", "this week", "tomorrow")) {
            score += 20;
        }
        if (normalized.matches(".*\\d{1,2}[:：]\\d{2}.*") || containsAny(normalized, "截止", "deadline", "due")) {
            score += 15;
        }
        if (memoryConsolidationService.containsKeySignal(normalized)) {
            score += 10;
        }
        return score;
    }

    private String classifyBucket(String point) {
        String normalized = memoryConsolidationService.normalizeText(point).toLowerCase();
        if (containsAny(normalized, "今天", "今日", "今晚", "today", "立即", "马上")
                || normalized.matches(".*\\d{1,2}[:：]\\d{2}.*")) {
            return "today";
        }
        if (containsAny(normalized, "明天", "后天", "本周", "这周", "周", "this week", "tomorrow")) {
            return "this-week";
        }
        return "later";
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTodoGenerationIntent(String message) {
        String normalized = memoryConsolidationService.normalizeText(message).toLowerCase();
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.contains("生成待办")
                || normalized.contains("转成待办")
                || normalized.contains("行动清单")
                || normalized.contains("todo list")
                || "待办".equals(normalized)
                || "todo".equals(normalized);
    }

    private List<String> extractReviewPoints(String sourceText) {
        String normalized = memoryConsolidationService.normalizeText(sourceText);
        if (normalized.isBlank()) {
            return List.of();
        }
        String[] parts = normalized.split("[\\n。！？!?；;]+");
        LinkedHashSet<String> prioritized = new LinkedHashSet<>();
        LinkedHashSet<String> fallback = new LinkedHashSet<>();
        for (String part : parts) {
            String line = memoryConsolidationService.normalizeText(part);
            if (line.isBlank()) {
                continue;
            }
            if (memoryConsolidationService.containsKeySignal(line)) {
                prioritized.add(line);
            } else {
                fallback.add(line);
            }
        }
        List<String> selected = new java.util.ArrayList<>();
        for (String line : prioritized) {
            if (selected.size() >= 5) {
                break;
            }
            selected.add(line);
        }
        for (String line : fallback) {
            if (selected.size() >= 5) {
                break;
            }
            selected.add(line);
        }
        return selected;
    }


    private String buildUserId(ImPlatform platform, String senderId) {
        String normalizedSender = senderId == null || senderId.isBlank() ? "anonymous" : senderId.trim();
        return "im:" + platform.name().toLowerCase() + ":" + normalizedSender;
    }
}

