package com.zhongbo.mindos.assistant.cli;

import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(
        name = "mindos",
        mixinStandardHelpOptions = true,
        description = "MindOS CLI client",
        subcommands = {
                ChatCommand.class,
                InitCommand.class,
                ProfileCommand.class,
                MemoryCommand.class
        }
)
public class MindosCliApplication implements Runnable {

    @CommandLine.Option(names = {"-u", "--user"}, defaultValue = "local-user", description = "User id")
    private String userId;

    @CommandLine.Option(names = {"--server"}, defaultValue = "http://localhost:8080", description = "MindOS server base URL")
    private String server;

    @CommandLine.Option(names = {"--profile-config"},
            defaultValue = "${sys:user.home}/.mindos/profile.properties",
            description = "Local profile config file path")
    private Path profileConfig;

    @CommandLine.Option(names = {"--llm-provider"},
            description = "Override LLM provider for this session")
    private String llmProvider;

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
        new InteractiveChatRunner().run(System.in, spec.commandLine().getOut(), chatService);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new MindosCliApplication()).execute(args);
        System.exit(exitCode);
    }
}

