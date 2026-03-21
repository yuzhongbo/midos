package com.zhongbo.mindos.assistant.cli;

import com.zhongbo.mindos.assistant.common.dto.PersonaProfileDto;
import com.zhongbo.mindos.assistant.sdk.AssistantSdkException;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(name = "show", description = "Show the learned persona profile stored on the server")
public class ProfilePersonaShowCommand implements Runnable {

    @CommandLine.Option(names = {"-u", "--user"}, defaultValue = "local-user", description = "User id")
    private String userId;

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
            PersonaProfileDto response = chatService.getPersonaProfile();
            java.io.PrintWriter out = spec.commandLine().getOut();
            out.println("persona.assistantName=" + response.assistantName());
            out.println("persona.role=" + response.role());
            out.println("persona.style=" + response.style());
            out.println("persona.language=" + response.language());
            out.println("persona.timezone=" + response.timezone());
            out.println("persona.preferredChannel=" + response.preferredChannel());
        } catch (IllegalArgumentException ex) {
            throw new CommandLine.ParameterException(spec.commandLine(), ex.getMessage(), ex);
        } catch (AssistantSdkException ex) {
            String error = "MindOS persona profile show failed"
                    + " (status=" + ex.statusCode()
                    + ", code=" + ex.errorCode() + ")"
                    + ": " + ex.getMessage();
            throw new CommandLine.ExecutionException(spec.commandLine(), error, ex);
        }
    }
}

