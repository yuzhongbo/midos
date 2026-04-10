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

    // Keep fallback prompts comfortably below provider limits while leaving room for system/context metadata.
    static final int MAX_TOKENS = 1_500;
    // Limit memory to the top five items so prompts stay focused and avoid raw conversation dumps.
    static final int MAX_MEMORY_ITEMS = 5;
    // Reserve a moderate per-section budget so the four structured sections fit inside the global token cap.
    private static final int MAX_SECTION_TOKENS = 320;

    public String build(PromptMemoryContextDto promptMemoryContext, String userQuery) {
        return build(promptMemoryContext, deriveCurrentTask(userQuery), userQuery);
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
        String text = normalize(item.text());
        if ("episodic".equals(label)) {
            int separator = text.indexOf(':');
            if (separator >= 0 && separator + 1 < text.length()) {
                text = text.substring(separator + 1).trim();
            }
            label = "recent";
        }
        return "[" + label + "] " + capByTokens(text, 120);
    }

    private String deriveCurrentTask(String userQuery) {
        String normalized = normalize(userQuery);
        if (normalized.isBlank()) {
            return "(none)";
        }
        return capByTokens("Answer the user's current request directly: " + normalized, 120);
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
