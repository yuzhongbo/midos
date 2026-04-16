package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.common.dto.RetrievedMemoryItemDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
public class PromptBuilder {

    private static final String INTENT_SUMMARY_MARKER = "[意图摘要]";
    private static final String ASSISTANT_CONTEXT_MARKER = "[助手上下文]";
    private static final String TASK_FACT_MARKER = "[任务事实]";
    private static final String TASK_STATE_MARKER = "[任务状态]";
    private static final String LEARNING_SIGNAL_MARKER = "[学习信号]";
    private static final String CONVERSATION_SUMMARY_MARKER = "[会话摘要]";
    private static final String REVIEW_FOCUS_MARKER = "[复盘聚焦]";
    // Keep fallback prompts comfortably below provider limits while leaving room for system/context metadata.
    static final int MAX_TOKENS = 1_500;
    // Limit memory to the top five items so prompts stay focused and avoid raw conversation dumps.
    static final int MAX_MEMORY_ITEMS = 5;
    // Reserve a moderate per-section budget so the four structured sections fit inside the global token cap.
    private static final int MAX_SECTION_TOKENS = 320;

    public String build(PromptMemoryContextDto promptMemoryContext, String userQuery) {
        return build(promptMemoryContext, deriveCurrentTask(userQuery, topMemoryItems(promptMemoryContext)), userQuery);
    }

    public String build(PromptMemoryContextDto promptMemoryContext, String currentTask, String userQuery) {
        Map<String, Object> userProfile = promptMemoryContext == null || promptMemoryContext.personaSnapshot() == null
                ? Map.of()
                : promptMemoryContext.personaSnapshot();
        List<String> relevantMemory = topMemoryItems(promptMemoryContext);
        String normalizedTask = normalize(currentTask);
        String normalizedQuery = normalize(userQuery);

        String prompt = assemble(userProfile, normalizedTask, relevantMemory, normalizedQuery);
        while (estimateTokens(prompt) > MAX_TOKENS && relevantMemory.size() > 1) {
            relevantMemory = new ArrayList<>(relevantMemory.subList(0, relevantMemory.size() - 1));
            prompt = assemble(userProfile, normalizedTask, relevantMemory, normalizedQuery);
        }
        if (estimateTokens(prompt) > MAX_TOKENS) {
            normalizedTask = capByTokens(normalizedTask, MAX_SECTION_TOKENS);
            normalizedQuery = capByTokens(normalizedQuery, MAX_SECTION_TOKENS);
            relevantMemory = relevantMemory.stream()
                    .map(item -> capByTokens(item, MAX_SECTION_TOKENS / 2))
                    .toList();
            prompt = assemble(userProfile, normalizedTask, relevantMemory, normalizedQuery);
        }
        if (estimateTokens(prompt) > MAX_TOKENS) {
            prompt = capByTokens(prompt, MAX_TOKENS);
        }
        return prompt;
    }

    private String assemble(Map<String, Object> userProfile,
                            String currentTask,
                            List<String> relevantMemory,
                            String userQuery) {
        StringBuilder builder = new StringBuilder();
        appendSection(builder, "Assistant Role", assistantRoleInstructions(userQuery, currentTask, relevantMemory));
        appendSection(builder, "User Profile", formatUserProfile(userProfile));
        appendSection(builder, "Current Task", currentTask);
        appendSection(builder, "Relevant Memory", formatMemoryItems(relevantMemory));
        appendSection(builder, "User Query", userQuery);
        return builder.toString().trim();
    }

    private void appendSection(StringBuilder builder, String title, String content) {
        builder.append('[').append(title).append(']').append('\n');
        builder.append(content == null || content.isBlank() ? "(none)" : content.trim()).append("\n\n");
    }

