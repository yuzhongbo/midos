package com.zhongbo.mindos.assistant.cli;

import com.zhongbo.mindos.assistant.common.dto.ChatResponseDto;
import com.zhongbo.mindos.assistant.common.dto.ConversationTurnDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryCompressionPlanRequestDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryCompressionPlanResponseDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryCompressionStepDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryStyleProfileDto;
import com.zhongbo.mindos.assistant.common.dto.MemorySyncRequestDto;
import com.zhongbo.mindos.assistant.common.dto.MemorySyncResponseDto;
import com.zhongbo.mindos.assistant.common.dto.ProceduralMemoryEntryDto;
import com.zhongbo.mindos.assistant.common.dto.SemanticMemoryEntryDto;
import com.zhongbo.mindos.assistant.common.nlu.MemoryIntentNlu;
import com.zhongbo.mindos.assistant.sdk.AssistantSdkException;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class InteractiveChatRunner {

    private static final String PROP_TODO_P1_THRESHOLD = "mindos.todo.priority.p1-threshold";
    private static final String PROP_TODO_P2_THRESHOLD = "mindos.todo.priority.p2-threshold";
    private static final String PROP_TODO_WINDOW_P1 = "mindos.todo.window.p1";
    private static final String PROP_TODO_WINDOW_P2 = "mindos.todo.window.p2";
    private static final String PROP_TODO_WINDOW_P3 = "mindos.todo.window.p3";
    private static final String PROP_TODO_LEGEND = "mindos.todo.legend";

    private static final List<String> COMMAND_CATALOG = List.of(
            "/help", "/session", "/user", "/server", "/provider", "/history",
            "/retry", "/clear", "/skills", "/skill", "/profile", "/memory", "/todo",
            "/teach", "/theme", "/routing", "/exit", "/quit"
    );

    enum UiTheme {
        CYBER,
        CLASSIC;

        static UiTheme fromValue(String raw) {
            if (raw == null || raw.isBlank()) {
                return CYBER;
            }
            return "classic".equalsIgnoreCase(raw.trim()) ? CLASSIC : CYBER;
        }
    }

    private static final CommandLine.Help.Ansi ANSI = CommandLine.Help.Ansi.AUTO;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_INSTANT;
    private final CommandNluParser commandNluParser = new CommandNluParser();
    private UiTheme uiTheme;
    private boolean showRoutingDetails;

    InteractiveChatRunner() {
        this(UiTheme.CYBER, true);
    }

    InteractiveChatRunner(UiTheme uiTheme) {
        this(uiTheme, true);
    }

    InteractiveChatRunner(UiTheme uiTheme, boolean showRoutingDetails) {
        this.uiTheme = uiTheme == null ? UiTheme.CYBER : uiTheme;
        this.showRoutingDetails = showRoutingDetails;
    }

    void run(PrintWriter out, CliChatService chatService) {
        SessionState sessionState = new SessionState();
        ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mindos-cli-bg");
            thread.setDaemon(true);
            return thread;
        });
        printWelcome(out, chatService, sessionState);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            while (true) {
                drainCompletedTasks(out, chatService, sessionState);
                printStatusBar(out, chatService, sessionState);
                out.print(renderPrompt(chatService));
                out.flush();

                String line = reader.readLine();
                if (line == null) {
                    out.println();
                    out.println("已退出对话模式。再见！");
                    out.flush();
                    return;
                }

                String input = normalizeInteractiveInput(line);
                if (input.isEmpty()) {
                    continue;
                }
                if (isExitCommand(input)) {
                    awaitPendingTasks(out, chatService, sessionState);
                    out.println("已退出对话模式。再见！");
                    out.flush();
                    return;
                }
                if (isHelpCommand(input)) {
                    printHelp(out, input);
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
                    List<String> suggestions = suggestSlashCommands(input);
                    if (suggestions.isEmpty()) {
                        printInfo(out, "未知命令：" + input + "，输入 /help 查看帮助。");
                    } else {
                        printInfo(out,
                                "未知命令：" + input + "，输入 /help 查看帮助。你可能想输入 " + suggestions.get(0)
                                        + "。候选: " + String.join(" ", suggestions));
                    }
                    out.flush();
                    continue;
                }

                CommandNluParser.NaturalLanguageResolution resolution = resolveNaturalLanguage(input);
                String naturalLanguageCommand = resolution.command();
                if (naturalLanguageCommand != null) {
                    if (showRoutingDetails) {
                        printAutoDispatchHint(out, naturalLanguageCommand);
                        printEqNaturalLanguageEnumHints(out, input, naturalLanguageCommand);
                    }
                    if (resolution.isLowConfidence()) {
                        Boolean confirmed = promptYesNo(out,
                                reader,
                                "该自然语言识别置信度较低，是否按该命令执行？",
                                false);
                        if (confirmed == null || !confirmed) {
                            printInfo(out, "已取消命令执行，改为普通对话输入。");
                            naturalLanguageCommand = null;
                        }
                    }
                }
                if (naturalLanguageCommand != null) {
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

                if (handleMemoryReviewFollowUpIfNeeded(input, out, sessionState)) {
                    out.flush();
                    continue;
                }
                if (handleTodoGenerationFollowUpIfNeeded(input, out, sessionState)) {
                    out.flush();
                    continue;
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
        if (uiTheme == UiTheme.CYBER) {
            out.println(ANSI.string("@|faint [Neural Console] low-latency command + chat cockpit|@"));
            out.println(ANSI.string("@|faint ----------------------------------------------------|@"));
        }
        printSession(out, chatService, sessionState);
        out.println(ANSI.string("@|faint 输入 /help 查看帮助（快捷键：/h /?），输入 /exit、/quit 或 :q 退出。|@"));
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

    private void printHelp(PrintWriter out, String rawInput) {
        String normalized = rawInput == null ? "/help" : rawInput.trim().toLowerCase();
        if (normalized.startsWith("/help teach")) {
            printTeachHelp(out);
            return;
        }
        if (normalized.startsWith("/help memory")) {
            printMemoryHelp(out);
            return;
        }
        if (normalized.startsWith("/help skill")) {
            printSkillHelp(out);
            return;
        }
        if (!normalized.startsWith("/help full")) {
            printQuickHelp(out);
            return;
        }

        out.println("可用命令:");
        out.println(ANSI.string("@|bold,cyan [Session]|@"));
        out.println("  /help                                   查看帮助");
        out.println("  /session                                查看当前会话信息");
        out.println("  /user <userId>                          切换当前用户");
        out.println("  /server <url>                           切换服务端地址");
        out.println("  /provider <name|default>                设置当前会话 LLM 提供商覆盖");
        out.println("  /history [--limit N]                    查看当前用户的服务端会话历史");
        out.println("  /retry                                  重试最近一条用户消息");
        out.println("  /clear                                  清空当前窗口本地状态并重新显示头部");
        out.println(ANSI.string("@|bold,cyan [Skills]|@"));
        out.println("  /skills                                 查看当前已注册技能");
        out.println("  /skill list                             查看当前已注册技能");
        out.println("  /skill reload                           重载本地自定义技能");
        out.println("  /skill reload-mcp                       重载已配置 MCP 技能");
        out.println("  /skill load-mcp --alias docs --url ...  动态加载一个 MCP server");
        out.println("  /skill load-jar --url ...               动态加载一个外部 skill JAR");
        out.println(ANSI.string("@|bold,cyan [Profile + Memory]|@"));
        out.println("  /profile show                           查看本地 profile");
        out.println("  /profile set [--field value ...]        更新本地 profile（无参时逐项引导）");
        out.println("         支持: --name --role --style --language --timezone --llm-provider");
        out.println("  /profile reset                          重置本地 profile");
        out.println("  /memory pull [--since N] [--limit N]    拉取增量记忆");
        out.println("  /memory push [--limit N]                在窗口内交互式整理并推送记忆");
        out.println("  /memory push --file path [--limit N]    推送记忆 JSON 文件（兼容模式）");
        out.println("  /memory style show                      查看当前记忆压缩风格");
        out.println("  /memory style set --style-name 名称     更新记忆压缩风格");
        out.println("  /memory style set --auto-tune --sample-text 文本  自动微调记忆风格");
        out.println("  /memory compress --source 文本 [--focus learning|task|review]  生成逐步记忆压缩规划");
        out.println("  /todo policy [show]                     查看当前会话待办策略");
        out.println("  /todo policy set --p1-threshold N --p2-threshold N [--window-p1 文案 --window-p2 文案 --window-p3 文案 --legend 文案]");
        out.println("  /todo policy reset                      恢复当前会话待办策略默认值");
        out.println("  /teach plan [--query 文本]              生成教学规划（自动转 teaching.plan skill）");
        out.println("  /eq coach [--query 文本] [--style gentle|direct|workplace|intimate] [--mode analysis|reply|both] [--priority-focus p1|p2|p3]  生成高情商沟通建议");
        out.println(ANSI.string("@|bold,cyan [Shortcuts]|@"));
        out.println("  自然语言指令示例                          我有哪些技能 / 帮我拉取记忆 / 加载jar https://...");
        out.println("  重要命令会二次确认                        例如重置配置、加载外部技能、切换 server");
        out.println("  /h 或 /?                                 快速查看帮助");
        out.println("  :q                                       快速退出对话模式");
        out.println("  /theme [cyber|classic]                  切换界面主题（默认 cyber）");
        out.println("  /routing [on|off]                       打开/关闭路由细节显示（排障模式）");
        out.println("  /help full                              查看完整帮助");
        out.println("  /help teach|memory|skill                查看场景化帮助");
        out.println(ANSI.string("@|bold,cyan [Exit]|@"));
        out.println("  /exit                                   退出对话模式");
        out.println("  /quit                                   退出对话模式");
        out.flush();
    }

    private void printQuickHelp(PrintWriter out) {
        out.println("自然语言使用指南:");
        out.println("  你可以直接说：我有哪些技能 / 帮我拉取记忆 / 给学生做学习计划");
        out.println("  你可以直接说：高情商回复这个场景：...（自动路由到 eq.coach）");
        out.println("  你可以直接说：打开排障模式 / 关闭排障模式");
        out.println("  你可以直接说：查看会话信息 / 重试刚才那条 / 清空窗口");
        out.println("  你可以直接说：退出 / 结束");
        out.println("  如需查看技术命令与参数：输入 /help full");
        out.println("  场景化帮助：/help teach  /help memory  /help skill");
        out.flush();
    }

    private void printTeachHelp(PrintWriter out) {
        out.println("教学规划（自然语言）:");
        out.println("  直接说需求即可，例如：给学生 stu-1 做数学学习计划，六周，每周八小时，目标是期末提分");
        out.println("  不需要手动写参数；如需技术命令格式请看 /help full");
        out.flush();
    }

    private void printMemoryHelp(PrintWriter out) {
        out.println("记忆同步（自然语言）:");
        out.println("  拉取：直接说“帮我拉取最近 30 条记忆”或“从 12 开始拉取记忆”");
        out.println("  推送：直接说“帮我保存 20 条记忆”或“开始记忆推送”");
        out.println("  风格：直接说“查看我的记忆风格”或“把记忆风格改成 action，语气 warm”");
        out.println("  微调：直接说“根据这段话自动微调记忆风格：...”");
        out.println("  压缩：直接说“按我的风格压缩这段记忆：...”");
        out.println("  待办策略：直接说“查看待办策略”或“恢复待办策略默认”");
        out.println("  如需技术命令格式请看 /help full");
        out.flush();
    }

    private void printSkillHelp(PrintWriter out) {
        out.println("技能管理（自然语言）:");
        out.println("  查看技能：直接说“我有哪些技能”");
        out.println("  刷新技能：直接说“重载技能”或“重载 mcp”");
        out.println("  接入扩展：直接说“请接入 mcp https://...，简称 docs”或“请加载 jar https://...”");
        out.println("  如需技术命令格式请看 /help full");
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

        if (input.startsWith("/teach")) {
            return handleTeachCommand(input, out, chatService, reader, sessionState);
        }

        if (input.startsWith("/eq")) {
            return handleEqCommand(input, out, chatService, reader, sessionState);
        }

        if (input.startsWith("/memory")) {
            return handleMemoryCommand(input, out, chatService, reader, sessionState, backgroundExecutor);
        }

        if (input.startsWith("/todo")) {
            return handleTodoCommand(input, out, sessionState);
        }

        if (input.startsWith("/theme")) {
            return handleThemeCommand(input, out);
        }

        if (input.startsWith("/routing") || input.startsWith("/debug")) {
            return handleRoutingCommand(input, out);
        }

        return false;
    }

    private boolean handleRoutingCommand(String input, PrintWriter out) {
        String argument;
        if (input.startsWith("/debug")) {
            argument = input.substring("/debug".length()).trim();
        } else {
            argument = input.substring("/routing".length()).trim();
        }
        if (argument.isBlank() || "show".equalsIgnoreCase(argument)) {
            printInfo(out, "当前模式: " + (showRoutingDetails ? "排障视图（显示路由细节）" : "自然语言视图（隐藏路由细节）"));
            return true;
        }
        if ("on".equalsIgnoreCase(argument) || "true".equalsIgnoreCase(argument)) {
            showRoutingDetails = true;
            printInfo(out, "已切换为排障视图：将显示路由与技能细节。");
            return true;
        }
        if ("off".equalsIgnoreCase(argument) || "false".equalsIgnoreCase(argument)) {
            showRoutingDetails = false;
            printInfo(out, "已切换为自然语言视图：仅显示对话结果。");
            return true;
        }
        printError(out, "用法: /routing [on|off]");
        return true;
    }

    private boolean handleThemeCommand(String input, PrintWriter out) {
        String argument = input.substring("/theme".length()).trim();
        if (argument.isBlank()) {
            printInfo(out, "当前主题: " + uiTheme.name().toLowerCase());
            return true;
        }
        uiTheme = UiTheme.fromValue(argument);
        printInfo(out, "已切换主题: " + uiTheme.name().toLowerCase());
        return true;
    }

    private boolean handleTodoCommand(String input, PrintWriter out, SessionState sessionState) {
        String args = input.substring("/todo".length()).trim();
        if (args.isBlank() || args.equalsIgnoreCase("policy") || args.equalsIgnoreCase("policy show")) {
            TodoPriorityPolicy policy = resolveTodoPriorityPolicy(sessionState);
            printInfo(out, "当前待办策略：P1>= " + policy.p1Threshold()
                    + "，P2>= " + policy.p2Threshold()
                    + "；P1=" + policy.p1Window()
                    + "，P2=" + policy.p2Window()
                    + "，P3=" + policy.p3Window() + "。"
            );
            out.println(policy.legend());
            return true;
        }

        if (args.equalsIgnoreCase("policy reset")) {
            sessionState.todoPolicyOverride = null;
            printInfo(out, "已恢复会话待办策略默认值。");
            return true;
        }

        if (args.toLowerCase().startsWith("policy set")) {
            Map<String, String> parsed = parseOptionPairs(args.substring("policy set".length()).trim());
            TodoPriorityPolicy base = resolveTodoPriorityPolicy(sessionState);
            int p1 = parsePositiveInt(parsed.get("p1-threshold"), base.p1Threshold());
            int p2 = parsePositiveInt(parsed.get("p2-threshold"), base.p2Threshold());
            if (p2 > p1) {
                p2 = p1;
            }
            String w1 = fallbackText(parsed.get("window-p1"), base.p1Window());
            String w2 = fallbackText(parsed.get("window-p2"), base.p2Window());
            String w3 = fallbackText(parsed.get("window-p3"), base.p3Window());
            String legend = fallbackText(parsed.get("legend"), base.legend());
            sessionState.todoPolicyOverride = new TodoPriorityPolicy(p1, p2, w1, w2, w3, legend);
            printInfo(out, "会话待办策略已更新。输入 /todo policy 查看当前策略。");
            return true;
        }

        if (args.toLowerCase().startsWith("policy ")) {
            String candidate = args.substring("policy".length()).trim();
            String subCommand = candidate.isBlank() ? "" : candidate.split("\\s+")[0];
            String suggested = suggestClosestEnumValue(subCommand, List.of("show", "set", "reset"));
            if (suggested != null) {
                printError(out, "不支持的 policy 子命令：" + subCommand + "。可选值：show|set|reset");
                printInfo(out, "猜你想用 /todo policy " + suggested + "。可直接重试该命令。");
                return true;
            }
        }

        printError(out, "用法: /todo policy [show|set|reset]");
        return true;
    }

    private int parsePositiveInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String fallbackText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
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

    private boolean handleTeachCommand(String input,
                                       PrintWriter out,
                                       CliChatService chatService,
                                       BufferedReader reader,
                                       SessionState sessionState) {
        if (!input.startsWith("/teach plan")) {
            return false;
        }

        Map<String, String> parsed = parseOptionPairs(input.substring("/teach plan".length()).trim());
        String query = parsed.get("query");
        if (query == null || query.isBlank()) {
            query = promptForMemoryValue(out, reader, "请输入教学规划需求", null, false);
            if (query == null || query.isBlank()) {
                printInfo(out, "已取消教学规划生成。");
                return true;
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>(commandNluParser.parseTeachingPlanInput(query));
        payload.putIfAbsent("topic", query.trim());
        String dslMessage = buildSkillDslJson("teaching.plan", payload);

        try {
            ChatResponseDto response = chatService.sendMessage(dslMessage);
            sessionState.lastUserMessage = query;
            sessionState.localTurns.add("你> " + query + " (teaching-plan)");
            sessionState.localTurns.add("助手[" + response.channel() + "]> " + response.reply());
            printAssistantReply(out, response);
        } catch (AssistantSdkException ex) {
            printError(out, "teaching.plan 执行失败(status=" + ex.statusCode()
                    + ", code=" + ex.errorCode() + "): " + ex.getMessage());
        }
        return true;
    }

    private boolean handleEqCommand(String input,
                                    PrintWriter out,
                                    CliChatService chatService,
                                    BufferedReader reader,
                                    SessionState sessionState) {
        if (!input.startsWith("/eq ")) {
            return false;
        }
        if (!input.startsWith("/eq coach")) {
            printError(out, "用法：/eq coach --query 场景 [--style gentle|direct|workplace|intimate] [--mode analysis|reply|both] [--priority-focus p1|p2|p3]");
            return true;
        }

        Map<String, String> parsed = parseOptionPairs(input.substring("/eq coach".length()).trim());
        for (String key : parsed.keySet()) {
            if (!List.of("query", "style", "mode", "priority-focus", "priorityFocus").contains(key)) {
                printError(out, "不支持的参数 --" + key + "。仅支持 query/style/mode/priority-focus。");
                return true;
            }
        }
        String query = parsed.get("query");
        if (query == null || query.isBlank()) {
            query = promptForMemoryValue(out, reader, "请输入沟通场景", null, false);
            if (query == null || query.isBlank()) {
                printInfo(out, "已取消情商沟通建议生成。");
                return true;
            }
        }
        query = query.trim();
        if (query.isBlank()) {
            printError(out, "query 不能为空。请提供具体场景。");
            return true;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        String rawStyle = parsed.get("style");
        String style = normalizeEqStyleValue(rawStyle);
        if (rawStyle != null && !rawStyle.isBlank() && style == null) {
            printError(out, "style 非法：" + rawStyle + "。可选值：gentle|direct|workplace|intimate");
            printSlashEnumSuggestion(out, "style", rawStyle, List.of("gentle", "direct", "workplace", "intimate"));
            return true;
        }
        if (style != null && !style.isBlank()) {
            payload.put("style", style);
        }
        String rawMode = parsed.get("mode");
        String mode = normalizeEqModeValue(rawMode);
        if (rawMode != null && !rawMode.isBlank() && mode == null) {
            printError(out, "mode 非法：" + rawMode + "。可选值：analysis|reply|both");
            printSlashEnumSuggestion(out, "mode", rawMode, List.of("analysis", "reply", "both"));
            return true;
        }
        if (mode != null && !mode.isBlank()) {
            payload.put("mode", mode);
        }
        String priorityRaw = parsed.containsKey("priority-focus") ? parsed.get("priority-focus") : parsed.get("priorityFocus");
        String priorityFocus = normalizeEqPriorityFocusValue(priorityRaw);
        if (priorityRaw != null && !priorityRaw.isBlank() && priorityFocus == null) {
            printError(out, "priority-focus 非法：" + priorityRaw + "。可选值：p1|p2|p3");
            printSlashEnumSuggestion(out, "priority-focus", priorityRaw, List.of("p1", "p2", "p3"));
            return true;
        }
        if (priorityFocus != null && !priorityFocus.isBlank()) {
            payload.put("priorityFocus", priorityFocus);
        }

        String dslMessage = buildSkillDslJson("eq.coach", payload);
        try {
            ChatResponseDto response = chatService.sendMessage(dslMessage);
            sessionState.lastUserMessage = query;
            sessionState.localTurns.add("你> " + query + " (eq-coach)");
            sessionState.localTurns.add("助手[" + response.channel() + "]> " + response.reply());
            printAssistantReply(out, response);
        } catch (AssistantSdkException ex) {
            printError(out, "eq.coach 执行失败(status=" + ex.statusCode()
                    + ", code=" + ex.errorCode() + "): " + ex.getMessage());
        }
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
                    parsed.get("llm-provider"),
                    parsed.get("llm-preset")
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
        if (input.startsWith("/memory style")) {
            return handleMemoryStyleCommand(input, out, chatService, reader);
        }

        if (input.startsWith("/memory compress")) {
            return handleMemoryCompressCommand(input, out, chatService, reader, sessionState);
        }

        if (input.startsWith("/memory pull")) {
            Map<String, String> parsed = parseOptionPairs(input.substring("/memory pull".length()).trim());
            long since = parseLong(parsed.get("since"));
            int limit = parseInt(parsed.get("limit"), 100);
            submitBackgroundTask(out, sessionState, backgroundExecutor,
                    "memory pull 已在后台执行",
                    () -> {
                        try {
                            MemorySyncResponseDto response = chatService.pullMemory(since, limit);
                            synchronized (out) {
                                printInfo(out, "memory 拉取完成");
                                out.println("已拉取记忆：对话 " + response.episodic().size()
                                        + " 条，知识 " + response.semantic().size()
                                        + " 条，流程 " + response.procedural().size() + " 条。");
                                if (showRoutingDetails) {
                                    out.println("memory.cursor=" + response.cursor());
                                    out.println("memory.episodic=" + response.episodic().size());
                                    out.println("memory.semantic=" + response.semantic().size());
                                    out.println("memory.procedural=" + response.procedural().size());
                                }
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
                                printMemoryApplyStats(out, response);
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

    private boolean handleMemoryStyleCommand(String input,
                                             PrintWriter out,
                                             CliChatService chatService,
                                             BufferedReader reader) {
        if ("/memory style".equalsIgnoreCase(input) || "/memory style show".equalsIgnoreCase(input)) {
            try {
                printMemoryStyle(out, chatService.getMemoryStyleProfile());
            } catch (AssistantSdkException ex) {
                printError(out, "memory style 获取失败(status=" + ex.statusCode() + ", code=" + ex.errorCode() + "): " + ex.getMessage());
            }
            return true;
        }

        if (input.startsWith("/memory style set")) {
            Map<String, String> parsed = parseOptionPairs(input.substring("/memory style set".length()).trim());
            boolean autoTune = parseBooleanFlag(parsed);
            String sampleText = parsed.get("sample-text");
            String styleName = parsed.get("style-name");

            String rawTone = parsed.get("tone");
            String tone = normalizeMemoryTone(rawTone);
            if (rawTone != null && !rawTone.isBlank() && tone == null) {
                printError(out, "tone 非法：" + rawTone + "。可选值：warm|direct|neutral");
                printSlashEnumSuggestion(out, "tone", rawTone, List.of("warm", "direct", "neutral"));
                return true;
            }

            String rawOutputFormat = parsed.get("output-format");
            String outputFormat = normalizeMemoryOutputFormat(rawOutputFormat);
            if (rawOutputFormat != null && !rawOutputFormat.isBlank() && outputFormat == null) {
                printError(out, "output-format 非法：" + rawOutputFormat + "。可选值：plain|bullet");
                printSlashEnumSuggestion(out, "output-format", rawOutputFormat, List.of("plain", "bullet"));
                return true;
            }

            if (!autoTune && (styleName == null || styleName.isBlank())) {
                styleName = promptForMemoryValue(out, reader, "记忆风格名称", null, false);
                if (styleName == null) {
                    printInfo(out, "已取消记忆风格更新。");
                    return true;
                }
            }
            if (tone == null) {
                tone = promptForMemoryValue(out, reader, "语气（可留空）", "", true);
                if (tone == null) {
                    printInfo(out, "已取消记忆风格更新。");
                    return true;
                }
            }
            if (outputFormat == null) {
                outputFormat = promptForMemoryValue(out, reader, "输出格式（plain/bullet，可留空）", "", true);
                if (outputFormat == null) {
                    printInfo(out, "已取消记忆风格更新。");
                    return true;
                }
            }
            if (autoTune && (sampleText == null || sampleText.isBlank())) {
                sampleText = promptForMemoryValue(out, reader, "用于自动微调的样本文本", null, false);
                if (sampleText == null) {
                    printInfo(out, "已取消记忆风格更新。");
                    return true;
                }
            }
            try {
                MemoryStyleProfileDto response = chatService.updateMemoryStyleProfile(
                        new MemoryStyleProfileDto(blankToNull(styleName), blankToNull(tone), blankToNull(outputFormat)),
                        autoTune,
                        blankToNull(sampleText)
                );
                printInfo(out, "记忆风格已更新。");
                printMemoryStyle(out, response);
            } catch (AssistantSdkException ex) {
                printError(out, "memory style 更新失败(status=" + ex.statusCode() + ", code=" + ex.errorCode() + "): " + ex.getMessage());
            }
            return true;
        }
        return false;
    }

    private boolean handleMemoryCompressCommand(String input,
                                                PrintWriter out,
                                                CliChatService chatService,
                                                BufferedReader reader,
                                                SessionState sessionState) {
        Map<String, String> parsed = parseOptionPairs(input.substring("/memory compress".length()).trim());
        String source = parsed.get("source");
        if (source == null || source.isBlank()) {
            source = promptForMemoryValue(out, reader, "请输入需要压缩的记忆内容", null, false);
            if (source == null) {
                printInfo(out, "已取消记忆压缩规划。");
                return true;
            }
        }
        try {
            MemoryCompressionPlanResponseDto response = chatService.buildMemoryCompressionPlan(
                    new MemoryCompressionPlanRequestDto(
                            source,
                            blankToNull(parsed.get("style-name")),
                            blankToNull(parsed.get("tone")),
                            blankToNull(parsed.get("output-format")),
                            blankToNull(parsed.get("focus"))
                    )
            );
            printMemoryCompressionPlan(out, response, sessionState);
        } catch (AssistantSdkException ex) {
            printError(out, "memory compress 失败(status=" + ex.statusCode() + ", code=" + ex.errorCode() + "): " + ex.getMessage());
        }
        return true;
    }

    private boolean handleMemoryReviewFollowUpIfNeeded(String input, PrintWriter out, SessionState sessionState) {
        String source = sessionState.pendingMemoryReviewSource;
        if (source == null || source.isBlank()) {
            return false;
        }
        if (!MemoryIntentNlu.isAffirmativeIntent(input)) {
            return false;
        }
        List<String> keyPoints = extractReviewPoints(source);
        if (keyPoints.isEmpty()) {
            printInfo(out, "我这边暂时没提炼出关键点，你可以继续补充原文，我再帮你复核。");
            sessionState.pendingMemoryReviewSource = null;
            return true;
        }
        printInfo(out, "好的，我整理了原文关键点，建议你逐条确认：");
        for (int i = 0; i < keyPoints.size(); i++) {
            out.println((i + 1) + ") " + keyPoints.get(i));
        }
        sessionState.pendingMemoryReviewSource = null;
        sessionState.pendingMemoryReviewPoints = List.copyOf(keyPoints);
        out.println("如果你愿意，回复“生成待办”，我可以把这些关键点转成执行清单。");
        return true;
    }

    private boolean handleTodoGenerationFollowUpIfNeeded(String input, PrintWriter out, SessionState sessionState) {
        if (!isTodoGenerationIntent(input)) {
            return false;
        }
        List<String> points = sessionState.pendingMemoryReviewPoints;
        if (points == null || points.isEmpty()) {
            return false;
        }
        printInfo(out, "好的，已根据关键点整理执行清单：");
        printBucketedTodoList(out, points, sessionState);
        sessionState.pendingMemoryReviewPoints = null;
        return true;
    }

    private void printBucketedTodoList(PrintWriter out, List<String> points, SessionState sessionState) {
        TodoPriorityPolicy policy = resolveTodoPriorityPolicy(sessionState);
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
        out.println(policy.legend());
        out.println("当前待办策略：P1>= " + policy.p1Threshold()
                + "，P2>= " + policy.p2Threshold()
                + "；P1=" + policy.p1Window()
                + "，P2=" + policy.p2Window()
                + "，P3=" + policy.p3Window() + "。"
        );
        printTodoBucket(out, "今天（today）", today, policy);
        printTodoBucket(out, "本周（this week）", thisWeek, policy);
        printTodoBucket(out, "后续（later）", later, policy);
        if (today.isEmpty() && thisWeek.isEmpty() && later.isEmpty()) {
            out.println("1) 暂无可执行条目，请补充更具体的行动描述。");
        }
    }

    private void printTodoBucket(PrintWriter out, String title, List<String> items, TodoPriorityPolicy policy) {
        if (items.isEmpty()) {
            return;
        }
        out.println("[" + title + "]");
        for (int i = 0; i < items.size(); i++) {
            out.println((i + 1) + ") " + formatTodoItem(items.get(i), policy));
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

    private TodoPriorityPolicy resolveTodoPriorityPolicy(SessionState sessionState) {
        if (sessionState != null && sessionState.todoPolicyOverride != null) {
            return sessionState.todoPolicyOverride;
        }
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
        String normalized = normalizeMemoryText(point).toLowerCase();
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
        return normalizeMemoryText(point)
                .replaceFirst("^(请|需要|要|必须|务必|尽快)\\s*", "")
                .trim();
    }

    private List<String> sortByPriority(List<String> points) {
        return points.stream()
                .sorted((left, right) -> Integer.compare(priorityScore(right), priorityScore(left)))
                .toList();
    }

    private int priorityScore(String point) {
        String normalized = normalizeMemoryText(point).toLowerCase();
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
        if (containsKeySignal(normalized)) {
            score += 10;
        }
        return score;
    }

    private String classifyBucket(String point) {
        String normalized = normalizeMemoryText(point).toLowerCase();
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

    private boolean isTodoGenerationIntent(String input) {
        String normalized = normalizeMemoryText(input).toLowerCase();
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.contains("生成待办")
                || normalized.contains("转成待办")
                || normalized.contains("行动清单")
                || normalized.contains("todo list")
                || normalized.equals("待办")
                || normalized.equals("todo");
    }

    private List<String> extractReviewPoints(String sourceText) {
        String normalized = normalizeMemoryText(sourceText);
        if (normalized.isBlank()) {
            return List.of();
        }
        String[] parts = normalized.split("[\\n。！？!?；;]+");
        LinkedHashSet<String> prioritized = new LinkedHashSet<>();
        LinkedHashSet<String> fallback = new LinkedHashSet<>();
        for (String part : parts) {
            String line = normalizeMemoryText(part);
            if (line.isBlank()) {
                continue;
            }
            if (containsKeySignal(line)) {
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

    private String normalizeMemoryText(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace('\u3000', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n+ *", "\\n")
                .trim();
    }

    private boolean containsKeySignal(String text) {
        String normalized = normalizeMemoryText(text).toLowerCase();
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.matches(".*\\d.*")
                || normalized.matches(".*(\\d{1,2}[:：]\\d{2}|\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d+\\s*(天|周|月|年|小时|分钟)).*")
                || normalized.contains("截止")
                || normalized.contains("deadline")
                || normalized.contains("必须")
                || normalized.contains("不要")
                || normalized.contains("不能")
                || normalized.contains("禁止")
                || normalized.contains("风险")
                || normalized.contains("不可")
                || normalized.contains("http://")
                || normalized.contains("https://")
                || normalized.contains("@");
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
                            printMemoryApplyStats(out, response);
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

    private void printMemoryApplyStats(PrintWriter out, MemorySyncResponseDto response) {
        int processed = response.acceptedCount() + response.skippedCount();
        double dedupRate = processed == 0 ? 0.0 : (double) response.deduplicatedCount() / processed;
        out.println("本次记忆处理完成：接收 " + response.acceptedCount()
                + " 条，跳过 " + response.skippedCount()
                + " 条，去重 " + response.deduplicatedCount()
                + " 条（" + formatPercent(dedupRate) + "）。");
        if (response.keySignalInputCount() > 0) {
            out.println("关键信息保留 "
                    + response.keySignalStoredCount() + "/" + response.keySignalInputCount() + " 条。");
        }
        if (showRoutingDetails) {
            out.println("memory.cursor=" + response.cursor());
            out.println("memory.accepted=" + response.acceptedCount());
            out.println("memory.skipped=" + response.skippedCount());
            out.println("memory.deduplicated=" + response.deduplicatedCount());
            out.println("memory.deduplicatedRate=" + Math.round(dedupRate * 10_000d) / 100d + "%");
            out.println("memory.keySignalInput=" + response.keySignalInputCount());
            out.println("memory.keySignalStored=" + response.keySignalStoredCount());
        }
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

    private CommandNluParser.NaturalLanguageResolution resolveNaturalLanguage(String input) {
        return commandNluParser.resolveNaturalLanguage(input);
    }

    private String normalizeInteractiveInput(String rawInput) {
        if (rawInput == null) {
            return "";
        }
        String normalized = rawInput.trim();
        if (normalized.isEmpty()) {
            return normalized;
        }
        if (normalized.startsWith("／")) {
            normalized = '/' + normalized.substring(1);
        }
        if ("?".equals(normalized) || "？".equals(normalized)
                || "/h".equalsIgnoreCase(normalized) || "/?".equals(normalized)) {
            return "/help";
        }
        if (":q".equalsIgnoreCase(normalized) || ":quit".equalsIgnoreCase(normalized)) {
            return "/exit";
        }
        return normalized;
    }

    private boolean isExitCommand(String input) {
        return "/exit".equalsIgnoreCase(input) || "/quit".equalsIgnoreCase(input);
    }

    private boolean isHelpCommand(String input) {
        return input != null && input.toLowerCase().startsWith("/help");
    }

    private List<String> suggestSlashCommands(String input) {
        if (input == null || input.isBlank() || !input.startsWith("/")) {
            return List.of();
        }
        String token = input.trim().split("\\s+")[0].toLowerCase();
        List<String> prefixMatches = new ArrayList<>();
        for (String candidate : COMMAND_CATALOG) {
            if (candidate.equals(token)) {
                return List.of();
            }
            if (candidate.startsWith(token) || token.startsWith(candidate)) {
                prefixMatches.add(candidate);
            }
        }
        if (!prefixMatches.isEmpty()) {
            return prefixMatches.subList(0, Math.min(3, prefixMatches.size()));
        }
        List<String> ranked = new ArrayList<>();
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : COMMAND_CATALOG) {
            int distance = levenshtein(token, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
            }
            ranked.add(candidate + "#" + distance);
        }
        if (bestDistance > 2) {
            return List.of();
        }
        ranked.sort((a, b) -> Integer.compare(
                Integer.parseInt(a.substring(a.indexOf('#') + 1)),
                Integer.parseInt(b.substring(b.indexOf('#') + 1))));
        List<String> suggestions = new ArrayList<>();
        for (String value : ranked) {
            if (suggestions.size() >= 3) {
                break;
            }
            String candidate = value.substring(0, value.indexOf('#'));
            if (!suggestions.contains(candidate)) {
                suggestions.add(candidate);
            }
        }
        return suggestions;
    }

    private void printStatusBar(PrintWriter out, CliChatService chatService, SessionState sessionState) {
        String status = "[status] user=" + chatService.userId()
                + " provider=" + chatService.resolvedLlmProvider()
                + " theme=" + uiTheme.name().toLowerCase()
                + " pending=" + sessionState.pendingTasks.size();
        out.println(ANSI.string("@|faint " + status + "|@"));
    }

    private void printAutoDispatchHint(PrintWriter out, String command) {
        printInfo(out, "已识别自然语言指令 -> " + command + "（自动调度）");
    }

    private void printEqNaturalLanguageEnumHints(PrintWriter out, String input, String command) {
        if (input == null || command == null || !command.startsWith("/eq coach")) {
            return;
        }
        String normalized = input.toLowerCase();
        if ((normalized.contains("风格") || normalized.contains("版本") || normalized.contains("style"))
                && !command.contains("--style ")) {
            printInfo(out, "未识别到 style 参数，建议可用值：gentle|direct|workplace|intimate");
        }
        if ((normalized.contains("模式") || normalized.contains("mode"))
                && !command.contains("--mode ")) {
            printInfo(out, "未识别到 mode 参数，建议可用值：analysis|reply|both");
        }
        if ((normalized.contains("优先级")
                || normalized.contains("focus")
                || normalized.contains("最高优先")
                || normalized.contains("最紧急"))
                && !command.contains("--priority-focus ")) {
            printInfo(out, "未识别到 priority-focus 参数，建议可用值：p1|p2|p3");
        }
    }

    private void printSlashEnumSuggestion(PrintWriter out,
                                          String option,
                                          String actual,
                                          List<String> allowedValues) {
        String best = suggestClosestEnumValue(actual, allowedValues);
        if (best != null) {
            printInfo(out, "猜你想用 --" + option + " " + best + "。可直接重试该命令。");
        }
    }

    private String suggestClosestEnumValue(String actual, List<String> allowedValues) {
        if (actual == null || actual.isBlank() || allowedValues == null || allowedValues.isEmpty()) {
            return null;
        }
        String normalizedActual = actual.trim().toLowerCase();
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : allowedValues) {
            int distance = levenshtein(normalizedActual, candidate.toLowerCase());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        if (best != null && bestDistance <= Math.max(2, best.length() / 3)) {
            return best;
        }
        return null;
    }

    private String normalizeMemoryTone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase();
        if (normalized.contains("warm") || normalized.contains("温和") || normalized.contains("友好")) {
            return "warm";
        }
        if (normalized.contains("direct") || normalized.contains("直接")) {
            return "direct";
        }
        if (normalized.contains("neutral") || normalized.contains("中性") || normalized.contains("客观")) {
            return "neutral";
        }
        return null;
    }

    private String normalizeMemoryOutputFormat(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase();
        if ("plain".equals(normalized)
                || "纯文本".equals(normalized)
                || "文本".equals(normalized)) {
            return "plain";
        }
        if ("bullet".equals(normalized)
                || "列表".equals(normalized)
                || "要点".equals(normalized)) {
            return "bullet";
        }
        return null;
    }

    private String renderPrompt(CliChatService chatService) {
        String user = chatService == null ? "user" : chatService.userId();
        if (uiTheme == UiTheme.CLASSIC) {
            return user + "> ";
        }
        return ANSI.string("@|bold,cyan " + user + "> |@");
    }

    private void printInfo(PrintWriter out, String message) {
        out.println(message);
    }

    private void printError(PrintWriter out, String message) {
        out.println("[error] " + message);
    }

    private void printAssistantReply(PrintWriter out, ChatResponseDto response) {
        if (response == null) {
            return;
        }
        out.println("助手[" + response.channel() + "] " + response.reply());
    }

    private void printProfile(PrintWriter out, AssistantProfile profile) {
        if (profile == null) {
            printInfo(out, "profile 为空。");
            return;
        }
        out.println("assistant=" + safe(profile.assistantName()));
        out.println("role=" + safe(profile.role()));
        out.println("style=" + safe(profile.style()));
        out.println("language=" + safe(profile.language()));
        out.println("timezone=" + safe(profile.timezone()));
        out.println("llm.provider=" + safe(profile.llmProvider()));
        out.println("llm.preset=" + safe(profile.llmPreset()));
    }

    private Map<String, String> promptForProfileFields(PrintWriter out,
                                                        BufferedReader reader,
                                                        AssistantProfile current,
                                                        Map<String, String> initialValues) {
        Map<String, String> result = new LinkedHashMap<>();
        if (initialValues != null) {
            result.putAll(initialValues);
        }
        putIfBlank(result, "name", promptForMemoryValue(out, reader,
                "name [当前=" + safe(current == null ? null : current.assistantName()) + "]",
                safe(current == null ? null : current.assistantName()), true));
        putIfBlank(result, "role", promptForMemoryValue(out, reader,
                "role [当前=" + safe(current == null ? null : current.role()) + "]",
                safe(current == null ? null : current.role()), true));
        putIfBlank(result, "style", promptForMemoryValue(out, reader,
                "style [当前=" + safe(current == null ? null : current.style()) + "]",
                safe(current == null ? null : current.style()), true));
        putIfBlank(result, "language", promptForMemoryValue(out, reader,
                "language [当前=" + safe(current == null ? null : current.language()) + "]",
                safe(current == null ? null : current.language()), true));
        putIfBlank(result, "timezone", promptForMemoryValue(out, reader,
                "timezone [当前=" + safe(current == null ? null : current.timezone()) + "]",
                safe(current == null ? null : current.timezone()), true));
        putIfBlank(result, "llm-provider", promptForMemoryValue(out, reader,
                "llm-provider [当前=" + safe(current == null ? null : current.llmProvider()) + "]",
                safe(current == null ? null : current.llmProvider()), true));
        putIfBlank(result, "llm-preset", promptForMemoryValue(out, reader,
                "llm-preset [当前=" + safe(current == null ? null : current.llmPreset()) + "]",
                safe(current == null ? null : current.llmPreset()), true));
        return result;
    }

    private void putIfBlank(Map<String, String> target, String key, String value) {
        if (!target.containsKey(key) || target.get(key) == null || target.get(key).isBlank()) {
            target.put(key, value);
        }
    }

    private Map<String, String> parseOptionPairs(String arguments) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (arguments == null || arguments.isBlank()) {
            return parsed;
        }
        List<String> tokens = tokenizeArguments(arguments);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (!token.startsWith("--") || token.length() <= 2) {
                continue;
            }
            String key = token.substring(2);
            String value = "true";
            if (i + 1 < tokens.size() && !tokens.get(i + 1).startsWith("--")) {
                if ("query".equals(key)) {
                    StringBuilder merged = new StringBuilder();
                    int j = i + 1;
                    while (j < tokens.size() && !tokens.get(j).startsWith("--")) {
                        if (!merged.isEmpty()) {
                            merged.append(' ');
                        }
                        merged.append(tokens.get(j));
                        j++;
                    }
                    value = merged.toString();
                    i = j - 1;
                } else {
                    value = tokens.get(i + 1);
                    i++;
                }
            }
            parsed.put(key, value);
        }
        return parsed;
    }

    private List<String> tokenizeArguments(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '"' || c == '\'') && (!inQuote || c == quoteChar)) {
                if (inQuote) {
                    inQuote = false;
                    quoteChar = 0;
                } else {
                    inQuote = true;
                    quoteChar = c;
                }
                continue;
            }
            if (Character.isWhitespace(c) && !inQuote) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private boolean parseBooleanFlag(Map<String, String> parsed) {
        if (parsed == null || !parsed.containsKey("auto-tune")) {
            return false;
        }
        String value = parsed.get("auto-tune");
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.trim().toLowerCase();
        return List.of("true", "1", "yes", "y", "on", "是").contains(normalized);
    }

    private String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    private String formatPercent(double rate) {
        return Math.round(rate * 10_000d) / 100d + "%";
    }

    private String normalizeEqStyleValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase();
        return switch (normalized) {
            case "gentle", "direct", "workplace", "intimate" -> normalized;
            default -> null;
        };
    }

    private String normalizeEqModeValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase();
        return switch (normalized) {
            case "analysis", "reply", "both" -> normalized;
            default -> null;
        };
    }

    private String normalizeEqPriorityFocusValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase();
        return switch (normalized) {
            case "p1", "p2", "p3" -> normalized;
            default -> null;
        };
    }

    private String buildSkillDslJson(String skill, Map<String, Object> payload) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("skill", skill);
        root.put("input", payload == null ? Map.of() : payload);
        return toJsonValue(root);
    }

    private String toJsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return '"' + escapeJson(s) + '"';
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escapeJson(String.valueOf(entry.getKey()))).append('"').append(':');
                sb.append(toJsonValue(entry.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(toJsonValue(list.get(i)));
            }
            sb.append(']');
            return sb.toString();
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> materialized = new ArrayList<>();
            for (Object item : iterable) {
                materialized.add(item);
            }
            return toJsonValue(materialized);
        }
        if (value.getClass().isArray()) {
            Object[] array = (Object[]) value;
            List<Object> items = new ArrayList<>(Arrays.asList(array));
            return toJsonValue(items);
        }
        return '"' + escapeJson(String.valueOf(value)) + '"';
    }

    private String escapeJson(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private int levenshtein(String left, String right) {
        String a = left == null ? "" : left;
        String b = right == null ? "" : right;
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return dp[a.length()][b.length()];
    }

    private void printMemoryStyle(PrintWriter out, MemoryStyleProfileDto style) {
        if (style == null) {
            printInfo(out, "当前记忆风格：未设置。");
            return;
        }
        printInfo(out, "当前记忆风格：" + safe(style.styleName()) + "，语气=" + safe(style.tone()) + "，输出=" + safe(style.outputFormat()));
        if (showRoutingDetails) {
            out.println("style.name=" + safe(style.styleName()));
            out.println("style.tone=" + safe(style.tone()));
            out.println("style.outputFormat=" + safe(style.outputFormat()));
        }
    }

    private void printMemoryCompressionPlan(PrintWriter out,
                                            MemoryCompressionPlanResponseDto response,
                                            SessionState sessionState) {
        if (response == null) {
            printInfo(out, "记忆压缩规划：无结果。");
            return;
        }
        out.println("记忆压缩规划");
        printMemoryStyle(out, response.style());
        List<MemoryCompressionStepDto> steps = response.steps() == null ? List.of() : response.steps();
        for (MemoryCompressionStepDto step : steps) {
            if (step == null) {
                continue;
            }
            out.println("[" + safe(step.stage()) + "] " + safe(step.content()));
        }
        if (!steps.isEmpty()) {
            int first = Math.max(1, steps.get(0).length());
            int last = Math.max(0, steps.get(steps.size() - 1).length());
            double compressRate = 1d - ((double) last / first);
            out.println("memory.compressRate=" + formatPercent(compressRate));
            if (sessionState != null && steps.get(0) != null) {
                sessionState.pendingMemoryReviewSource = safe(steps.get(0).content());
            }
            out.println("如果你愿意，直接回复“要/好的”，我会帮你复核并提炼关键点。");
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class SessionState {
        private final List<String> localTurns = new ArrayList<>();
        private final List<CompletableFuture<Void>> pendingTasks = new CopyOnWriteArrayList<>();
        private String lastUserMessage;
        private String pendingMemoryReviewSource;
        private List<String> pendingMemoryReviewPoints;
        private TodoPriorityPolicy todoPolicyOverride;

        void clear() {
            localTurns.clear();
            lastUserMessage = null;
            pendingMemoryReviewSource = null;
            pendingMemoryReviewPoints = null;
            todoPolicyOverride = null;
        }
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

