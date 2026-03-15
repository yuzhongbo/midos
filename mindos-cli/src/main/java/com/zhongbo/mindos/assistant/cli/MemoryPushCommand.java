package com.zhongbo.mindos.assistant.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.common.dto.MemorySyncRequestDto;
import com.zhongbo.mindos.assistant.common.dto.MemorySyncResponseDto;
import com.zhongbo.mindos.assistant.sdk.AssistantSdkClient;
import com.zhongbo.mindos.assistant.sdk.AssistantSdkException;
import picocli.CommandLine;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

@CommandLine.Command(name = "push", description = "Push memory updates to server using a JSON payload file")
public class MemoryPushCommand implements Runnable {

    @CommandLine.Option(names = {"-u", "--user"}, defaultValue = "local-user", description = "User id")
    private String userId;

    @CommandLine.Option(names = {"-f", "--file"}, required = true, description = "Path to memory sync payload JSON")
    private Path file;

    @CommandLine.Option(names = {"--limit"}, defaultValue = "100", description = "Maximum number of events returned in response")
    private int limit;

    @CommandLine.Option(names = {"--server"}, defaultValue = "http://localhost:8080", description = "MindOS server base URL")
    private String server;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Override
    public void run() {
        try {
            if (!Files.exists(file)) {
                throw new CommandLine.ParameterException(spec.commandLine(), "Payload file does not exist: " + file);
            }

            MemorySyncRequestDto request = objectMapper.readValue(file.toFile(), MemorySyncRequestDto.class);
            String securedServer = UrlSecurityPolicy.requireAllowedSensitiveUrl(server, "server");
            AssistantSdkClient client = new AssistantSdkClient(URI.create(securedServer));
            MemorySyncResponseDto response = client.applyMemorySync(userId, request, limit);

            System.out.println("cursor=" + response.cursor());
            System.out.println("accepted=" + response.acceptedCount());
            System.out.println("skipped=" + response.skippedCount());
        } catch (AssistantSdkException ex) {
            String error = "MindOS memory push failed"
                    + " (status=" + ex.statusCode()
                    + ", code=" + ex.errorCode() + ")"
                    + ": " + ex.getMessage();
            throw new CommandLine.ExecutionException(spec.commandLine(), error, ex);
        } catch (IllegalArgumentException ex) {
            throw new CommandLine.ParameterException(spec.commandLine(), ex.getMessage(), ex);
        } catch (IOException ex) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "Failed to read payload file as MemorySyncRequest JSON: " + file, ex);
        }
    }
}

