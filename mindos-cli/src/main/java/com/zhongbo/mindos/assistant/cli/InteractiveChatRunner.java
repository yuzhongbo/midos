package com.zhongbo.mindos.assistant.cli;

import com.zhongbo.mindos.assistant.common.dto.ChatResponseDto;
import com.zhongbo.mindos.assistant.common.dto.ConversationTurnDto;
import com.zhongbo.mindos.assistant.common.dto.MemorySyncRequestDto;
import com.zhongbo.mindos.assistant.common.dto.MemorySyncResponseDto;
import com.zhongbo.mindos.assistant.common.dto.ProceduralMemoryEntryDto;
import com.zhongbo.mindos.assistant.common.dto.SemanticMemoryEntryDto;
import com.zhongbo.mindos.assistant.sdk.AssistantSdkException;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class InteractiveChatRunner {

    private static final CommandLine.Help.Ansi ANSI = CommandLine.Help.Ansi.AUTO;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_INSTANT;
    private final CommandNluParser commandNluParser = new CommandNluParser();

    void run(InputStream inputStream, PrintWriter out, CliChatService chatService) {
        SessionState sessionState = new SessionState();
        ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mindos-cli-bg");
            thread.setDaemon(true);
            return thread;
        });
        printWelcome(out, chatService, sessionState);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            while (true) {
                drainCompletedTasks(out, chatService, sessionState);
                out.print(renderPrompt(chatService));
                out.flush();

                String line = reader.readLine();
                if (line == null) {
                    out.println();
                    out.println("已退出对话模式。再见！");
                    out.flush();
                    return;
                }

                String input = line.trim();
                if (input.isEmpty()) {
                    continue;
                }
                if ("/exit".equalsIgnoreCase(input) || "/quit".equalsIgnoreCase(input)) {
                    awaitPendingTasks(out, chatService, sessionState);
                    out.println("已退出对话模式。再见！");
                    out.flush();
                    return;
                }
                if ("/help".equalsIgnoreCase(input)) {
                    printHelp(out);
                    continue;
                }
                if ("/session".equalsIgnoreCase(input)) {
                    printSession(out, chatService, sessionState);
                    continue;
                }
                if (input.startsWith("/")) {
                    String securityError = validateSensitiveCommand(input);
                    if (securityError != null) {
                        printError(out, securityError);
                        continue;
                    }
                    if (!confirmSensitiveActionIfNeeded(input, out, reader)) {
                        continue;
                    }
                    if (handleSlashCommand(input, out, chatService, reader, sessionState, backgroundExecutor)) {
                        continue;
                    }
                    printInfo(out, "未知命令：" + input + "，输入 /help 查看帮助。");
                    out.flush();
                    continue;
                }

                String naturalLanguageCommand = resolveNaturalLanguageCommand(input);
                if (naturalLanguageCommand != null) {
                    printInfo(out, "已识别自然语言指令 -> " + naturalLanguageCommand);
                    String securityError = validateSensitiveCommand(naturalLanguageCommand);
                    if (securityError != null) {
                        printError(out, securityError);
                        continue;
                    }
                    if (!confirmSensitiveActionIfNeeded(naturalLanguageCommand, out, reader)) {
                        continue;
                    }
                    if (handleSlashCommand(naturalLanguageCommand, out, chatService, reader, sessionState, backgroundExecutor)) {
                        continue;
                    }
                }

                try {
                    ChatResponseDto response = chatService.sendMessage(input);
                    sessionState.lastUserMessage = input;
                    sessionState.localTurns.add("你> " + input);
                    sessionState.localTurns.add("助手[" + response.channel() + "]> " + response.reply());
                    printAssistantReply(out, response);
                } catch (AssistantSdkException ex) {
                    printError(out, "请求失败(status=" + ex.statusCode()
                            + ", code=" + ex.errorCode() + "): " + ex.getMessage());
                }
                out.flush();
            }
        } catch (IOException ex) {
            printError(out, "交互式会话已中断: " + ex.getMessage());
            out.flush();
        } finally {
            shutdownExecutor(backgroundExecutor);
        }
    }

    private boolean confirmSensitiveActionIfNeeded(String command, PrintWriter out, BufferedReader reader) {
        if (!isSensitiveCommand(command)) {
            return true;
        }
        Boolean confirmed = promptYesNo(out, reader,
                "该命令可能影响安全或配置，确认执行吗？",
                false);
        if (confirmed == null || !confirmed) {
            printInfo(out, "已取消执行：" + command);
            return false;
        }
        return true;
    }

    private boolean isSensitiveCommand(String command) {
        String normalized = command == null ? "" : command.trim().toLowerCase();
        return normalized.startsWith("/skill load-jar")
                || normalized.startsWith("/skill load-mcp")
                || normalized.startsWith("/profile reset")
                || normalized.startsWith("/server ");
    }

    private String validateSensitiveCommand(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String normalized = command.trim().toLowerCase();
        if (normalized.startsWith("/server")) {
            String nextServer = command.substring("/server".length()).trim();
            if (nextServer.isBlank()) {
                return null;
            }
            return validateSensitiveUrl(nextServer, "server");
        }
        if (normalized.startsWith("/skill load-jar")) {
            Map<String, String> parsed = parseOptionPairs(command.substring("/skill load-jar".length()).trim());
            String url = parsed.get("url");
            if (url == null || url.isBlank()) {
                return null;
            }
            return validateSensitiveUrl(url, "url");
        }
        if (normalized.startsWith("/skill load-mcp")) {
            Map<String, String> parsed = parseOptionPairs(command.substring("/skill load-mcp".length()).trim());
            String url = parsed.get("url");
            if (url == null || url.isBlank()) {
                return null;
            }
            return validateSensitiveUrl(url, "url");
        }
        return null;
    }

    private String validateSensitiveUrl(String value, String fieldName) {
        try {
            UrlSecurityPolicy.requireAllowedSensitiveUrl(value, fieldName);
            return null;
        } catch (IllegalArgumentException ex) {
            return ex.getMessage();
        }
    }

    private void awaitPendingTasks(PrintWriter out, CliChatService chatService, SessionState sessionState) {
        drainCompletedTasks(out, chatService, sessionState);
        if (sessionState.pendingTasks.isEmpty()) {
            return;
        }
        printInfo(out, "正在等待后台任务完成（" + sessionState.pendingTasks.size() + "）...");
        for (CompletableFuture<Void> pendingTask : List.copyOf(sessionState.pendingTasks)) {
            try {
                pendingTask.get(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Keep shutdown responsive; unfinished tasks will be abandoned on exit.
            }
        }
        drainCompletedTasks(out, chatService, sessionState);
    }

    private void drainCompletedTasks(PrintWriter out, CliChatService chatService, SessionState sessionState) {
        sessionState.pendingTasks.removeIf(task -> {
            if (!task.isDone()) {
                return false;
            }
            try {
                task.join();
            } catch (Exception ignored) {
                // Errors are already surfaced by each task-specific handler.
            }
            synchronized (out) {
                out.print(renderPrompt(chatService));
                out.flush();
            }
            return true;
        });
    }

    private void shutdownExecutor(ExecutorService executorService) {
        executorService.shutdownNow();
    }

    private void printWelcome(PrintWriter out, CliChatService chatService, SessionState sessionState) {
        out.println(ANSI.string("@|bold,cyan ==== MindOS 对话模式 ====|@"));
        printSession(out, chatService, sessionState);
        out.println(ANSI.string("@|faint 输入 /help 查看帮助，输入 /exit 或 /quit 退出。|@"));
        out.println();
        out.flush();
    }

    private void printSession(PrintWriter out, CliChatService chatService, SessionState sessionState) {
        out.println(ANSI.string("@|bold 会话信息|@"));
        out.println("assistant=" + chatService.assistantName());
        out.println("user=" + chatService.userId());
        out.println("server=" + chatService.server());
        out.println("llm.provider=" + chatService.resolvedLlmProvider());
        out.println("profile.config=" + chatService.profileConfig());
        out.println("local.turns=" + sessionState.localTurns.size());
    }

    private void printHelp(PrintWriter out) {
        out.println("可用命令:");
        out.println("  /help                                   查看帮助");
        out.println("  /session                                查看当前会话信息");
        out.println("  /user <userId>                          切换当前用户");
        out.println("  /server <url>                           切换服务端地址");
        out.println("  /provider <name|default>                设置当前会话 LLM 提供商覆盖");
        out.println("  /history [--limit N]                    查看当前用户的服务端会话历史");
        out.println("  /retry                                  重试最近一条用户消息");
        out.println("  /clear                                  清空当前窗口本地状态并重新显示头部");
        out.println("  /skills                                 查看当前已注册技能");
        out.println("  /skill list                             查看当前已注册技能");
        out.println("  /skill reload                           重载本地自定义技能");
        out.println("  /skill reload-mcp                       重载已配置 MCP 技能");
        out.println("  /skill load-mcp --alias docs --url ...  动态加载一个 MCP server");
        out.println("  /skill load-jar --url ...               动态加载一个外部 skill JAR");
        out.println("  /profile show                           查看本地 profile");
        out.println("  /profile set [--field value ...]        更新本地 profile（无参时逐项引导）");
        out.println("         支持: --name --role --style --language --timezone --llm-provider");
        out.println("  /profile reset                          重置本地 profile");
        out.println("  /memory pull [--since N] [--limit N]    拉取增量记忆");
        out.println("  /memory push [--limit N]                在窗口内交互式整理并推送记忆");
        out.println("  /memory push --file path [--limit N]    推送记忆 JSON 文件（兼容模式）");
        out.println("  自然语言指令示例                          我有哪些技能 / 帮我拉取记忆 / 加载jar https://...");
        out.println("  重要命令会二次确认                        例如重置配置、加载外部技能、切换 server");
        out.println("  /exit                                   退出对话模式");
        out.println("  /quit                                   退出对话模式");
        out.flush();
    }

    private boolean handleSlashCommand(String input,
                                       PrintWriter out,
                                       CliChatService chatService,
                                       BufferedReader reader,
                                       SessionState sessionState,
                                       ExecutorService backgroundExecutor) {
        if (input.startsWith("/user ")) {
            String nextUser = input.substring("/user ".length()).trim();
            if (nextUser.isBlank()) {
                printError(out, "user 不能为空。");
                return true;
            }
            chatService.setUserId(nextUser);
            printInfo(out, "已切换 user=" + chatService.userId());
            return true;
        }

        if (input.startsWith("/server ")) {
            String nextServer = input.substring("/server ".length()).trim();
            if (nextServer.isBlank()) {
                printError(out, "server 不能为空。");
                return true;
            }
            try {
                chatService.setServer(nextServer);
                printInfo(out, "已切换 server=" + chatService.server());
            } catch (IllegalArgumentException ex) {
                printError(out, ex.getMessage());
            }
            return true;
        }

        if (input.startsWith("/provider")) {
            return handleProviderCommand(input, out, chatService);
        }

        if (input.startsWith("/history")) {
            return handleHistoryCommand(input, out, chatService);
        }

        if ("/skills".equalsIgnoreCase(input) || input.startsWith("/skill")) {
            return handleSkillCommand(input, out, chatService);
        }

        if ("/retry".equalsIgnoreCase(input)) {
            return handleRetry(out, chatService, sessionState);
        }

        if ("/clear".equalsIgnoreCase(input)) {
            sessionState.clear();
            out.println(ANSI.string("@|faint ------------------------------|@"));
            printInfo(out, "已清空当前窗口本地状态。");
            printWelcome(out, chatService, sessionState);
            return true;
        }

        if (input.startsWith("/profile")) {
            return handleProfileCommand(input, out, chatService, reader);
        }

        if (input.startsWith("/memory")) {
            return handleMemoryCommand(input, out, chatService, reader, sessionState, backgroundExecutor);
        }

        return false;
    }

    private boolean handleProviderCommand(String input, PrintWriter out, CliChatService chatService) {
        String argument = input.substring("/provider".length()).trim();
        if (argument.isEmpty()) {
            printInfo(out, "当前会话 llm.provider=" + chatService.resolvedLlmProvider());
            return true;
        }
        if ("default".equalsIgnoreCase(argument)) {
            chatService.clearSessionLlmProvider();
            printInfo(out, "已清除当前会话 provider 覆盖，当前值=" + chatService.resolvedLlmProvider());
            return true;
        }
        chatService.setSessionLlmProvider(argument);
        printInfo(out, "已设置当前会话 llm.provider=" + chatService.resolvedLlmProvider());
        return true;
    }

    private boolean handleSkillCommand(String input, PrintWriter out, CliChatService chatService) {
        if ("/skills".equalsIgnoreCase(input) || "/skill list".equalsIgnoreCase(input)) {
            try {
                List<Map<String, String>> skills = chatService.listSkills();
                if (skills.isEmpty()) {
                    printInfo(out, "当前没有已注册技能。");
                    return true;
                }
                printInfo(out, "当前已注册技能（" + skills.size() + "）:");
                for (Map<String, String> skill : skills) {
                    out.println("- " + skill.getOrDefault("name", "")
                            + " :: " + skill.getOrDefault("description", ""));
                }
            } catch (AssistantSdkException ex) {
                printError(out, "skills 获取失败(status=" + ex.statusCode() + ", code=" + ex.errorCode() + "): " + ex.getMessage());
            }
            return true;
        }

        if ("/skill reload".equalsIgnoreCase(input)) {
            try {
                Map<String, Object> response = chatService.reloadSkills();
                printInfo(out, "自定义技能已重载：reloaded=" + response.getOrDefault("reloaded", 0)
                        + ", status=" + response.getOrDefault("status", "unknown"));
            } catch (AssistantSdkException ex) {
                printError(out, "skill reload 失败(status=" + ex.statusCode() + ", code=" + ex.errorCode() + "): " + ex.getMessage());
            }
            return true;
        }

        if ("/skill reload-mcp".equalsIgnoreCase(input)) {
            try {
                Map<String, Object> response = chatService.reloadMcpSkills();
                printInfo(out, "MCP 技能已重载：reloaded=" + response.getOrDefault("reloaded", 0)
                        + ", status=" + response.getOrDefault("status", "unknown"));
            } catch (AssistantSdkException ex) {
                printError(out, "skill reload-mcp 失败(status=" + ex.statusCode() + ", code=" + ex.errorCode() + "): " + ex.getMessage());
            }
            return true;
        }

        if (input.startsWith("/skill load-mcp")) {
            Map<String, String> parsed = parseOptionPairs(input.substring("/skill load-mcp".length()).trim());
            String alias = parsed.get("alias");
            String url = parsed.get("url");
            if (alias == null || alias.isBlank() || url == null || url.isBlank()) {
                printInfo(out, "用法：/skill load-mcp --alias docs --url http://localhost:8081/mcp");
                return true;
            }
            try {
                url = UrlSecurityPolicy.requireAllowedSensitiveUrl(url, "url");
            } catch (IllegalArgumentException ex) {
                printError(out, ex.getMessage());
                return true;
            }
            try {
                Map<String, Object> response = chatService.loadMcpServer(alias, url);
                printInfo(out, "MCP server 已加载：alias=" + response.getOrDefault("alias", alias)
                        + ", loaded=" + response.getOrDefault("loaded", 0)
                        + ", status=" + response.getOrDefault("status", "unknown"));
            } catch (AssistantSdkException ex) {
                printError(out, "skill load-mcp 失败(status=" + ex.statusCode() + ", code=" + ex.errorCode() + "): " + ex.getMessage());
            }
            return true;
        }

        if (input.startsWith("/skill load-jar")) {
            Map<String, String> parsed = parseOptionPairs(input.substring("/skill load-jar".length()).trim());
            String url = parsed.get("url");
            if (url == null || url.isBlank()) {
                printInfo(out, "用法：/skill load-jar --url https://example.com/skill-weather.jar");
                return true;
            }
            try {
                url = UrlSecurityPolicy.requireAllowedSensitiveUrl(url, "url");
            } catch (IllegalArgumentException ex) {
                printError(out, ex.getMessage());
                return true;
            }
            try {
                Map<String, Object> response = chatService.loadExternalJar(url);
                printInfo(out, "外部 skill JAR 已加载：url=" + response.getOrDefault("url", url)
                        + ", loaded=" + response.getOrDefault("loaded", 0)
                        + ", status=" + response.getOrDefault("status", "unknown"));
            } catch (AssistantSdkException ex) {
                printError(out, "skill load-jar 失败(status=" + ex.statusCode() + ", code=" + ex.errorCode() + "): " + ex.getMessage());
            }
            return true;
        }

        return false;
    }

    private boolean handleHistoryCommand(String input, PrintWriter out, CliChatService chatService) {
        Map<String, String> parsed = parseOptionPairs(input.substring("/history".length()).trim());
        int limit = parseInt(parsed.get("limit"), 10);
        try {
            List<ConversationTurnDto> turns = chatService.fetchConversationHistory();
            if (turns.isEmpty()) {
                printInfo(out, "当前用户暂无服务端历史记录。");
                return true;
            }
            printInfo(out, "最近 " + Math.min(limit, turns.size()) + " 条服务端历史：");
            int start = Math.max(0, turns.size() - limit);
            for (ConversationTurnDto turn : turns.subList(start, turns.size())) {
                String timestamp = turn.createdAt() == null ? "unknown-time" : TIME_FORMATTER.format(turn.createdAt());
                out.println("- [" + timestamp + "] " + turn.role() + ": " + turn.content());
            }
        } catch (AssistantSdkException ex) {
            printError(out, "history 获取失败(status=" + ex.statusCode() + ", code=" + ex.errorCode() + "): " + ex.getMessage());
        }
        return true;
    }

    private boolean handleRetry(PrintWriter out, CliChatService chatService, SessionState sessionState) {
        if (sessionState.lastUserMessage == null || sessionState.lastUserMessage.isBlank()) {
            printInfo(out, "当前没有可重试的用户消息。");
            return true;
        }
        try {
            ChatResponseDto response = chatService.sendMessage(sessionState.lastUserMessage);
            sessionState.localTurns.add("你> " + sessionState.lastUserMessage + " (retry)");
            sessionState.localTurns.add("助手[" + response.channel() + "]> " + response.reply());
            printInfo(out, "已重试上一条消息：" + sessionState.lastUserMessage);
            printAssistantReply(out, response);
        } catch (AssistantSdkException ex) {
            printError(out, "重试失败(status=" + ex.statusCode() + ", code=" + ex.errorCode() + "): " + ex.getMessage());
        }
        return true;
    }

    private boolean handleProfileCommand(String input, PrintWriter out, CliChatService chatService, BufferedReader reader) {
        if ("/profile show".equalsIgnoreCase(input)) {
            AssistantProfile profile = chatService.loadProfile();
            printProfile(out, profile);
            return true;
        }
        if ("/profile reset".equalsIgnoreCase(input)) {
            chatService.resetProfile();
            printInfo(out, "本地 profile 已重置。当前内容：");
            printProfile(out, chatService.loadProfile());
            return true;
        }
        if ("/profile set".equalsIgnoreCase(input)) {
            return handleProfileSetCommand("", out, chatService, reader);
        }
        if (input.startsWith("/profile set ")) {
            return handleProfileSetCommand(input.substring("/profile set ".length()).trim(), out, chatService, reader);
        }
        return false;
    }

    private boolean handleProfileSetCommand(String arguments,
                                            PrintWriter out,
                                            CliChatService chatService,
                                            BufferedReader reader) {
        Map<String, String> parsed = parseOptionPairs(arguments);
        if (parsed.isEmpty()) {
            parsed = promptForProfileFields(out, reader, chatService.loadProfile(), Map.of());
        } else {
            parsed = promptForProfileFields(out, reader, chatService.loadProfile(), parsed);
        }

        try {
            AssistantProfile updated = chatService.updateProfile(
                    parsed.get("name"),
                    parsed.get("role"),
                    parsed.get("style"),
                    parsed.get("language"),
                    parsed.get("timezone"),
                    parsed.get("llm-provider")
            );
            printInfo(out, "本地 profile 已更新：");
            printProfile(out, updated);
        } catch (IllegalArgumentException ex) {
            printError(out, "profile 更新失败：" + ex.getMessage());
        }
        return true;
    }

    private boolean handleMemoryCommand(String input,
                                        PrintWriter out,
                                        CliChatService chatService,
                                        BufferedReader reader,
                                        SessionState sessionState,
                                        ExecutorService backgroundExecutor) {
        if (input.startsWith("/memory pull")) {
            Map<String, String> parsed = parseOptionPairs(input.substring("/memory pull".length()).trim());
            long since = parseLong(parsed.get("since"), 0L);
            int limit = parseInt(parsed.get("limit"), 100);
            submitBackgroundTask(out, sessionState, backgroundExecutor,
                    "memory pull 已在后台执行",
                    () -> {
                        try {
                            MemorySyncResponseDto response = chatService.pullMemory(since, limit);
                            synchronized (out) {
                                printInfo(out, "memory 拉取完成");
                                out.println("memory.cursor=" + response.cursor());
                                out.println("memory.episodic=" + response.episodic().size());
                                out.println("memory.semantic=" + response.semantic().size());
                                out.println("memory.procedural=" + response.procedural().size());
                                out.flush();
                            }
                        } catch (AssistantSdkException ex) {
                            synchronized (out) {
                                printError(out, "memory pull 失败(status=" + ex.statusCode() + ", code=" + ex.errorCode() + "): " + ex.getMessage());
                                out.flush();
                            }
                        }
                    });
            return true;
        }

        if (input.startsWith("/memory push")) {
            Map<String, String> parsed = parseOptionPairs(input.substring("/memory push".length()).trim());
            int limit = parseInt(parsed.get("limit"), 100);
            String fileValue = parsed.get("file");
            if (fileValue == null || fileValue.isBlank()) {
                return handleInteractiveMemoryPush(out, chatService, reader, limit, sessionState, backgroundExecutor);
            }
            Path file = Path.of(fileValue);
            if (!Files.exists(file)) {
                printError(out, "memory push 失败：文件不存在 - " + file);
                return true;
            }
            submitBackgroundTask(out, sessionState, backgroundExecutor,
                    "memory push 已在后台执行",
                    () -> {
                        try {
                            MemorySyncResponseDto response = chatService.pushMemory(file, limit);
                            synchronized (out) {
                                printInfo(out, "memory 推送完成（已自动整理/压缩/去重）");
                                out.println("memory.cursor=" + response.cursor());
                                out.println("memory.accepted=" + response.acceptedCount());
                                out.println("memory.skipped=" + response.skippedCount());
                                out.flush();
                            }
                        } catch (AssistantSdkException ex) {
                            synchronized (out) {
                                printError(out, "memory push 失败(status=" + ex.statusCode() + ", code=" + ex.errorCode() + "): " + ex.getMessage());
                                out.flush();
                            }
                        } catch (IOException ex) {
                            synchronized (out) {
                                printError(out, "memory push 失败：无法读取 JSON 文件 - " + file);
                                out.flush();
                            }
                        }
                    });
            return true;
        }

        return false;
    }

    private boolean handleInteractiveMemoryPush(PrintWriter out,
                                                CliChatService chatService,
                                                BufferedReader reader,
                                                int limit,
                                                SessionState sessionState,
                                                ExecutorService backgroundExecutor) {
        MemorySyncRequestDto request = promptForMemoryRequest(out, reader, chatService.userId());
        if (request == null) {
            printInfo(out, "已取消 memory push。当前未提交任何记忆。");
            return true;
        }
        if (request.episodic().isEmpty() && request.semantic().isEmpty() && request.procedural().isEmpty()) {
            printInfo(out, "未收集到任何记忆，已取消提交。");
            return true;
        }

        printMemoryPreview(out, request);
        Boolean confirmed = promptYesNo(out, reader, "确认推送这些记忆吗？", true);
        if (confirmed == null || !confirmed) {
            printInfo(out, "已取消 memory push。当前未提交任何记忆。");
            return true;
        }

        submitBackgroundTask(out, sessionState, backgroundExecutor,
                "memory push 已在后台执行",
                () -> {
                    try {
                        MemorySyncResponseDto response = chatService.pushMemory(request, limit);
                        synchronized (out) {
                            printInfo(out, "memory 推送完成（已自动整理/压缩/去重）");
                            out.println("memory.cursor=" + response.cursor());
                            out.println("memory.accepted=" + response.acceptedCount());
                            out.println("memory.skipped=" + response.skippedCount());
                            out.flush();
                        }
                    } catch (AssistantSdkException ex) {
                        synchronized (out) {
                            printError(out, "memory push 失败(status=" + ex.statusCode() + ", code=" + ex.errorCode() + "): " + ex.getMessage());
                            out.flush();
                        }
                    }
                });
        return true;
    }

    private void submitBackgroundTask(PrintWriter out,
                                      SessionState sessionState,
                                      ExecutorService backgroundExecutor,
                                      String submitMessage,
                                      Runnable task) {
        printInfo(out, submitMessage);
        CompletableFuture<Void> future = CompletableFuture.runAsync(task, backgroundExecutor);
        sessionState.pendingTasks.add(future);
    }

    private MemorySyncRequestDto promptForMemoryRequest(PrintWriter out,
                                                        BufferedReader reader,
                                                        String userId) {
        printInfo(out, "进入 memory push 交互模式。输入 cancel 可随时取消。服务端会自动做记忆整理、压缩与去重。");
        String defaultEventId = "cli-" + sanitizeIdentifier(userId) + "-" + Instant.now().toEpochMilli() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        String eventId = promptForMemoryValue(out, reader, "eventId", defaultEventId, false);
        if (eventId == null) {
            return null;
        }

        List<ConversationTurnDto> episodic = new ArrayList<>();
        List<SemanticMemoryEntryDto> semantic = new ArrayList<>();
        List<ProceduralMemoryEntryDto> procedural = new ArrayList<>();

        while (true) {
            String type = promptForMemoryValue(out, reader, "记忆类型(semantic/episodic/procedural)", "semantic", true);
            if (type == null) {
                return null;
            }
            String normalizedType = normalizeMemoryType(type);
            if (normalizedType.isBlank()) {
                normalizedType = "semantic";
            }

            Instant createdAt = Instant.now();
            switch (normalizedType) {
                case "semantic" -> {
                    String text = promptForMemoryValue(out, reader, "记忆内容", null, false);
                    if (text == null) {
                        return null;
                    }
                    semantic.add(new SemanticMemoryEntryDto(text, List.of(), createdAt));
                }
                case "episodic" -> {
                    String role = promptForMemoryValue(out, reader, "对话角色(user/assistant)", "user", true);
                    if (role == null) {
                        return null;
                    }
                    String content = promptForMemoryValue(out, reader, "对话内容", null, false);
                    if (content == null) {
                        return null;
                    }
                    episodic.add(new ConversationTurnDto(role, content, createdAt));
                }
                case "procedural" -> {
                    String skillName = promptForMemoryValue(out, reader, "技能名", null, false);
                    if (skillName == null) {
                        return null;
                    }
                    String input = promptForMemoryValue(out, reader, "技能输入", null, false);
                    if (input == null) {
                        return null;
                    }
                    Boolean success = promptYesNo(out, reader, "是否成功？", true);
                    if (success == null) {
                        return null;
                    }
                    procedural.add(new ProceduralMemoryEntryDto(skillName, input, success, createdAt));
                }
                default -> {
                    printError(out, "不支持的记忆类型：" + type + "。可选 semantic / episodic / procedural。");
                    continue;
                }
            }

            printInfo(out, "当前草稿：episodic=" + episodic.size()
                    + ", semantic=" + semantic.size()
                    + ", procedural=" + procedural.size());
            Boolean continueAdding = promptYesNo(out, reader, "继续添加下一条记忆吗？", false);
            if (continueAdding == null) {
                return null;
            }
            if (!continueAdding) {
                break;
            }
        }

        return new MemorySyncRequestDto(eventId, episodic, semantic, procedural);
    }

    private void printMemoryPreview(PrintWriter out, MemorySyncRequestDto request) {
        out.println(ANSI.string("@|bold 记忆推送预览|@"));
        out.println("eventId=" + request.eventId());
        out.println("episodic=" + request.episodic().size());
        for (ConversationTurnDto turn : request.episodic()) {
            out.println("- [episodic] " + turn.role() + ": " + turn.content());
        }
        out.println("semantic=" + request.semantic().size());
        for (SemanticMemoryEntryDto entry : request.semantic()) {
            out.println("- [semantic] " + entry.text());
        }
        out.println("procedural=" + request.procedural().size());
        for (ProceduralMemoryEntryDto entry : request.procedural()) {
            out.println("- [procedural] " + entry.skillName() + " :: " + entry.input() + " :: success=" + entry.success());
        }
    }

    private String promptForMemoryValue(PrintWriter out,
                                        BufferedReader reader,
                                        String field,
                                        String defaultValue,
                                        boolean allowBlank) {
        try {
            out.print(field + (defaultValue == null ? "" : " [默认=" + defaultValue + "]") + "：");
            out.flush();
            String value = reader.readLine();
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            if (isCancelInput(trimmed)) {
                return null;
            }
            if (trimmed.isBlank()) {
                if (defaultValue != null) {
                    return defaultValue;
                }
                if (allowBlank) {
                    return "";
                }
                printError(out, field + " 不能为空，请重新输入。");
                return promptForMemoryValue(out, reader, field, defaultValue, allowBlank);
            }
            return trimmed;
        } catch (IOException ex) {
            return null;
        }
    }

    private Boolean promptYesNo(PrintWriter out, BufferedReader reader, String prompt, boolean defaultValue) {
        String suffix = defaultValue ? "[Y/n]" : "[y/N]";
        while (true) {
            String value = promptForMemoryValue(out, reader, prompt + " " + suffix, null, true);
            if (value == null) {
                return null;
            }
            if (value.isBlank()) {
                return defaultValue;
            }
            String normalized = value.trim().toLowerCase();
            if (List.of("y", "yes", "true", "1", "是").contains(normalized)) {
                return true;
            }
            if (List.of("n", "no", "false", "0", "否").contains(normalized)) {
                return false;
            }
            printError(out, "请输入 y / n。");
        }
    }

    private boolean isCancelInput(String value) {
        return "cancel".equalsIgnoreCase(value)
                || "/cancel".equalsIgnoreCase(value)
                || "退出".equals(value)
                || "取消".equals(value);
    }

    private String normalizeMemoryType(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return switch (normalized) {
            case "语义", "知识", "semantic" -> "semantic";
            case "对话", "聊天", "episodic" -> "episodic";
            case "流程", "技能", "procedural" -> "procedural";
            default -> normalized;
        };
    }

    private String sanitizeIdentifier(String value) {
        return value == null ? "user" : value.trim().replaceAll("[^a-zA-Z0-9._-]+", "-");
    }

    private String resolveNaturalLanguageCommand(String input) {
        return commandNluParser.resolveNaturalLanguageCommand(input);
    }

    private Map<String, String> parseOptionPairs(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        String[] tokens = arguments.split("\\s+");
        Map<String, String> values = new LinkedHashMap<>();
        String currentKey = null;
        StringBuilder currentValue = new StringBuilder();
        for (String token : tokens) {
            if (token.startsWith("--")) {
                if (currentKey != null) {
                    values.put(currentKey, currentValue.toString().trim());
                }
                currentKey = token.substring(2);
                currentValue = new StringBuilder();
            } else if (currentKey != null) {
                if (currentValue.length() > 0) {
                    currentValue.append(' ');
                }
                currentValue.append(token);
            }
        }
        if (currentKey != null) {
            values.put(currentKey, currentValue.toString().trim());
        }
        return values;
    }

    private void printProfile(PrintWriter out, AssistantProfile profile) {
        out.println(ANSI.string("@|bold 本地 Profile|@"));
        out.println("assistant=" + profile.assistantName());
        out.println("role=" + profile.role());
        out.println("style=" + profile.style());
        out.println("language=" + profile.language());
        out.println("timezone=" + profile.timezone());
        out.println("llm.provider=" + profile.llmProvider());
    }

    private Map<String, String> promptForProfileFields(PrintWriter out,
                                                       BufferedReader reader,
                                                       AssistantProfile current,
                                                       Map<String, String> seed) {
        Map<String, String> values = new LinkedHashMap<>(seed);
        values.putIfAbsent("name", promptForValue(out, reader, "name", current.assistantName()));
        values.putIfAbsent("role", promptForValue(out, reader, "role", current.role()));
        values.putIfAbsent("style", promptForValue(out, reader, "style", current.style()));
        values.putIfAbsent("language", promptForValue(out, reader, "language", current.language()));
        values.putIfAbsent("timezone", promptForValue(out, reader, "timezone", current.timezone()));
        values.putIfAbsent("llm-provider", promptForValue(out, reader, "llm-provider", current.llmProvider()));
        return values;
    }

    private String promptForValue(PrintWriter out, BufferedReader reader, String field, String currentValue) {
        try {
            out.print(field + " [当前=" + (currentValue == null ? "" : currentValue) + "]：");
            out.flush();
            String value = reader.readLine();
            if (value == null || value.isBlank()) {
                return currentValue;
            }
            return value.trim();
        } catch (IOException ex) {
            return currentValue;
        }
    }

    private void printAssistantReply(PrintWriter out, ChatResponseDto response) {
        out.println(ANSI.string("@|bold,green 助手[" + response.channel() + "]|@") + " " + response.reply());
    }

    private void printInfo(PrintWriter out, String message) {
        out.println(ANSI.string("@|cyan [info]|@") + " " + message);
    }

    private void printError(PrintWriter out, String message) {
        out.println(ANSI.string("@|bold,red [error]|@") + " " + message);
    }

    private String renderPrompt(CliChatService chatService) {
        return ANSI.string("@|bold,yellow " + chatService.userId() + "|@") + " > ";
    }

    private long parseLong(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static final class SessionState {
        private final List<String> localTurns = new ArrayList<>();
        private final List<CompletableFuture<Void>> pendingTasks = new CopyOnWriteArrayList<>();
        private String lastUserMessage;

        void clear() {
            localTurns.clear();
            lastUserMessage = null;
        }
    }
}

