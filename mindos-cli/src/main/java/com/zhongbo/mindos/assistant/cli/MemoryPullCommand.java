package com.zhongbo.mindos.assistant.cli;

import com.zhongbo.mindos.assistant.common.dto.MemorySyncResponseDto;
import com.zhongbo.mindos.assistant.sdk.AssistantSdkClient;
import com.zhongbo.mindos.assistant.sdk.AssistantSdkException;
import picocli.CommandLine;

import java.net.URI;

@CommandLine.Command(name = "pull", description = "Pull incremental memory updates from server")
public class MemoryPullCommand implements Runnable {

    @CommandLine.Option(names = {"-u", "--user"}, defaultValue = "local-user", description = "User id")
    private String userId;

    @CommandLine.Option(names = {"--since"}, defaultValue = "0", description = "Cursor to start from (exclusive)")
    private long since;

    @CommandLine.Option(names = {"--limit"}, defaultValue = "100", description = "Maximum number of events")
    private int limit;

    @CommandLine.Option(names = {"--server"}, defaultValue = "http://localhost:8080", description = "MindOS server base URL")
    private String server;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        try {
            String securedServer = UrlSecurityPolicy.requireAllowedSensitiveUrl(server, "server");
            AssistantSdkClient client = new AssistantSdkClient(URI.create(securedServer));
            MemorySyncResponseDto response = client.fetchMemorySync(userId, since, limit);
            System.out.println("cursor=" + response.cursor());
            System.out.println("episodic=" + response.episodic().size());
            System.out.println("semantic=" + response.semantic().size());
            System.out.println("procedural=" + response.procedural().size());
        } catch (IllegalArgumentException ex) {
            throw new CommandLine.ParameterException(spec.commandLine(), ex.getMessage(), ex);
        } catch (AssistantSdkException ex) {
            String error = "MindOS memory pull failed"
                    + " (status=" + ex.statusCode()
                    + ", code=" + ex.errorCode() + ")"
                    + ": " + ex.getMessage();
            throw new CommandLine.ExecutionException(spec.commandLine(), error, ex);
        }
    }
}