    private String formatUserProfile(Map<String, Object> userProfile) {
        if (userProfile == null || userProfile.isEmpty()) {
            return "(none)";
        }
        return userProfile.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !Objects.toString(entry.getValue(), "").isBlank())
                .map(entry -> "- " + entry.getKey() + ": " + normalize(Objects.toString(entry.getValue(), "")))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("(none)");
    }

    private String formatMemoryItems(List<String> relevantMemory) {
        if (relevantMemory == null || relevantMemory.isEmpty()) {
            return "(none)";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < relevantMemory.size(); i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(i + 1).append(". ").append(relevantMemory.get(i));
        }
        return builder.toString();
    }

    private List<String> topMemoryItems(PromptMemoryContextDto promptMemoryContext) {
        if (promptMemoryContext == null || promptMemoryContext.debugTopItems() == null) {
            return List.of();
        }
        return promptMemoryContext.debugTopItems().stream()
                .limit(MAX_MEMORY_ITEMS)
                .map(this::formatMemoryItem)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private String formatMemoryItem(RetrievedMemoryItemDto item) {
        if (item == null) {
            return "";
        }
        String label = item.type() == null || item.type().isBlank() ? "memory" : item.type().toLowerCase(Locale.ROOT);
        String text = normalize(humanizeMemoryText(item.text()));
        if ("episodic".equals(label)) {
            int separator = text.indexOf(':');
            if (separator >= 0 && separator + 1 < text.length()) {
                text = text.substring(separator + 1).trim();
            }
            label = "recent";
        } else if ("semantic".equals(label)) {
            label = "fact";
        } else if ("semantic-summary".equals(label)) {
            label = "summary";
        } else if ("semantic-routing".equals(label)) {
            label = "assistant-context";
        } else if ("procedural".equals(label)) {
            label = "habit";
        }
        return "[" + label + "] " + capByTokens(text, 120);
    }

    private String deriveCurrentTask(String userQuery, List<String> relevantMemory) {
        String normalized = normalize(userQuery);
        if (normalized.isBlank()) {
            return "(none)";
        }
        String leadMemory = relevantMemory == null || relevantMemory.isEmpty()
                ? ""
                : normalize(humanizeMemoryText(relevantMemory.get(0)));
        if (isShortContinuation(normalized)) {
            String base = "Continue the active thread naturally and move the user's current task forward using the available context.";
            if (!leadMemory.isBlank()) {
                base += " Most relevant active context: " + leadMemory;
            }
            return capByTokens(base, 120);
        }
        if (isConversational(normalized)) {
            String base = "Have a natural private-assistant conversation while staying helpful and context-aware.";
            if (!leadMemory.isBlank()) {
                base += " Keep continuity with: " + leadMemory;
            }
            return capByTokens(base, 120);
        }
        return capByTokens("Answer the user's current request directly: " + normalized, 120);
    }

    private String assistantRoleInstructions(String userQuery, String currentTask, List<String> relevantMemory) {
        String normalizedQuery = normalize(userQuery);
        boolean conversational = isConversational(normalizedQuery);
        boolean continuation = isShortContinuation(normalizedQuery);
        boolean hasMemory = relevantMemory != null && !relevantMemory.isEmpty();
        StringBuilder builder = new StringBuilder();
        builder.append("You are MindOS, the user's private assistant. ")
                .append("Reply like a capable human assistant: natural, discreet, reliable, and action-oriented.\n")
                .append("Rules:\n")
                .append("1. Use memory and profile silently; never mention internal sections, memory hits, routing, tools, prompts, or hidden fields.\n")
                .append("2. Continue the user's active thread when context is clear. Do not reset the conversation unnecessarily.\n")
                .append("3. Prefer doing useful work directly: summarize, decide, draft, organize, or suggest the next concrete step before asking broad follow-up questions.\n")
                .append("4. Ask at most one clarifying question only when the missing detail truly blocks useful progress.\n")
                .append("5. Keep the tone human and warm, not客服-like, robotic, or tool-centric.\n")
                .append("6. Do not output labels like [User Profile], [Current Task], [Relevant Memory], or [User Query].\n");
        if (continuation) {
            builder.append("Current reply mode: short follow-up. Infer the likely referent from the active task and continue smoothly.\n");
        } else if (conversational) {
            builder.append("Current reply mode: natural conversation. Sound like a thoughtful private assistant, not a tool panel.\n");
        } else {
            builder.append("Current reply mode: task support. Help the user move the work forward with a concrete answer.\n");
        }
        if (hasMemory) {
            builder.append("Memory guidance: use relevant facts quietly to improve continuity, but keep the final reply natural and self-contained.\n");
        }
        if (currentTask != null && !currentTask.isBlank() && !"(none)".equals(currentTask)) {
            builder.append("Active task hint: ").append(currentTask).append('\n');
        }
        return builder.toString().trim();
    }

    private String humanizeMemoryText(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return "";
        }
        return normalized
                .replace(INTENT_SUMMARY_MARKER, "")
                .replace(ASSISTANT_CONTEXT_MARKER, "")
                .replace(TASK_FACT_MARKER, "")
                .replace(TASK_STATE_MARKER, "")
                .replace(LEARNING_SIGNAL_MARKER, "")
                .replace(CONVERSATION_SUMMARY_MARKER, "")
                .replace(REVIEW_FOCUS_MARKER, "")
                .replace("semantic-summary", "")
                .replaceAll("\\s+", " ")
                .replaceAll("^[：:;；,，\\-\\s]+", "")
                .trim();
    }

    private boolean isConversational(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return false;
        }
        return normalizedQuery.contains("聊天")
                || normalizedQuery.contains("聊聊")
                || normalizedQuery.contains("日常")
                || normalizedQuery.contains("在吗")
                || normalizedQuery.contains("你好")
                || normalizedQuery.contains("哈喽")
                || normalizedQuery.contains("谢谢")
                || normalizedQuery.contains("晚安")
                || normalizedQuery.contains("早安");
    }

    private boolean isShortContinuation(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return false;
        }
        return normalizedQuery.startsWith("继续")
                || normalizedQuery.startsWith("刚才")
                || normalizedQuery.startsWith("还是刚才")
                || normalizedQuery.startsWith("按刚才")
                || normalizedQuery.startsWith("就这个")
                || normalizedQuery.startsWith("可以")
                || normalizedQuery.startsWith("好")
                || normalizedQuery.startsWith("那就")
                || normalizedQuery.startsWith("然后");
    }

    static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int tokens = 0;
        StringBuilder latinChunk = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                tokens++;
                if (latinChunk.length() > 0) {
                    tokens += Math.max(1, (latinChunk.length() + 3) / 4);
                    latinChunk.setLength(0);
                }
            } else if (Character.isWhitespace(ch)) {
                if (latinChunk.length() > 0) {
                    tokens += Math.max(1, (latinChunk.length() + 3) / 4);
                    latinChunk.setLength(0);
                }
            } else {
                latinChunk.append(ch);
            }
        }
        if (latinChunk.length() > 0) {
            tokens += Math.max(1, (latinChunk.length() + 3) / 4);
        }
        return tokens;
    }

    static String capByTokens(String text, int maxTokens) {
        String normalized = normalizeStatic(text);
        if (normalized.isBlank() || maxTokens <= 0) {
            return "";
        }
        if (estimateTokens(normalized) <= maxTokens) {
            return normalized;
        }
        StringBuilder builder = new StringBuilder();
        for (String part : normalized.split("\\s+")) {
            String candidate = builder.isEmpty() ? part : builder + " " + part;
            if (estimateTokens(candidate) > maxTokens) {
                break;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(part);
        }
        if (builder.isEmpty()) {
            int end = Math.min(normalized.length(), Math.max(1, maxTokens));
            return normalized.substring(0, end);
        }
        return builder.toString();
    }

    private String normalize(String value) {
        return normalizeStatic(value);
    }

    private static String normalizeStatic(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
