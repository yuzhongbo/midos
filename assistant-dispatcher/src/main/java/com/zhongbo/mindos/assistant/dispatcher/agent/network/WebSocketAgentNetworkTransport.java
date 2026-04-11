package com.zhongbo.mindos.assistant.dispatcher.agent.network;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class WebSocketAgentNetworkTransport implements AgentNetworkTransport {

    private final WebSocketConnector connector;
    private final ObjectMapper objectMapper;
    private final Duration defaultTimeout;

    public WebSocketAgentNetworkTransport() {
        this(new JdkWebSocketConnector(), new ObjectMapper().findAndRegisterModules(), Duration.ofSeconds(5));
    }

    public WebSocketAgentNetworkTransport(WebSocketConnector connector,
                                          ObjectMapper objectMapper,
                                          Duration defaultTimeout) {
        this.connector = connector == null ? new JdkWebSocketConnector() : connector;
        this.objectMapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
        this.defaultTimeout = defaultTimeout == null ? Duration.ofSeconds(5) : defaultTimeout;
    }

    @Override
    public AgentTransportKind kind() {
        return AgentTransportKind.WEBSOCKET;
    }

    @Override
    public Optional<AgentMessage> send(AgentMessage message, AgentEndpoint endpoint) {
        if (message == null || endpoint == null) {
            return Optional.empty();
        }
        if (endpoint.uri() == null) {
            throw new AgentNetworkException("WebSocket endpoint URI is required for " + endpoint.agentId());
        }
        try {
            return connector.exchange(endpoint, objectMapper.writeValueAsString(message), objectMapper,
                    endpoint.timeout() == null ? defaultTimeout : endpoint.timeout());
        } catch (IOException ex) {
            throw new AgentNetworkException("WebSocket agent network I/O failed for " + endpoint.agentId(), ex);
        }
    }

    interface WebSocketConnector {
        Optional<AgentMessage> exchange(AgentEndpoint endpoint,
                                        String text,
                                        ObjectMapper objectMapper,
                                        Duration timeout) throws IOException;
    }

    static final class JdkWebSocketConnector implements WebSocketConnector {

        @Override
        public Optional<AgentMessage> exchange(AgentEndpoint endpoint,
                                               String text,
                                               ObjectMapper objectMapper,
                                               Duration timeout) throws IOException {
            WebSocket webSocket = null;
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .build();

                CompletableFuture<AgentMessage> replyFuture = new CompletableFuture<>();
                AtomicReference<StringBuilder> buffer = new AtomicReference<>(new StringBuilder());
                WebSocket.Listener listener = new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buffer.get().append(data);
                        if (last && !replyFuture.isDone()) {
                            String payload = buffer.getAndSet(new StringBuilder()).toString();
                            try {
                                replyFuture.complete(objectMapper.readValue(payload, AgentMessage.class));
                            } catch (Exception ex) {
                                replyFuture.completeExceptionally(ex);
                            }
                        }
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        if (!replyFuture.isDone()) {
                            replyFuture.completeExceptionally(error);
                        }
                    }
                };

                WebSocket.Builder builder = client.newWebSocketBuilder();
                endpoint.headers().forEach(builder::header);
                webSocket = builder.buildAsync(endpoint.uri(), listener).join();
                webSocket.sendText(text, true).join();
                AgentMessage reply = replyFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                try {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
                } catch (Exception ignored) {
                    // Best-effort close; a completed reply is still valid.
                }
                return Optional.ofNullable(reply);
            } catch (Exception ex) {
                if (webSocket != null) {
                    webSocket.abort();
                }
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (ex instanceof java.util.concurrent.TimeoutException) {
                    return Optional.empty();
                }
                throw new IOException("WebSocket exchange failed for " + endpoint.agentId(), ex);
            }
        }
    }
}
