package com.zhongbo.mindos.assistant.cli;

import com.zhongbo.mindos.assistant.common.dto.ChatResponseDto;
import com.zhongbo.mindos.assistant.sdk.AssistantSdkException;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(name = "chat", description = "Send a chat message to MindOS server")
public class ChatCommand implements Runnable {

    @CommandLine.Option(names = {"-u", "--user"}, defaultValue = "local-user", description = "User id")
    private String userId;

    @CommandLine.Option(names = {"-m", "--message"}, description = "Message to send")
    private String message;

    @CommandLine.Option(names = {"-i", "--interactive"}, description = "Enter interactive chat mode")
    private boolean interactive;

    @CommandLine.Option(names = {"--server"}, defaultValue = "http://localhost:8080", description = "MindOS server base URL")
    private String server;

    @CommandLine.Option(names = {"--profile-config"},
            defaultValue = "${sys:user.home}/.mindos/profile.properties",
            description = "Local profile config file path")
    private Path profileConfig;

    @CommandLine.Option(names = {"--llm-provider"},
            description = "Override LLM provider for this request (e.g. openai, local)")
    private String llmProvider;

    @CommandLine.Option(names = {"--theme"}, defaultValue = "cyber",
            description = "Interactive UI theme: cyber or classic")
    private String theme;

    @CommandLine.Option(names = {"--pure-nl"}, defaultValue = "true",
            description = "Use pure natural-language chat view (default: enabled)")
    private boolean pureNaturalLanguage;

    @CommandLine.Option(names = {"--show-routing-details"}, defaultValue = "false",
            description = "Show skill/channel/routing details for troubleshooting")
    private boolean showRoutingDetails;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        CliChatService chatService;
        try {
            chatService = new CliChatService(userId, server, profileConfig, llmProvider);
        } catch (IllegalArgumentException ex) {
            throw new CommandLine.ParameterException(spec.commandLine(), ex.getMessage(), ex);
        }
        if (interactive || message == null || message.isBlank()) {
            InteractiveChatRunner.UiTheme uiTheme = InteractiveChatRunner.UiTheme.fromValue(theme);
            boolean routingDetailsVisible = showRoutingDetails || !pureNaturalLanguage;
            new InteractiveChatRunner(uiTheme, routingDetailsVisible).run(System.in, spec.commandLine().getOut(), chatService);
            return;
        }

        try {
            ChatResponseDto response = chatService.sendMessage(message);
            spec.commandLine().getOut().println("助手[" + response.channel() + "]> " + response.reply());
        } catch (AssistantSdkException ex) {
            String error = "MindOS request failed"
                    + " (status=" + ex.statusCode()
                    + ", code=" + ex.errorCode() + ")"
                    + ": " + ex.getMessage();
            throw new CommandLine.ExecutionException(spec.commandLine(), error, ex);
        }
    }
}
