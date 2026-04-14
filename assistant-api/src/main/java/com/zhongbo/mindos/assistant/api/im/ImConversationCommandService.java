package com.zhongbo.mindos.assistant.api.im;

import com.zhongbo.mindos.assistant.api.MemoryCommandOrchestrator;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.nlu.MemoryIntentNlu;
import com.zhongbo.mindos.assistant.memory.MemoryConsolidationService;
import com.zhongbo.mindos.assistant.memory.MemoryFacade;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionPlan;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionStep;
import com.zhongbo.mindos.assistant.memory.model.MemoryStyleProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ImConversationCommandService {

    static final String MEMORY_COMMAND_SKILL = "im.memory.command";
    static final String DINGTALK_TASK_QUERY_SKILL = "im.dingtalk.task.query";

    private static final String PROP_TODO_P1_THRESHOLD = "mindos.todo.priority.p1-threshold";
    private static final String PROP_TODO_P2_THRESHOLD = "mindos.todo.priority.p2-threshold";
    private static final String PROP_TODO_WINDOW_P1 = "mindos.todo.window.p1";
    private static final String PROP_TODO_WINDOW_P2 = "mindos.todo.window.p2";
    private static final String PROP_TODO_WINDOW_P3 = "mindos.todo.window.p3";
    private static final String PROP_TODO_LEGEND = "mindos.todo.legend";
    private static final String DINGTALK_ASYNC_TASK_TITLE = "钉钉消息处理";
    private static final String DINGTALK_RESULT_NOTE_PREFIX = "[ASYNC_RESULT] ";
    private static final String[] DINGTALK_PROGRESS_QUERY_TERMS = {
            "查进度", "查看进度", "进度", "任务进度", "查看状态", "查状态", "进度查询", "status"
    };
    private static final String[] DINGTALK_RESULT_QUERY_TERMS = {
            "查结果", "查看结果", "结果", "任务结果", "最终结果", "处理结果", "结果查询"
    };
    private static final Pattern TASK_ID_PATTERN = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");

    private final MemoryFacade memoryFacade;
    private final MemoryCommandOrchestrator memoryCommandOrchestrator;
    private final MemoryConsolidationService memoryConsolidationService;
    private final Map<String, String> pendingKeyPointReviews = new ConcurrentHashMap<>();
    private final Map<String, List<String>> pendingTodoFromReview = new ConcurrentHashMap<>();

    @Autowired
    public ImConversationCommandService(MemoryFacade memoryFacade,
                                        MemoryCommandOrchestrator memoryCommandOrchestrator,
                                        MemoryConsolidationService memoryConsolidationService) {
        this.memoryFacade = memoryFacade;
        this.memoryCommandOrchestrator = memoryCommandOrchestrator;
        this.memoryConsolidationService = memoryConsolidationService;
    }

    ImConversationCommandService(MemoryFacade memoryFacade,
                                 MemoryConsolidationService memoryConsolidationService) {
        this.memoryFacade = memoryFacade;
        this.memoryCommandOrchestrator = new MemoryCommandOrchestrator(memoryFacade);
        this.memoryConsolidationService = memoryConsolidationService;
    }

    public Optional<SkillDsl> resolveSkillDsl(String userId, String message, Map<String, Object> profileContext) {
        if (message == null || message.isBlank() || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        String platform = text(profileContext == null ? null : profileContext.get("imPlatform")).toLowerCase();
        String normalized = message.trim();
        if ("dingtalk".equals(platform)) {
            boolean resultIntent = isDingtalkResultQuery(normalized);
            boolean progressIntent = isDingtalkProgressQuery(normalized);
            if (resultIntent || progressIntent) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("action", resultIntent ? "result" : "progress");
                String taskId = extractTaskId(normalized);
                if (!taskId.isBlank()) {
                    params.put("taskId", taskId);
                }
                return Optional.of(new SkillDsl(DINGTALK_TASK_QUERY_SKILL, params));
            }
        }
        if (platform.isBlank()) {
            return Optional.empty();
        }
        if (MemoryIntentNlu.isAffirmativeIntent(normalized)) {
            String pendingSource = pendingKeyPointReviews.get(userId);
            if (pendingSource != null && !pendingSource.isBlank()) {
                return Optional.of(new SkillDsl(MEMORY_COMMAND_SKILL, Map.of("action", "review_keypoints")));
            }
        }
        if (isTodoGenerationIntent(normalized)) {
            List<String> pendingPoints = pendingTodoFromReview.get(userId);
            if (pendingPoints != null && !pendingPoints.isEmpty()) {
                return Optional.of(new SkillDsl(MEMORY_COMMAND_SKILL, Map.of("action", "generate_todo")));
            }
        }

        String sample = MemoryIntentNlu.extractAutoTuneSample(normalized);
        if (sample != null || normalized.contains("自动微调记忆风格") || normalized.contains("微调记忆风格")) {
            return Optional.of(new SkillDsl(MEMORY_COMMAND_SKILL, Map.of(
                    "action", "auto_tune",
                    "sample", sample == null ? normalized : sample
            )));
        }
        MemoryIntentNlu.StyleUpdateIntent styleUpdateIntent = MemoryIntentNlu.extractStyleUpdateIntent(normalized);
        if (styleUpdateIntent != null && styleUpdateIntent.hasValues()) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("action", "style_update");
            if (styleUpdateIntent.styleName() != null) {
                params.put("styleName", styleUpdateIntent.styleName());
            }
            if (styleUpdateIntent.tone() != null) {
                params.put("tone", styleUpdateIntent.tone());
            }
            if (styleUpdateIntent.outputFormat() != null) {
                params.put("outputFormat", styleUpdateIntent.outputFormat());
            }
            return Optional.of(new SkillDsl(MEMORY_COMMAND_SKILL, params));
        }
        if (MemoryIntentNlu.isStyleShowIntent(normalized)) {
            return Optional.of(new SkillDsl(MEMORY_COMMAND_SKILL, Map.of("action", "style_show")));
        }

        MemoryIntentNlu.CompressionIntent compressionIntent = MemoryIntentNlu.extractCompressionIntent(normalized);
        if (compressionIntent == null) {
            return Optional.empty();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "compress");
        params.put("source", compressionIntent.source() == null || compressionIntent.source().isBlank()
                ? normalized
                : compressionIntent.source());
        if (compressionIntent.focus() != null && !compressionIntent.focus().isBlank()) {
            params.put("focus", compressionIntent.focus());
        }
        return Optional.of(new SkillDsl(MEMORY_COMMAND_SKILL, params));
    }

    public SkillResult executeMemoryCommand(String userId, Map<String, Object> attributes) {
        String action = text(attributes == null ? null : attributes.get("action"));
        return switch (action) {
            case "review_keypoints" -> SkillResult.success(MEMORY_COMMAND_SKILL, reviewKeypoints(userId));
            case "generate_todo" -> SkillResult.success(MEMORY_COMMAND_SKILL, generateTodoChecklist(userId));
            case "auto_tune" -> SkillResult.success(MEMORY_COMMAND_SKILL, autoTuneStyle(userId, text(attributes == null ? null : attributes.get("sample"))));
            case "style_update" -> SkillResult.success(MEMORY_COMMAND_SKILL, updateStyle(userId, attributes));
            case "style_show" -> SkillResult.success(MEMORY_COMMAND_SKILL, showStyle(userId));
            case "compress" -> SkillResult.success(MEMORY_COMMAND_SKILL, compressMemory(userId, attributes));
            default -> SkillResult.failure(MEMORY_COMMAND_SKILL, "未识别的记忆命令。");
        };
    }

    public SkillResult executeDingtalkTaskQuery(String userId, Map<String, Object> attributes) {
        String action = text(attributes == null ? null : attributes.get("action"));
        LongTask task = resolveQueriedDingtalkTask(userId, text(attributes == null ? null : attributes.get("taskId")));
        if (task == null) {
            return SkillResult.success(DINGTALK_TASK_QUERY_SKILL, "当前没有可查询的钉钉异步任务。你也可以把任务ID发给我，我帮你精确查询。");
        }
        if ("result".equals(action)) {
            return SkillResult.success(DINGTALK_TASK_QUERY_SKILL, buildDingtalkResultReply(task));
        }
        return SkillResult.success(DINGTALK_TASK_QUERY_SKILL, buildDingtalkProgressReply(task));
    }

    private String reviewKeypoints(String userId) {
        String pendingSource = pendingKeyPointReviews.remove(userId);
        if (pendingSource == null || pendingSource.isBlank()) {
            return "当前没有待复核的关键点。你可以先发一段需要压缩的记忆，我再帮你梳理。";
        }
        return buildKeyPointReviewReply(userId, pendingSource);
    }

    private String generateTodoChecklist(String userId) {
        List<String> pendingPoints = pendingTodoFromReview.remove(userId);
        if (pendingPoints == null || pendingPoints.isEmpty()) {
            return "当前没有可生成待办的关键点。你可以先让我整理一段记忆，再继续生成执行清单。";
        }
        return buildTodoChecklistReply(pendingPoints);
    }

    private String autoTuneStyle(String userId, String sample) {
        MemoryStyleProfile updated = memoryCommandOrchestrator.updateMemoryStyleProfile(
                userId,
                new MemoryStyleProfile(null, null, null),
                true,
                sample
        );
        return "记忆风格已微调: " + updated.styleName()
                + "，语气=" + updated.tone()
                + "，格式=" + updated.outputFormat();
    }

    private String updateStyle(String userId, Map<String, Object> attributes) {
        MemoryStyleProfile updated = memoryCommandOrchestrator.updateMemoryStyleProfile(
                userId,
                new MemoryStyleProfile(
                        nullableText(attributes == null ? null : attributes.get("styleName")),
                        nullableText(attributes == null ? null : attributes.get("tone")),
                        nullableText(attributes == null ? null : attributes.get("outputFormat"))
                )
        );
        return "记忆风格已更新: " + updated.styleName()
                + "，语气=" + updated.tone()
                + "，格式=" + updated.outputFormat();
    }

    private String showStyle(String userId) {
        MemoryStyleProfile style = memoryFacade.getMemoryStyleProfile(userId);
        return "当前记忆风格: " + style.styleName()
                + "，语气=" + style.tone()
                + "，格式=" + style.outputFormat();
    }

    private String compressMemory(String userId, Map<String, Object> attributes) {
        String source = text(attributes == null ? null : attributes.get("source"));
        String focus = nullableText(attributes == null ? null : attributes.get("focus"));
        MemoryCompressionPlan plan = memoryFacade.buildMemoryCompressionPlan(
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

    private String buildDingtalkProgressReply(LongTask task) {
        StringBuilder builder = new StringBuilder("当前任务进度：");
        builder.append("\n- 任务ID：").append(task.taskId());
        builder.append("\n- 状态：").append(describeTaskStatus(task.status()));
        builder.append("\n- 进度：").append(task.progressPercent()).append("%");
        builder.append("\n- 已完成步骤：").append(task.completedSteps().size());
        builder.append("\n- 待完成步骤：").append(task.pendingSteps().size());
        if (!task.pendingSteps().isEmpty()) {
            builder.append("\n- 当前待处理：").append(task.pendingSteps().get(0));
        }
        if (task.status() == LongTaskStatus.BLOCKED && task.blockedReason() != null && !task.blockedReason().isBlank()) {
            builder.append("\n- 阻塞原因：").append(task.blockedReason());
        }
        String result = extractAsyncResult(task);
        if (task.status() == LongTaskStatus.BLOCKED && result != null) {
            builder.append("\n- 已生成结果，因主动回推未成功，我现在补发给你：\n").append(result);
        }
        return builder.toString();
    }

    private String buildDingtalkResultReply(LongTask task) {
        String result = extractAsyncResult(task);
        if (result == null || result.isBlank()) {
            return "这个任务暂时还没有可查看的最终结果。当前状态是："
                    + describeTaskStatus(task.status())
                    + "。如果你愿意，可以稍后回复“查进度”继续确认。";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("任务结果（").append(task.taskId()).append("）：");
        if (task.status() == LongTaskStatus.BLOCKED) {
            builder.append("\n之前 sessionWebhook 已失效或回推失败，我现在补发给你：");
        }
        builder.append("\n").append(result);
        return builder.toString();
    }

    private LongTask resolveQueriedDingtalkTask(String userId, String explicitTaskId) {
        if (explicitTaskId != null && !explicitTaskId.isBlank()) {
            LongTask task = memoryFacade.getLongTask(userId, explicitTaskId);
            if (task != null && DINGTALK_ASYNC_TASK_TITLE.equals(task.title())) {
                return task;
            }
        }
        return memoryFacade.listLongTasks(userId, null).stream()
                .filter(task -> DINGTALK_ASYNC_TASK_TITLE.equals(task.title()))
                .findFirst()
                .orElse(null);
    }

    private String extractTaskId(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        Matcher matcher = TASK_ID_PATTERN.matcher(message);
        return matcher.find() ? matcher.group() : "";
    }

    private boolean isDingtalkProgressQuery(String message) {
        String normalized = memoryConsolidationService.normalizeText(message).toLowerCase();
        return containsAny(normalized, DINGTALK_PROGRESS_QUERY_TERMS);
    }

    private boolean isDingtalkResultQuery(String message) {
        String normalized = memoryConsolidationService.normalizeText(message).toLowerCase();
        return containsAny(normalized, DINGTALK_RESULT_QUERY_TERMS);
    }

    private String describeTaskStatus(LongTaskStatus status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case PENDING -> "等待处理";
            case RUNNING -> "处理中";
            case BLOCKED -> "未成功送达";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
        };
    }

    private String extractAsyncResult(LongTask task) {
        if (task == null || task.recentNotes() == null) {
            return null;
        }
        for (int i = task.recentNotes().size() - 1; i >= 0; i--) {
            String normalized = stripNoteAuthor(task.recentNotes().get(i));
            if (normalized.startsWith(DINGTALK_RESULT_NOTE_PREFIX)) {
                return normalized.substring(DINGTALK_RESULT_NOTE_PREFIX.length()).trim();
            }
        }
        return null;
    }

    private String stripNoteAuthor(String note) {
        if (note == null) {
            return "";
        }
        int separator = note.indexOf(": ");
        if (separator < 0 || separator + 2 >= note.length()) {
            return note;
        }
        return note.substring(separator + 2);
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
        TodoPriorityPolicy policy = resolveTodoPriorityPolicy();
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
        appendPriorityLegend(builder, policy);
        appendPolicyPreview(builder, policy);
        appendBucket(builder, "今天（today）", today, policy);
        appendBucket(builder, "本周（this week）", thisWeek, policy);
        appendBucket(builder, "后续（later）", later, policy);
        if (today.isEmpty() && thisWeek.isEmpty() && later.isEmpty()) {
            builder.append("\n1) 暂无可执行条目，请补充更具体的行动描述。");
        }
        return builder.toString();
    }

    private void appendPriorityLegend(StringBuilder builder, TodoPriorityPolicy policy) {
        builder.append("\n").append(policy.legend()).append("\n");
    }

    private void appendPolicyPreview(StringBuilder builder, TodoPriorityPolicy policy) {
        builder.append("当前待办策略：P1>= ")
                .append(policy.p1Threshold())
                .append("，P2>= ")
                .append(policy.p2Threshold())
                .append("；P1=")
                .append(policy.p1Window())
                .append("，P2=")
                .append(policy.p2Window())
                .append("，P3=")
                .append(policy.p3Window())
                .append("。\n");
    }

    private void appendBucket(StringBuilder builder, String title, List<String> items, TodoPriorityPolicy policy) {
        if (items.isEmpty()) {
            return;
        }
        builder.append("\n[").append(title).append("]");
        for (int i = 0; i < items.size(); i++) {
            String formatted = formatTodoItem(items.get(i), policy);
            builder.append("\n").append(i + 1).append(") ").append(formatted);
        }
    }

    private String formatTodoItem(String point, TodoPriorityPolicy policy) {
        int score = priorityScore(point);
        String priority = score >= policy.p1Threshold() ? "P1" : (score >= policy.p2Threshold() ? "P2" : "P3");
        String action = actionVerb(point);
        String cleaned = normalizeActionText(point);
        return priority + " " + action + "：" + cleaned + "（" + suggestedWindow(priority, policy) + "）";
    }

    private String suggestedWindow(String priority, TodoPriorityPolicy policy) {
        return switch (priority) {
            case "P1" -> policy.p1Window();
            case "P2" -> policy.p2Window();
            default -> policy.p3Window();
        };
    }

    private TodoPriorityPolicy resolveTodoPriorityPolicy() {
        int p1Threshold = readPositiveIntProperty(PROP_TODO_P1_THRESHOLD, 45);
        int p2Threshold = readPositiveIntProperty(PROP_TODO_P2_THRESHOLD, 25);
        if (p2Threshold > p1Threshold) {
            p2Threshold = p1Threshold;
        }
        String p1Window = readTextProperty(PROP_TODO_WINDOW_P1, "建议24小时内完成");
        String p2Window = readTextProperty(PROP_TODO_WINDOW_P2, "建议3天内完成");
        String p3Window = readTextProperty(PROP_TODO_WINDOW_P3, "建议本周内完成");
        String legend = readTextProperty(PROP_TODO_LEGEND, "优先级说明：P1=今天必须完成，P2=3天内推进，P3=本周内安排。");
        return new TodoPriorityPolicy(p1Threshold, p2Threshold, p1Window, p2Window, p3Window, legend);
    }

    private int readPositiveIntProperty(String key, int defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private String readTextProperty(String key, String defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
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
        return memoryConsolidationService.normalizeText(point)
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
        List<String> selected = new ArrayList<>();
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

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String nullableText(Object value) {
        String normalized = text(value);
        return normalized.isBlank() ? null : normalized;
    }

    private record TodoPriorityPolicy(
            int p1Threshold,
            int p2Threshold,
            String p1Window,
            String p2Window,
            String p3Window,
            String legend
    ) {
    }
}
