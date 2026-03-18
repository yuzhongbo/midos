package com.zhongbo.mindos.assistant.cli;

import com.zhongbo.mindos.assistant.common.dto.MemoryCompressionPlanRequestDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryCompressionPlanResponseDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryCompressionStepDto;
import com.zhongbo.mindos.assistant.sdk.AssistantSdkException;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(name = "compress", description = "Build a gradual memory compression plan")
public class MemoryCompressCommand implements Runnable {

    @CommandLine.Option(names = {"-u", "--user"}, defaultValue = "local-user", description = "User id")
    private String userId;

    @CommandLine.Option(names = {"--source"}, required = true, description = "Source text to compress")
    private String source;

    @CommandLine.Option(names = {"--style-name"}, description = "Override style name for this compression")
    private String styleName;

    @CommandLine.Option(names = {"--tone"}, description = "Override tone for this compression")
    private String tone;

    @CommandLine.Option(names = {"--output-format"}, description = "Override output format for this compression")
    private String outputFormat;

    @CommandLine.Option(names = {"--focus"}, description = "Optional focus: learning, task, or review")
    private String focus;

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
            MemoryCompressionPlanResponseDto response = chatService.buildMemoryCompressionPlan(
                    new MemoryCompressionPlanRequestDto(source, styleName, tone, outputFormat, focus)
            );
            System.out.println("style.name=" + response.style().styleName());
            System.out.println("style.tone=" + response.style().tone());
            System.out.println("style.outputFormat=" + response.style().outputFormat());
            for (MemoryCompressionStepDto step : response.steps()) {
                System.out.println(step.stage() + ": " + step.content());
            }
        } catch (IllegalArgumentException ex) {
            throw new CommandLine.ParameterException(spec.commandLine(), ex.getMessage(), ex);
        } catch (AssistantSdkException ex) {
            String error = "MindOS memory compress failed"
                    + " (status=" + ex.statusCode()
                    + ", code=" + ex.errorCode() + ")"
                    + ": " + ex.getMessage();
            throw new CommandLine.ExecutionException(spec.commandLine(), error, ex);
        }
    }
}

