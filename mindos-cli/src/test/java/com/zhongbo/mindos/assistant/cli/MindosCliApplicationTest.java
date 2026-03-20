package com.zhongbo.mindos.assistant.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MindosCliApplicationTest {

    @Test
    void shouldEnterInteractiveModeByDefault() {
        CommandOutputResult result = executeInteractiveWithOut("/exit\n");

        assertEquals(0, result.exitCode());
        String console = result.stdout();
        assertTrue(console.contains("MindOS 对话模式"));
        assertTrue(console.contains("/help"));
        assertTrue(console.contains("已退出对话模式"));
    }

    @Test
    void shouldSupportHelpAndExitShortcutsInInteractiveMode() {
        CommandOutputResult result = executeInteractiveWithOut("/h\n:q\n");

        assertEquals(0, result.exitCode());
        String console = result.stdout();
        assertTrue(console.contains("自然语言使用指南:"));
        assertTrue(console.contains("如需查看技术命令与参数：输入 /help full"));
        assertTrue(console.contains("已退出对话模式"));
    }

    @Test
    void shouldShowHelpAndExitZero() {
        CommandOutputResult result = executeWithOut("--help");

        assertEquals(0, result.exitCode());
        String help = result.stdout();
        assertTrue(help.contains("chat"));
        assertTrue(help.contains("init"));
        assertTrue(help.contains("profile"));
        assertTrue(help.contains("memory"));
    }

    @Test
    void shouldShowProfileSubcommandHelp() {
        CommandOutputResult result = executeWithOut("profile", "--help");

        assertEquals(0, result.exitCode());
        String help = result.stdout();
        assertTrue(help.contains("show"));
        assertTrue(help.contains("set"));
        assertTrue(help.contains("reset"));
    }

    @Test
    void shouldInitializeProfileFile() throws IOException {
        Path tempDir = Files.createTempDirectory("mindos-cli-test-");
        Path configPath = tempDir.resolve("profile.properties");

        CommandLine commandLine = new CommandLine(new MindosCliApplication());
        int exitCode = commandLine.execute(
                "init",
                "--name", "BoAssistant",
                "--role", "coding-partner",
                "--style", "concise",
                "--language", "zh-CN",
                "--timezone", "Asia/Shanghai",
                "--config", configPath.toString()
        );

        String profileContent = Files.readString(configPath, StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(profileContent.contains("assistant.name=BoAssistant"));
        assertTrue(profileContent.contains("assistant.role=coding-partner"));
    }

    @Test
    void shouldShowInitializedProfile() throws IOException {
        Path tempDir = Files.createTempDirectory("mindos-cli-profile-");
        Path configPath = tempDir.resolve("profile.properties");

        CommandLine commandLine = new CommandLine(new MindosCliApplication());
        int initExitCode = commandLine.execute(
                "init",
                "--name", "BoAssistant",
                "--role", "coding-partner",
                "--style", "concise",
                "--language", "zh-CN",
                "--timezone", "Asia/Shanghai",
                "--config", configPath.toString()
        );

        int showExitCode = commandLine.execute(
                "profile", "show", "--config", configPath.toString()
        );

        AssistantProfile profile = new AssistantProfileStore().load(configPath);
        assertEquals(0, initExitCode);
        assertEquals(0, showExitCode);
        assertEquals("BoAssistant", profile.assistantName());
        assertEquals("coding-partner", profile.role());
    }

    @Test
    void shouldSetAndResetProfile() throws IOException {
        Path tempDir = Files.createTempDirectory("mindos-cli-profile-set-");
        Path configPath = tempDir.resolve("profile.properties");

        CommandLine commandLine = new CommandLine(new MindosCliApplication());
        int initExitCode = commandLine.execute(
                "init",
                "--name", "BoAssistant",
                "--role", "coding-partner",
                "--style", "concise",
                "--language", "zh-CN",
                "--timezone", "Asia/Shanghai",
                "--config", configPath.toString()
        );

        int setExitCode = commandLine.execute(
                "profile", "set",
                "--style", "detailed",
                "--timezone", "UTC",
                "--config", configPath.toString()
        );

        AssistantProfile updated = new AssistantProfileStore().load(configPath);
        assertEquals(0, initExitCode);
        assertEquals(0, setExitCode);
        assertEquals("detailed", updated.style());
        assertEquals("UTC", updated.timezone());
        assertEquals("BoAssistant", updated.assistantName());

        int resetExitCode = commandLine.execute(
                "profile", "reset",
                "--config", configPath.toString()
        );

        AssistantProfile reset = new AssistantProfileStore().load(configPath);
        assertEquals(0, resetExitCode);
        assertEquals(AssistantProfileStore.DEFAULT_ASSISTANT_NAME, reset.assistantName());
        assertEquals(AssistantProfileStore.DEFAULT_ROLE, reset.role());
        assertEquals(AssistantProfileStore.DEFAULT_STYLE, reset.style());
        assertEquals(AssistantProfileStore.DEFAULT_LANGUAGE, reset.language());
        assertEquals(AssistantProfileStore.DEFAULT_TIMEZONE, reset.timezone());
    }

    @Test
    void shouldRunChatCommandAgainstFakeServer() throws IOException {
        AtomicReference<String> requestBodyRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            requestBodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"reply\":\"hello from fake server\",\"channel\":\"echo\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandLine commandLine = new CommandLine(new MindosCliApplication());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            commandLine.setOut(new PrintWriter(output, true));

            int exitCode = commandLine.execute(
                    "chat",
                    "--user", "cli-user",
                    "--message", "echo hello",
                    "--server", "http://127.0.0.1:" + port
            );

            String requestBody = requestBodyRef.get();

            assertEquals(0, exitCode);
            assertTrue(requestBody.contains("\"userId\":\"cli-user\""));
            assertTrue(requestBody.contains("\"message\":\"echo hello\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldSendMessageInInteractiveModeAgainstFakeServer() throws IOException {
        AtomicReference<String> requestBodyRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            requestBodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"reply\":\"interactive hello\",\"channel\":\"echo\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "echo hello\n/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "cli-user"
            );

            assertEquals(0, result.exitCode());
            String console = result.stdout();
            assertTrue(requestBodyRef.get().contains("\"message\":\"echo hello\""));
            assertTrue(console.contains("助手[echo] interactive hello"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldManageProfileInsideInteractiveMode() throws IOException {
        Path tempDir = Files.createTempDirectory("mindos-cli-interactive-profile-");
        Path configPath = tempDir.resolve("profile.properties");

        CommandOutputResult result = executeInteractiveWithOut(
                "/profile set --name BoAssistant --role coding-partner --style detailed --language zh-CN --timezone UTC --llm-provider openai\n"
                        + "/profile show\n"
                        + "/exit\n",
                "--profile-config", configPath.toString()
        );

        AssistantProfile profile = new AssistantProfileStore().load(configPath);
        String console = result.stdout();

        assertEquals(0, result.exitCode());
        assertEquals("BoAssistant", profile.assistantName());
        assertEquals("coding-partner", profile.role());
        assertEquals("detailed", profile.style());
        assertEquals("UTC", profile.timezone());
        assertEquals("openai", profile.llmProvider());
        assertTrue(console.contains("本地 profile 已更新"));
        assertTrue(console.contains("assistant=BoAssistant"));
        assertTrue(console.contains("llm.provider=openai"));
    }

    @Test
    void shouldGuideProfileEditingInsideInteractiveMode() throws IOException {
        Path tempDir = Files.createTempDirectory("mindos-cli-interactive-profile-guided-");
        Path configPath = tempDir.resolve("profile.properties");
        new AssistantProfileStore().save(configPath, new AssistantProfile(
                "MindOS Assistant",
                "personal-assistant",
                "concise",
                "zh-CN",
                "Asia/Shanghai",
                ""
        ));

        CommandOutputResult result = executeInteractiveWithOut(
                "/profile set\n"
                        + "BoAssistant\n"
                        + "coding-partner\n"
                        + "detailed\n"
                        + "zh-CN\n"
                        + "UTC\n"
                        + "openai\n"
                        + "/exit\n",
                "--profile-config", configPath.toString()
        );

        AssistantProfile profile = new AssistantProfileStore().load(configPath);
        String console = result.stdout();

        assertEquals(0, result.exitCode());
        assertEquals("BoAssistant", profile.assistantName());
        assertEquals("coding-partner", profile.role());
        assertEquals("detailed", profile.style());
        assertEquals("UTC", profile.timezone());
        assertEquals("openai", profile.llmProvider());
        assertTrue(console.contains("name [当前=MindOS Assistant]"));
        assertTrue(console.contains("llm-provider [当前=]"));
    }

    @Test
    void shouldHandleSessionCommandsInsideInteractiveMode() throws IOException {
        AtomicReference<String> requestBodyRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            requestBodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"reply\":\"session hello\",\"channel\":\"echo\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "/provider local\n"
                            + "/session\n"
                            + "/histroy\n"
                            + "echo hello\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "cli-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("已设置当前会话 llm.provider=local"));
            assertTrue(console.contains("user=cli-user"));
            assertTrue(console.contains("llm.provider=local"));
            assertTrue(console.contains("未知命令：/histroy"));
            assertTrue(console.contains("你可能想输入 /history"));
            assertTrue(console.contains("助手[echo] session hello"));
            assertTrue(requestBodyRef.get().contains("\"message\":\"echo hello\""));
            assertFalse(requestBodyRef.get().contains("histroy"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldShowHistoryAndRetryInsideInteractiveMode() throws IOException {
        AtomicReference<String> chatRequestBodyRef = new AtomicReference<>("");
        AtomicReference<String> historyRequestUriRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            chatRequestBodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"reply\":\"retry hello\",\"channel\":\"echo\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/chat", exchange -> {
            historyRequestUriRef.set(exchange.getRequestURI().toString());
            byte[] response = ("[" +
                    "{\"role\":\"user\",\"content\":\"previous question\",\"createdAt\":\"2026-03-13T00:00:00Z\"}," +
                    "{\"role\":\"assistant\",\"content\":\"previous answer\",\"createdAt\":\"2026-03-13T00:00:01Z\"}" +
                    "]").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "echo hello\n"
                            + "/history --limit 1\n"
                            + "/retry\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "cli-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(historyRequestUriRef.get().contains("/api/chat/cli-user/history"));
            assertTrue(console.contains("最近 1 条服务端历史"));
            assertTrue(console.contains("assistant: previous answer"));
            assertTrue(console.contains("已重试上一条消息：echo hello"));
            assertTrue(chatRequestBodyRef.get().contains("\"message\":\"echo hello\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldClearLocalStateInsideInteractiveMode() {
        CommandOutputResult result = executeInteractiveWithOut(
                "/clear\n"
                        + "/session\n"
                        + "/exit\n"
        );

        String console = result.stdout();
        assertEquals(0, result.exitCode());
        assertTrue(console.contains("已清空当前窗口本地状态"));
        assertTrue(console.contains("local.turns=0"));
    }

    @Test
    void shouldSwitchThemeInsideInteractiveMode() {
        CommandOutputResult result = executeInteractiveWithOut(
                "/theme classic\n"
                        + "/theme\n"
                        + "/exit\n"
        );

        String console = result.stdout();
        assertEquals(0, result.exitCode());
        assertTrue(console.contains("已切换主题: classic"));
        assertTrue(console.contains("当前主题: classic"));
    }

    @Test
    void shouldPullMemoryInsideInteractiveMode() throws IOException {
        AtomicReference<String> requestUriRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/memory/cli-user/sync", exchange -> {
            requestUriRef.set(exchange.getRequestURI().toString());
            String responseBody = "{" +
                    "\"cursor\":9," +
                    "\"acceptedCount\":0," +
                    "\"skippedCount\":0," +
                    "\"episodic\":[{\"role\":\"user\",\"content\":\"hi\",\"createdAt\":\"2026-03-13T00:00:00Z\"}]," +
                    "\"semantic\":[]," +
                    "\"procedural\":[]" +
                    "}";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "/memory pull --since 0 --limit 50\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "cli-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(requestUriRef.get().contains("since=0"));
            assertTrue(requestUriRef.get().contains("limit=50"));
            assertTrue(console.contains("memory.cursor=9"));
            assertTrue(console.contains("memory.episodic=1"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldPushMemoryInteractivelyInsideSingleWindow() throws IOException {
        AtomicReference<String> requestBodyRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/memory/cli-user/sync", exchange -> {
            requestBodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String responseBody = "{" +
                    "\"cursor\":12," +
                    "\"acceptedCount\":1," +
                    "\"skippedCount\":0," +
                    "\"episodic\":[]," +
                    "\"semantic\":[{\"text\":\"Java 偏好\",\"embedding\":[],\"createdAt\":\"2026-03-14T00:00:00Z\"}]," +
                    "\"procedural\":[]" +
                    "}";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "/memory push --limit 50\n"
                            + "\n"
                            + "semantic\n"
                            + "Java 偏好\n"
                            + "n\n"
                            + "y\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "cli-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("进入 memory push 交互模式"));
            assertTrue(console.contains("记忆推送预览"));
            assertTrue(console.contains("memory.accepted=1"));
            assertTrue(requestBodyRef.get().contains("\"semantic\":[{"));
            assertTrue(requestBodyRef.get().contains("Java 偏好"));
            assertTrue(requestBodyRef.get().contains("\"eventId\":\"cli-cli-user-"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldHideMemoryTechnicalMetricsByDefaultInInteractiveMode() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/memory/cli-user/sync", exchange -> {
            String responseBody = "{" +
                    "\"cursor\":9," +
                    "\"acceptedCount\":0," +
                    "\"skippedCount\":0," +
                    "\"episodic\":[{\"role\":\"user\",\"content\":\"hi\",\"createdAt\":\"2026-03-13T00:00:00Z\"}]," +
                    "\"semantic\":[]," +
                    "\"procedural\":[]" +
                    "}";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOutDefault(
                    "/memory pull --since 0 --limit 50\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "cli-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("已拉取记忆：对话 1 条"));
            assertFalse(console.contains("memory.cursor="));
            assertFalse(console.contains("memory.episodic="));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldShowMemoryStyleInNaturalLanguageByDefault() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/memory/cli-user/style", exchange -> {
            String responseBody = "{" +
                    "\"styleName\":\"action\"," +
                    "\"tone\":\"warm\"," +
                    "\"outputFormat\":\"bullet\"" +
                    "}";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOutDefault(
                    "/memory style show\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "cli-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("当前记忆风格：action，语气=warm，输出=bullet"));
            assertFalse(console.contains("style.name="));
            assertFalse(console.contains("style.tone="));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldShowMemoryStyleTechnicalFieldsInRoutingDetailsMode() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/memory/cli-user/style", exchange -> {
            String responseBody = "{" +
                    "\"styleName\":\"action\"," +
                    "\"tone\":\"warm\"," +
                    "\"outputFormat\":\"bullet\"" +
                    "}";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "/memory style show\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "cli-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("当前记忆风格：action，语气=warm，输出=bullet"));
            assertTrue(console.contains("style.name=action"));
            assertTrue(console.contains("style.tone=warm"));
            assertTrue(console.contains("style.outputFormat=bullet"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldManageSkillsInsideInteractiveMode() throws IOException {
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skills", exchange -> {
            bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String responseBody;
            if ("GET".equals(method) && "/api/skills".equals(path)) {
                responseBody = "[" +
                        "{\"name\":\"echo\",\"description\":\"Echo skill\"}," +
                        "{\"name\":\"mcp.docs.searchDocs\",\"description\":\"Search docs\"}" +
                        "]";
            } else if ("POST".equals(method) && "/api/skills/reload".equals(path)) {
                responseBody = "{\"reloaded\":1,\"status\":\"ok\"}";
            } else if ("POST".equals(method) && "/api/skills/reload-mcp".equals(path)) {
                responseBody = "{\"reloaded\":2,\"status\":\"ok\"}";
            } else if ("POST".equals(method) && "/api/skills/load-mcp".equals(path)) {
                responseBody = "{\"loaded\":2,\"alias\":\"docs\",\"status\":\"ok\"}";
            } else if ("POST".equals(method) && "/api/skills/load-jar".equals(path)) {
                responseBody = "{\"loaded\":1,\"url\":\"https://example.com/skill-weather.jar\",\"status\":\"ok\"}";
            } else {
                responseBody = "{\"status\":\"unknown\"}";
            }
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "/skills\n"
                            + "/skill reload\n"
                            + "/skill reload-mcp\n"
                            + "/skill load-mcp --alias docs --url http://localhost:8081/mcp\n"
                            + "y\n"
                            + "/skill load-jar --url https://example.com/skill-weather.jar\n"
                            + "y\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("当前已注册技能（2）"));
            assertTrue(console.contains("echo :: Echo skill"));
            assertTrue(console.contains("自定义技能已重载：reloaded=1"));
            assertTrue(console.contains("MCP 技能已重载：reloaded=2"));
            assertTrue(console.contains("MCP server 已加载：alias=docs, loaded=2, status=ok"));
            assertTrue(console.contains("外部 skill JAR 已加载：url=https://example.com/skill-weather.jar, loaded=1, status=ok"));
            assertTrue(bodyRef.get().contains("https://example.com/skill-weather.jar") || bodyRef.get().contains("\"alias\":\"docs\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldMapNaturalLanguageToSkillListCommandInsideInteractiveMode() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skills", exchange -> {
            String responseBody = "[{\"name\":\"echo\",\"description\":\"Echo skill\"}]";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "我有哪些技能\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "cli-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("已识别自然语言指令 -> /skills"));
            assertTrue(console.contains("当前已注册技能（1）"));
            assertTrue(console.contains("echo :: Echo skill"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldHideNaturalLanguageRoutingHintByDefault() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skills", exchange -> {
            byte[] response = "[{\"name\":\"echo\",\"description\":\"Echo skill\"}]"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOutDefault(
                    "我有哪些技能\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "cli-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertFalse(console.contains("已识别自然语言指令 -> /skills"));
            assertTrue(console.contains("当前已注册技能（1）"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldShowNaturalLanguageRoutingHintWhenShowRoutingDetailsEnabled() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skills", exchange -> {
            byte[] response = "[{\"name\":\"echo\",\"description\":\"Echo skill\"}]"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOutDefault(
                    "我有哪些技能\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "cli-user",
                    "--show-routing-details"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("已识别自然语言指令 -> /skills"));
            assertTrue(console.contains("当前已注册技能（1）"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldToggleRoutingDetailsViaNaturalLanguageInSession() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skills", exchange -> {
            byte[] response = "[{\"name\":\"echo\",\"description\":\"Echo skill\"}]"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOutDefault(
                    "打开排障模式\n"
                            + "我有哪些技能\n"
                            + "关闭排障模式\n"
                            + "我有哪些技能\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "cli-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("已切换为排障视图"));
            assertTrue(console.contains("已切换为自然语言视图"));
            assertEquals(1, countOccurrences(console, "已识别自然语言指令 -> /skills"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldMapNaturalLanguageToTeachingPlanDslInsideInteractiveMode() throws IOException {
        AtomicReference<String> requestBodyRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            requestBodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"reply\":\"plan ok\",\"channel\":\"teaching.plan\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "给学生 stu-1 做一个数学学习计划，目标是期末提分，六周，每周八小时，薄弱点函数、概率，学习风格练习优先\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "cli-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("已识别自然语言指令 -> /teach plan --query"));
            assertTrue(console.contains("助手[teaching.plan] plan ok"));

            String requestBody = requestBodyRef.get();
            assertTrue(requestBody.contains("\\\"skill\\\":\\\"teaching.plan\\\""));
            assertTrue(requestBody.contains("\\\"studentId\\\":\\\"stu-1\\\""));
            assertTrue(requestBody.contains("\\\"topic\\\":\\\"数学\\\""));
            assertTrue(requestBody.contains("\\\"durationWeeks\\\":6"));
            assertTrue(requestBody.contains("\\\"weeklyHours\\\":8"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldFallbackToChatWhenLowConfidenceNaturalLanguageCommandIsDeclined() throws IOException {
        AtomicReference<String> requestBodyRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            requestBodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"reply\":\"chat fallback\",\"channel\":\"echo\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "学习计划\n"
                            + "n\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "cli-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("已识别自然语言指令 -> /teach plan --query 学习计划"));
            assertTrue(console.contains("该自然语言识别置信度较低，是否按该命令执行？"));
            assertTrue(console.contains("已取消命令执行，改为普通对话输入。"));
            assertTrue(console.contains("助手[echo] chat fallback"));

            String requestBody = requestBodyRef.get();
            assertTrue(requestBody.contains("\"message\":\"学习计划\""));
            assertFalse(requestBody.contains("\\\"skill\\\":\\\"teaching.plan\\\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldAskConfirmationForSensitiveNaturalLanguageCommand() throws IOException {
        AtomicReference<String> requestBodyRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skills/load-jar", exchange -> {
            requestBodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"loaded\":1,\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "请帮我加载jar https://example.com/skill-weather.jar\n"
                            + "n\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "cli-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("已识别自然语言指令 -> /skill load-jar --url https://example.com/skill-weather.jar"));
            assertTrue(console.contains("该命令可能影响安全或配置，确认执行吗？"));
            assertTrue(console.contains("已取消执行：/skill load-jar --url https://example.com/skill-weather.jar"));
            assertTrue(requestBodyRef.get().isBlank());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRunMemoryPullInBackgroundWithoutBlockingChat() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/memory/cli-user/sync", exchange -> {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            String responseBody = "{" +
                    "\"cursor\":9," +
                    "\"acceptedCount\":0," +
                    "\"skippedCount\":0," +
                    "\"episodic\":[]," +
                    "\"semantic\":[]," +
                    "\"procedural\":[]" +
                    "}";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/chat", exchange -> {
            byte[] response = "{\"reply\":\"hello\",\"channel\":\"echo\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "帮我拉取记忆\n"
                            + "echo hello\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "cli-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("已识别自然语言指令 -> /memory pull"));
            assertTrue(console.contains("memory pull 已在后台执行"));
            assertTrue(console.contains("助手[echo] hello"));
            assertTrue(console.contains("memory.cursor=9"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldMapNaturalLanguageToParameterizedMemoryAndHistoryCommands() throws IOException {
        AtomicReference<String> memoryRequestUriRef = new AtomicReference<>("");
        AtomicReference<String> historyRequestUriRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/memory/cli-user/sync", exchange -> {
            memoryRequestUriRef.set(exchange.getRequestURI().toString());
            String responseBody = "{" +
                    "\"cursor\":99," +
                    "\"acceptedCount\":0," +
                    "\"skippedCount\":0," +
                    "\"episodic\":[]," +
                    "\"semantic\":[]," +
                    "\"procedural\":[]" +
                    "}";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/chat/cli-user/history", exchange -> {
            historyRequestUriRef.set(exchange.getRequestURI().toString());
            String responseBody = "[{\"role\":\"user\",\"content\":\"hello\",\"createdAt\":\"2026-03-14T00:00:00Z\"}]";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "帮我从 12 开始拉取最近 30 条记忆\n"
                            + "查看最近 5 条历史\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "cli-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("已识别自然语言指令 -> /memory pull --since 12 --limit 30"));
            assertTrue(console.contains("已识别自然语言指令 -> /history --limit 5"));
            assertTrue(memoryRequestUriRef.get().contains("since=12"));
            assertTrue(memoryRequestUriRef.get().contains("limit=30"));
            assertTrue(historyRequestUriRef.get().contains("/api/chat/cli-user/history"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldMapNaturalLanguageToProfileSetWithMultipleFields() throws IOException {
        Path tempDir = Files.createTempDirectory("mindos-cli-nl-profile-");
        Path configPath = tempDir.resolve("profile.properties");
        new AssistantProfileStore().save(configPath, new AssistantProfileStore().defaultProfile());

        CommandOutputResult result = executeInteractiveWithOut(
                "把名字改成 Nova，角色换成 architect，风格设成 concise，语言改成 en-US，时区换成 UTC\n"
                        + "/exit\n",
                "--profile-config", configPath.toString()
        );

        AssistantProfile profile = new AssistantProfileStore().load(configPath);
        String console = result.stdout();
        assertEquals(0, result.exitCode());
        assertTrue(console.contains("已识别自然语言指令 -> /profile set --name Nova --role architect --style concise --language en-US --timezone UTC"));
        assertEquals("Nova", profile.assistantName());
        assertEquals("architect", profile.role());
        assertEquals("concise", profile.style());
        assertEquals("en-US", profile.language());
        assertEquals("UTC", profile.timezone());
    }

    @Test
    void shouldMapNaturalLanguageToLoadMcpWithDerivedAlias() throws IOException {
        AtomicReference<String> requestBodyRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skills/load-mcp", exchange -> {
            requestBodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"loaded\":1,\"alias\":\"example\",\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "请帮我接入mcp https://example.com/mcp\n"
                            + "y\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "cli-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("已识别自然语言指令 -> /skill load-mcp --alias example --url https://example.com/mcp"));
            assertTrue(console.contains("该命令可能影响安全或配置，确认执行吗？"));
            assertTrue(requestBodyRef.get().contains("\"alias\":\"example\""));
            assertTrue(requestBodyRef.get().contains("\"url\":\"https://example.com/mcp\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldMapNaturalLanguageToUserAndServerCommands() {
        CommandOutputResult result = executeInteractiveWithOut(
                "把用户改为 dev-user\n"
                        + "把服务地址换成 http://localhost:18080\n"
                        + "y\n"
                        + "查看会话信息\n"
                        + "/exit\n"
        );

        String console = result.stdout();
        assertEquals(0, result.exitCode());
        assertTrue(console.contains("已识别自然语言指令 -> /user dev-user"));
        assertTrue(console.contains("已切换 user=dev-user"));
        assertTrue(console.contains("已识别自然语言指令 -> /server http://localhost:18080"));
        assertTrue(console.contains("该命令可能影响安全或配置，确认执行吗？"));
        assertTrue(console.contains("已切换 server=http://localhost:18080"));
        assertTrue(console.contains("user=dev-user"));
        assertTrue(console.contains("server=http://localhost:18080"));
    }

    @Test
    void shouldMapNaturalLanguageToProviderCommand() {
        CommandOutputResult result = executeInteractiveWithOut(
                "把模型切换到 openai\n"
                        + "查看会话信息\n"
                        + "取消模型覆盖\n"
                        + "查看会话信息\n"
                        + "/exit\n"
        );

        String console = result.stdout();
        assertEquals(0, result.exitCode());
        assertTrue(console.contains("已识别自然语言指令 -> /provider openai"));
        assertTrue(console.contains("已设置当前会话 llm.provider=openai"));
        assertTrue(console.contains("已识别自然语言指令 -> /provider default"));
        assertTrue(console.contains("已清除当前会话 provider 覆盖"));
    }

    @Test
    void shouldMapNaturalLanguageToMcpLoadWithAliasPhrase() throws IOException {
        AtomicReference<String> requestBodyRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skills/load-mcp", exchange -> {
            requestBodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"loaded\":1,\"alias\":\"docs-cn\",\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "请接入mcp https://docs.example.com/mcp，简称 docs-cn\n"
                            + "y\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "cli-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("已识别自然语言指令 -> /skill load-mcp --alias docs-cn --url https://docs.example.com/mcp"));
            assertTrue(requestBodyRef.get().contains("\"alias\":\"docs-cn\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldMapNaturalLanguageToHistoryWithImplicitLimit() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat/local-user/history", exchange -> {
            String responseBody = "[{\"role\":\"user\",\"content\":\"hello\",\"createdAt\":\"2026-03-14T00:00:00Z\"}]";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "给我看几条历史\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "local-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("已识别自然语言指令 -> /history --limit 10"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldParseMemoryPullCountWhenSinceAndLimitAreBothInSentence() throws IOException {
        AtomicReference<String> requestUriRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/memory/local-user/sync", exchange -> {
            requestUriRef.set(exchange.getRequestURI().toString());
            String responseBody = "{" +
                    "\"cursor\":15," +
                    "\"acceptedCount\":0," +
                    "\"skippedCount\":0," +
                    "\"episodic\":[]," +
                    "\"semantic\":[]," +
                    "\"procedural\":[]" +
                    "}";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "从 12 开始拉取 30 条记忆\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "local-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("已识别自然语言指令 -> /memory pull --since 12 --limit 30"));
            assertTrue(requestUriRef.get().contains("since=12"));
            assertTrue(requestUriRef.get().contains("limit=30"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldParseChineseNumberForHistoryAndMemoryPush() {
        CommandOutputResult result = executeInteractiveWithOut(
                "查看最近十条历史\n"
                        + "帮我保存二十条记忆\n"
                        + "cancel\n"
                        + "/exit\n"
        );

        String console = result.stdout();
        assertEquals(0, result.exitCode());
        assertTrue(console.contains("已识别自然语言指令 -> /history --limit 10"));
        assertTrue(console.contains("已识别自然语言指令 -> /memory push --limit 20"));
    }

    @Test
    void shouldParseServerHostPortWithoutSchemeFromNaturalLanguage() {
        CommandOutputResult result = executeInteractiveWithOut(
                "把服务端地址改成 localhost:19090\n"
                        + "y\n"
                        + "查看会话信息\n"
                        + "/exit\n"
        );

        String console = result.stdout();
        assertEquals(0, result.exitCode());
        assertTrue(console.contains("已识别自然语言指令 -> /server http://localhost:19090"));
        assertTrue(console.contains("server=http://localhost:19090"));
    }

    @Test
    void shouldRejectPublicHttpServerSwitchInsideInteractiveMode() {
        CommandOutputResult result = executeInteractiveWithOut(
                "/server http://example.com:8080\n"
                        + "/session\n"
                        + "/exit\n"
        );

        String console = result.stdout();
        assertEquals(0, result.exitCode());
        assertTrue(console.contains("server URL 不符合安全策略"));
        assertFalse(console.contains("确认执行吗"));
        assertTrue(console.contains("server=http://localhost:8080"));
    }

    @Test
    void shouldRejectPublicHttpJarLoadInsideInteractiveMode() throws IOException {
        AtomicReference<String> requestBodyRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skills/load-jar", exchange -> {
            requestBodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"loaded\":1,\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "/skill load-jar --url http://example.com/skill.jar\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("url URL 不符合安全策略"));
            assertTrue(requestBodyRef.get().isBlank());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldAllowPrivateHttpMcpLoadInsideInteractiveMode() throws IOException {
        AtomicReference<String> requestBodyRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skills/load-mcp", exchange -> {
            requestBodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"loaded\":1,\"alias\":\"docs\",\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "/skill load-mcp --alias docs --url http://127.0.0.1:8081/mcp\n"
                            + "y\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("该命令可能影响安全或配置，确认执行吗？"));
            assertTrue(console.contains("MCP server 已加载"));
            assertTrue(requestBodyRef.get().contains("\"alias\":\"docs\""));
            assertTrue(requestBodyRef.get().contains("\"url\":\"http://127.0.0.1:8081/mcp\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldParseChineseHundredNumberForHistoryAndMemoryPull() throws IOException {
        AtomicReference<String> requestUriRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat/local-user/history", exchange -> {
            byte[] response = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/memory/local-user/sync", exchange -> {
            requestUriRef.set(exchange.getRequestURI().toString());
            String responseBody = "{" +
                    "\"cursor\":7," +
                    "\"acceptedCount\":0," +
                    "\"skippedCount\":0," +
                    "\"episodic\":[]," +
                    "\"semantic\":[]," +
                    "\"procedural\":[]" +
                    "}";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "查看最近一百条历史\n"
                            + "从 3 开始拉一百二十条记忆\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "local-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("已识别自然语言指令 -> /history --limit 100"));
            assertTrue(requestUriRef.get().contains("since=3"));
            assertTrue(requestUriRef.get().contains("limit=120"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldParseChineseThousandNumbersForHistoryAndMemoryPull() throws IOException {
        AtomicReference<String> requestUriRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat/local-user/history", exchange -> {
            byte[] response = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/memory/local-user/sync", exchange -> {
            requestUriRef.set(exchange.getRequestURI().toString());
            String responseBody = "{" +
                    "\"cursor\":7," +
                    "\"acceptedCount\":0," +
                    "\"skippedCount\":0," +
                    "\"episodic\":[]," +
                    "\"semantic\":[]," +
                    "\"procedural\":[]" +
                    "}";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "查看最近一千二百条历史\n"
                            + "从 3 开始拉一千零二十条记忆\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "local-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("已识别自然语言指令 -> /history --limit 1200"));
            assertTrue(requestUriRef.get().contains("since=3"));
            assertTrue(requestUriRef.get().contains("limit=1020"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldMapColloquialPullWithoutMemoryKeyword() throws IOException {
        AtomicReference<String> requestUriRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/memory/local-user/sync", exchange -> {
            requestUriRef.set(exchange.getRequestURI().toString());
            String responseBody = "{" +
                    "\"cursor\":8," +
                    "\"acceptedCount\":0," +
                    "\"skippedCount\":0," +
                    "\"episodic\":[]," +
                    "\"semantic\":[]," +
                    "\"procedural\":[]" +
                    "}";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "从 2 开始拉三十条\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "local-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("已识别自然语言指令 -> /memory pull --since 2 --limit 30"));
            assertTrue(requestUriRef.get().contains("since=2"));
            assertTrue(requestUriRef.get().contains("limit=30"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldUseLastProfileFieldWhenRepeatedInNaturalLanguage() throws IOException {
        Path tempDir = Files.createTempDirectory("mindos-cli-profile-repeat-");
        Path configPath = tempDir.resolve("profile.properties");
        new AssistantProfileStore().save(configPath, new AssistantProfileStore().defaultProfile());

        CommandOutputResult result = executeInteractiveWithOut(
                "把名字改为 Alpha，名字改为 Beta\n"
                        + "/exit\n",
                "--profile-config", configPath.toString()
        );

        AssistantProfile profile = new AssistantProfileStore().load(configPath);
        String console = result.stdout();
        assertEquals(0, result.exitCode());
        assertTrue(console.contains("已识别自然语言指令 -> /profile set --name Beta"));
        assertEquals("Beta", profile.assistantName());
    }

    @Test
    void shouldMapNaturalLanguageToMemoryPushLimit() {
        CommandOutputResult result = executeInteractiveWithOut(
                "帮我保存 20 条记忆\n"
                        + "cancel\n"
                        + "/exit\n"
        );

        String console = result.stdout();
        assertEquals(0, result.exitCode());
        assertTrue(console.contains("已识别自然语言指令 -> /memory push --limit 20"));
        assertTrue(console.contains("进入 memory push 交互模式"));
        assertTrue(console.contains("已取消 memory push。当前未提交任何记忆。"));
    }

    @Test
    void shouldReturnNonZeroWhenServerReturnsStructuredError() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            byte[] response = "{\"code\":\"INVALID_SKILL_DSL\",\"message\":\"Missing skill\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandExecutionResult result = executeWithErr(
                    "chat",
                    "--user", "cli-user",
                    "--message", "{bad-json}",
                    "--server", "http://127.0.0.1:" + port
            );

            assertNonZeroWithErrContains(result, "Missing skill");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldShowMemorySubcommandHelp() {
        CommandOutputResult result = executeWithOut("memory", "--help");

        assertEquals(0, result.exitCode());
        String help = result.stdout();
        assertTrue(help.contains("pull"));
        assertTrue(help.contains("push"));
        assertTrue(help.contains("style"));
        assertTrue(help.contains("compress"));
    }

    @Test
    void shouldShowAndSetMemoryStyleAndBuildCompressionPlan() throws IOException {
        AtomicReference<String> styleRequestUriRef = new AtomicReference<>("");
        AtomicReference<String> styleRequestBodyRef = new AtomicReference<>("");
        AtomicReference<String> compressRequestBodyRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/memory/cli-user/style", exchange -> {
            styleRequestUriRef.set(exchange.getRequestURI().toString());
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                byte[] response = "{\"styleName\":\"concise\",\"tone\":\"direct\",\"outputFormat\":\"plain\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            styleRequestBodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = styleRequestBodyRef.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/memory/cli-user/compress-plan", exchange -> {
            compressRequestBodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{" +
                    "\"style\":{\"styleName\":\"action\",\"tone\":\"warm\",\"outputFormat\":\"bullet\"}," +
                    "\"steps\":[{" +
                    "\"stage\":\"RAW\",\"content\":\"原文\",\"length\":2},{" +
                    "\"stage\":\"STYLED\",\"content\":\"- 行动项\",\"length\":5}]," +
                    "\"createdAt\":\"2026-03-17T00:00:00Z\"" +
                    "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult showResult = executeWithOut(
                    "memory", "style", "show",
                    "--user", "cli-user",
                    "--server", "http://127.0.0.1:" + port
            );
            assertEquals(0, showResult.exitCode());
            assertTrue(styleRequestUriRef.get().contains("/api/memory/cli-user/style"));

            CommandOutputResult setResult = executeWithOut(
                    "memory", "style", "set",
                    "--user", "cli-user",
                    "--style-name", "action",
                    "--tone", "warm",
                    "--output-format", "bullet",
                    "--server", "http://127.0.0.1:" + port
            );
            assertEquals(0, setResult.exitCode());
            assertTrue(styleRequestBodyRef.get().contains("\"styleName\":\"action\""));
            assertTrue(styleRequestUriRef.get().contains("autoTune=false"));

            CommandOutputResult autoTuneResult = executeWithOut(
                    "memory", "style", "set",
                    "--user", "cli-user",
                    "--style-name", "",
                    "--auto-tune",
                    "--sample-text", "请帮我按步骤拆分任务清单",
                    "--server", "http://127.0.0.1:" + port
            );
            assertEquals(0, autoTuneResult.exitCode());
            assertTrue(styleRequestUriRef.get().contains("autoTune=true"));
            assertTrue(styleRequestUriRef.get().contains("sampleText="));

            CommandOutputResult compressResult = executeWithOut(
                    "memory", "compress",
                    "--user", "cli-user",
                    "--source", "明天先整理目标，再拆任务",
                    "--focus", "task",
                    "--server", "http://127.0.0.1:" + port
            );
            assertEquals(0, compressResult.exitCode());
            assertTrue(compressRequestBodyRef.get().contains("\"sourceText\":\"明天先整理目标，再拆任务\""));
            assertTrue(compressRequestBodyRef.get().contains("\"focus\":\"task\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldMapNaturalLanguageToMemoryCompressInsideInteractiveMode() throws IOException {
        AtomicReference<String> requestBodyRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/memory/cli-user/compress-plan", exchange -> {
            requestBodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{" +
                    "\"style\":{\"styleName\":\"action\",\"tone\":\"warm\",\"outputFormat\":\"bullet\"}," +
                    "\"steps\":[{" +
                    "\"stage\":\"RAW\",\"content\":\"明天18:30前必须提交合同，不要遗漏附件\",\"length\":20},{" +
                    "\"stage\":\"STYLED\",\"content\":\"- 提交合同\\n- 检查附件\",\"length\":11}]," +
                    "\"createdAt\":\"2026-03-17T00:00:00Z\"" +
                    "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeInteractiveWithOut(
                    "按我的风格压缩这段记忆：明天18:30前必须提交合同，不要遗漏附件，按任务聚焦\n"
                            + "要\n"
                            + "生成待办\n"
                            + "/exit\n",
                    "--server", "http://127.0.0.1:" + port,
                    "--user", "cli-user"
            );

            String console = result.stdout();
            assertEquals(0, result.exitCode());
            assertTrue(console.contains("已识别自然语言指令 -> /memory compress"));
            assertTrue(console.contains("记忆压缩规划"));
            assertTrue(console.contains("[STYLED] - 提交合同"));
            assertTrue(console.contains("memory.compressRate="));
            assertTrue(console.contains("如果你愿意，直接回复“要/好的”"));
            assertTrue(console.contains("好的，我整理了原文关键点"));
            assertTrue(console.contains("必须提交合同"));
            assertTrue(console.contains("如果你愿意，回复“生成待办”"));
            assertTrue(console.contains("已根据关键点整理执行清单"));
            assertTrue(console.contains("优先级说明：P1=今天必须完成"));
            assertTrue(console.contains("当前待办策略：P1>= 45，P2>= 25"));
            assertTrue(console.contains("[今天（today）]"));
            assertTrue(console.contains("P1"));
            assertTrue(console.contains("建议24小时内完成"));
            assertTrue(requestBodyRef.get().contains("\"sourceText\":\"明天18:30前必须提交合同，不要遗漏附件\""));
            assertTrue(requestBodyRef.get().contains("\"focus\":\"task\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldPullMemoryFromServer() throws IOException {
        AtomicReference<String> requestUriRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/memory/cli-user/sync", exchange -> {
            requestUriRef.set(exchange.getRequestURI().toString());
            String responseBody = "{" +
                    "\"cursor\":9," +
                    "\"acceptedCount\":0," +
                    "\"skippedCount\":0," +
                    "\"episodic\":[{\"role\":\"user\",\"content\":\"hi\",\"createdAt\":\"2026-03-13T00:00:00Z\"}]," +
                    "\"semantic\":[]," +
                    "\"procedural\":[]" +
                    "}";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeWithOut(
                    "memory", "pull",
                    "--user", "cli-user",
                    "--since", "0",
                    "--limit", "50",
                    "--server", "http://127.0.0.1:" + port
            );

            assertEquals(0, result.exitCode());
            assertTrue(requestUriRef.get().contains("since=0"));
            assertTrue(requestUriRef.get().contains("limit=50"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldPushMemoryToServer() throws IOException {
        AtomicReference<String> requestBodyRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/memory/cli-user/sync", exchange -> {
            requestBodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{" +
                    "\"cursor\":12," +
                    "\"acceptedCount\":3," +
                    "\"skippedCount\":0," +
                    "\"episodic\":[]," +
                    "\"semantic\":[]," +
                    "\"procedural\":[]" +
                    "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        Path tempDir = Files.createTempDirectory("mindos-cli-memory-push-");
        Path payload = tempDir.resolve("payload.json");
        Files.writeString(payload,
                "{" +
                        "\"eventId\":\"evt-1\"," +
                        "\"episodic\":[{\"role\":\"user\",\"content\":\"hello\",\"createdAt\":\"2026-03-13T00:00:00Z\"}]," +
                        "\"semantic\":[]," +
                        "\"procedural\":[]" +
                        "}",
                StandardCharsets.UTF_8);

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandOutputResult result = executeWithOut(
                    "memory", "push",
                    "--user", "cli-user",
                    "--file", payload.toString(),
                    "--limit", "50",
                    "--server", "http://127.0.0.1:" + port
            );

            assertEquals(0, result.exitCode());
            assertFalse(requestBodyRef.get().isBlank());
            assertTrue(requestBodyRef.get().contains("evt-1"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldReturnNonZeroWhenMemoryPushReturnsStructuredError() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/memory/cli-user/sync", exchange -> {
            byte[] response = "{\"code\":\"INVALID_MEMORY_SYNC\",\"message\":\"Bad payload\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        Path tempDir = Files.createTempDirectory("mindos-cli-memory-push-error-");
        Path payload = tempDir.resolve("payload.json");
        Files.writeString(payload,
                "{\"eventId\":\"evt-1\",\"episodic\":[],\"semantic\":[],\"procedural\":[]}",
                StandardCharsets.UTF_8);

        try {
            server.start();
            int port = server.getAddress().getPort();

            CommandExecutionResult result = executeWithErr(
                    "memory", "push",
                    "--user", "cli-user",
                    "--file", payload.toString(),
                    "--server", "http://127.0.0.1:" + port
            );

            assertNonZeroWithErrContains(result, "Bad payload");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldReturnNonZeroWhenChatServerIsPublicHttp() {
        CommandExecutionResult result = executeWithErr(
                "chat",
                "--message", "hello",
                "--server", "http://example.com:8080"
        );

        assertNonZeroWithErrContains(result, "server URL 不符合安全策略");
    }

    @Test
    void shouldReturnNonZeroWhenMemoryPullServerIsPublicHttp() {
        CommandExecutionResult result = executeWithErr(
                "memory", "pull",
                "--server", "http://example.com:8080"
        );

        assertNonZeroWithErrContains(result, "server URL 不符合安全策略");
    }

    @Test
    void shouldReturnNonZeroWhenMemoryPushServerIsPublicHttp() throws IOException {
        Path tempDir = Files.createTempDirectory("mindos-cli-memory-push-public-http-");
        Path payload = tempDir.resolve("payload.json");
        Files.writeString(payload,
                "{\"eventId\":\"evt-1\",\"episodic\":[],\"semantic\":[],\"procedural\":[]}",
                StandardCharsets.UTF_8);

        CommandExecutionResult result = executeWithErr(
                "memory", "push",
                "--file", payload.toString(),
                "--server", "http://example.com:8080"
        );

        assertNonZeroWithErrContains(result, "server URL 不符合安全策略");
    }

    private CommandExecutionResult executeWithErr(String... args) {
        CommandLine commandLine = new CommandLine(new MindosCliApplication());
        ByteArrayOutputStream errOutput = new ByteArrayOutputStream();
        commandLine.setErr(new PrintWriter(errOutput, true));
        int exitCode = commandLine.execute(args);
        return new CommandExecutionResult(exitCode, errOutput.toString(StandardCharsets.UTF_8));
    }

    private CommandOutputResult executeWithOut(String... args) {
        CommandLine commandLine = new CommandLine(new MindosCliApplication());
        ByteArrayOutputStream outOutput = new ByteArrayOutputStream();
        commandLine.setOut(new PrintWriter(outOutput, true));
        int exitCode = commandLine.execute(args);
        return new CommandOutputResult(exitCode, outOutput.toString(StandardCharsets.UTF_8));
    }

    private CommandOutputResult executeInteractiveWithOut(String input, String... args) {
        String[] mergedArgs = new String[args.length + 1];
        System.arraycopy(args, 0, mergedArgs, 0, args.length);
        mergedArgs[args.length] = "--show-routing-details";
        return executeInteractiveWithOutDefault(input, mergedArgs);
    }

    private CommandOutputResult executeInteractiveWithOutDefault(String input, String... args) {
        CommandLine commandLine = new CommandLine(new MindosCliApplication());
        ByteArrayOutputStream outOutput = new ByteArrayOutputStream();
        commandLine.setOut(new PrintWriter(outOutput, true));

        java.io.InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
            int exitCode = commandLine.execute(args);
            return new CommandOutputResult(exitCode, outOutput.toString(StandardCharsets.UTF_8));
        } finally {
            System.setIn(originalIn);
        }
    }

    private void assertNonZeroWithErrContains(CommandExecutionResult result, String expectedMessage) {
        assertTrue(result.exitCode() != 0);
        assertTrue(result.stderr().contains(expectedMessage));
    }

    private int countOccurrences(String text, String target) {
        if (text == null || text.isBlank() || target == null || target.isBlank()) {
            return 0;
        }
        int count = 0;
        int fromIndex = 0;
        while (true) {
            int next = text.indexOf(target, fromIndex);
            if (next < 0) {
                return count;
            }
            count++;
            fromIndex = next + target.length();
        }
    }

    private record CommandExecutionResult(int exitCode, String stderr) {
    }

    private record CommandOutputResult(int exitCode, String stdout) {
    }

}

