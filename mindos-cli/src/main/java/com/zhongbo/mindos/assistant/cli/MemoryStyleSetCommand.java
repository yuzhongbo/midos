package com.zhongbo.mindos.assistant.cli;

import com.zhongbo.mindos.assistant.common.dto.MemoryStyleProfileDto;
import com.zhongbo.mindos.assistant.sdk.AssistantSdkException;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(name = "set", description = "Update the memory compression style profile")
public class MemoryStyleSetCommand implements Runnable {

    @CommandLine.Option(names = {"-u", "--user"}, defaultValue = "local-user", description = "User id")
    private String userId;

    @CommandLine.Option(names = {"--style-name"}, required = true, description = "Memory style name")
    private String styleName;

    @CommandLine.Option(names = {"--tone"}, description = "Tone for the compressed summary")
    private String tone;

    @CommandLine.Option(names = {"--output-format"}, description = "Output format such as plain or bullet")
    private String outputFormat;

    @CommandLine.Option(names = {"--auto-tune"}, description = "Enable lightweight auto tune from sample text")
    private boolean autoTune;

    @CommandLine.Option(names = {"--sample-text"}, description = "Sample text used when auto tune is enabled")
    private String sampleText;

    @CommandLine.Option(names = {"--server"}, defaultValue = "http://localhost:8080", description = "MindOS server base URL")
    private String server;

    @CommandLine.Option(names = {"--profile-config"},
            defaultValue = "${sys:user.home}/.mindos/profile.properties",
            description = "Local profile config file path")
    private Path profileConfig;

    @CommandLine.Option(names = {"--llm-provider"},
            description = "Override LLM provider for this request")
    private String llmProvider;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        try {
            CliChatService chatService = new CliChatService(userId, server, profileConfig, llmProvider);
            MemoryStyleProfileDto response = chatService.updateMemoryStyleProfile(
                    new MemoryStyleProfileDto(styleName, tone, outputFormat),
                    autoTune,
                    sampleText
            );
            System.out.println("style.name=" + response.styleName());
            System.out.println("style.tone=" + response.tone());
            System.out.println("style.outputFormat=" + response.outputFormat());
        } catch (IllegalArgumentException ex) {
            throw new CommandLine.ParameterException(spec.commandLine(), ex.getMessage(), ex);
        } catch (AssistantSdkException ex) {
            String error = "MindOS memory style set failed"
                    + " (status=" + ex.statusCode()
                    + ", code=" + ex.errorCode() + ")"
                    + ": " + ex.getMessage();
            throw new CommandLine.ExecutionException(spec.commandLine(), error, ex);
        }
    }
}

