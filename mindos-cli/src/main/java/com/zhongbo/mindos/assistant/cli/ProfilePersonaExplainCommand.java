package com.zhongbo.mindos.assistant.cli;

import com.zhongbo.mindos.assistant.common.dto.PendingPreferenceOverrideDto;
import com.zhongbo.mindos.assistant.common.dto.PersonaProfileExplainDto;
import com.zhongbo.mindos.assistant.sdk.AssistantSdkException;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.nio.file.Path;

@CommandLine.Command(name = "explain", description = "Explain confirmed persona fields and pending override candidates")
public class ProfilePersonaExplainCommand implements Runnable {

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
            PersonaProfileExplainDto response = chatService.getPersonaProfileExplain();
            PrintWriter out = spec.commandLine().getOut();
            out.println("persona.confirmed.assistantName=" + response.confirmed().assistantName());
            out.println("persona.confirmed.role=" + response.confirmed().role());
            out.println("persona.confirmed.style=" + response.confirmed().style());
            out.println("persona.confirmed.language=" + response.confirmed().language());
            out.println("persona.confirmed.timezone=" + response.confirmed().timezone());
            out.println("persona.confirmed.preferredChannel=" + response.confirmed().preferredChannel());
            if (response.pendingOverrides().isEmpty()) {
                out.println("persona.pendingOverrides=none");
                return;
            }
            for (PendingPreferenceOverrideDto pending : response.pendingOverrides()) {
                out.println("persona.pending." + pending.field()
                        + "=" + pending.pendingValue()
                        + " (count=" + pending.count()
                        + "/" + pending.confirmThreshold()
                        + ", remaining=" + pending.remainingConfirmTurns() + ")");
            }
        } catch (IllegalArgumentException ex) {
            throw new CommandLine.ParameterException(spec.commandLine(), ex.getMessage(), ex);
        } catch (AssistantSdkException ex) {
            String error = "MindOS persona explain failed"
                    + " (status=" + ex.statusCode()
                    + ", code=" + ex.errorCode() + ")"
                    + ": " + ex.getMessage();
            throw new CommandLine.ExecutionException(spec.commandLine(), error, ex);
        }
    }
}

