package com.zhongbo.mindos.assistant.dispatcher;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

final class ConversationMemoryModeService {

    private static final Pattern DISABLE_MEMORY_PATTERN = Pattern.compile(
            "^(?:请)?(?:本次对话)?(?:不要|别|不用|不再)(?:记录|记忆|记住).*$"
                    + "|^(?:关闭|暂停)(?:记忆|记录).*$"
                    + "|^(?:进入|开启)隐私模式.*$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ENABLE_MEMORY_PATTERN = Pattern.compile(
            "^(?:恢复|重新开启|重新打开|开启|打开|继续)(?:记忆|记录).*$"
                    + "|^(?:退出|关闭)隐私模式.*$",
            Pattern.CASE_INSENSITIVE
    );

    private final ConcurrentHashMap<String, Boolean> suppressedUsers = new ConcurrentHashMap<>();

    Optional<MemoryModeDirective> detectDirective(String userInput) {
        String normalized = normalize(userInput);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        if (DISABLE_MEMORY_PATTERN.matcher(normalized).matches()) {
            return Optional.of(new MemoryModeDirective(
                    true,
                    "memory.mode",
                    "好的，已关闭记忆。本轮起我不会读取或写入历史记忆；发送“恢复记忆”即可重新开启。"
            ));
        }
        if (ENABLE_MEMORY_PATTERN.matcher(normalized).matches()) {
            return Optional.of(new MemoryModeDirective(
                    false,
                    "memory.mode",
                    "好的，已恢复记忆。后续对话会重新参与历史记忆读取与写入。"
            ));
        }
        return Optional.empty();
    }

    boolean isMemorySuppressed(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(suppressedUsers.get(userId));
    }

    void apply(String userId, MemoryModeDirective directive) {
        if (userId == null || userId.isBlank() || directive == null) {
            return;
        }
        if (directive.suppressed()) {
            suppressedUsers.put(userId, Boolean.TRUE);
        } else {
            suppressedUsers.remove(userId);
        }
    }

    boolean isExplicitMemoryRecallRequest(String userInput) {
        String normalized = normalize(userInput);
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.contains("根据记忆")
                || normalized.contains("查看记忆")
                || normalized.contains("读取记忆")
                || normalized.contains("回顾之前")
                || normalized.contains("回顾一下之前")
                || normalized.contains("复述之前")
                || normalized.contains("总结之前")
                || normalized.contains("你还记得")
                || normalized.contains("还记得我们")
                || (normalized.contains("之前") && normalized.contains("说过"))
                || (normalized.contains("之前") && normalized.contains("聊过"))
                || (normalized.contains("上次") && normalized.contains("内容"));
    }

    String disabledRecallReply() {
        return "当前已关闭记忆，我不会读取历史记忆。发送“恢复记忆”后可继续查看或回顾之前内容。";
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    record MemoryModeDirective(boolean suppressed, String channel, String reply) {
    }
}
