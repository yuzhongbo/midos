package com.zhongbo.mindos.assistant.dispatcher.agent.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAgentNetworkTest {

    private HttpServer server;
    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRouteMqMessagesBetweenSystems() {
        AgentNetworkBroker broker = AgentNetworkBroker.shared();
        broker.clear();

        DefaultAgentNetwork agentA = new DefaultAgentNetwork("agent-a", new ObjectMapper().findAndRegisterModules(), broker);
        DefaultAgentNetwork agentB = new DefaultAgentNetwork("agent-b", new ObjectMapper().findAndRegisterModules(), broker);
        agentA.registerEndpoint(AgentEndpoint.mq("agent-b", "agent-b"));
        agentB.registerEndpoint(AgentEndpoint.mq("agent-a", "agent-a"));

        agentA.send(AgentMessage.of("agent-a", "agent-b", "query", Map.of("question", "查数据")));
        AgentMessage inbound = agentB.receive().orElseThrow();
        assertEquals("agent-a", inbound.from());
        assertEquals("agent-b", inbound.to());
        assertEquals("query", inbound.type());
        assertEquals("查数据", inbound.payloadMap().get("question"));

        agentB.send(AgentMessage.reply(inbound, "agent-b", "result", Map.of("value", "ok")));
        AgentMessage reply = agentA.receive().orElseThrow();
        assertEquals("agent-b", reply.from());
        assertEquals("agent-a", reply.to());
        assertEquals("result", reply.type());
        assertEquals("ok", reply.payloadMap().get("value"));
    }

    @Test
    void shouldRoundTripHttpReply() throws IOException {
        AtomicReference<String> receivedBody = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/agent-b", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] requestBytes = exchange.getRequestBody().readAllBytes();
                receivedBody.set(new String(requestBytes, StandardCharsets.UTF_8));
                byte[] responseBytes = """
                        {"from":"agent-b","to":"agent-a","type":"result","payload":{"status":"ok","rows":3}}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    outputStream.write(responseBytes);
                }
            }
        });
        executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.start();

        AgentNetworkBroker broker = new AgentNetworkBroker();
        DefaultAgentNetwork agentA = new DefaultAgentNetwork("agent-a", new ObjectMapper().findAndRegisterModules(), broker);
        agentA.registerEndpoint(AgentEndpoint.http("agent-b", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/agent-b")));

        agentA.send(AgentMessage.of("agent-a", "agent-b", "query", Map.of("question", "select *")));

        assertTrue(receivedBody.get().contains("\"query\""));
        AgentMessage reply = agentA.receive().orElseThrow();
        assertEquals("agent-b", reply.from());
        assertEquals("agent-a", reply.to());
        assertEquals("result", reply.type());
        assertEquals("ok", reply.payloadMap().get("status"));
        assertEquals(3, ((Number) reply.payloadMap().get("rows")).intValue());
    }

    @Test
    void shouldRoundTripWebSocketThroughInjectedTransport() {
        AtomicReference<String> sentText = new AtomicReference<>("");
        WebSocketAgentNetworkTransport.WebSocketConnector connector = (endpoint, text, objectMapper, timeout) -> {
            sentText.set(text);
            return Optional.of(AgentMessage.of("agent-b", "agent-a", "result", Map.of("value", "ws-ok")));
        };
        WebSocketAgentNetworkTransport transport = new WebSocketAgentNetworkTransport(connector, new ObjectMapper().findAndRegisterModules(), Duration.ofSeconds(1));

        AgentNetworkBroker broker = new AgentNetworkBroker();
        DefaultAgentNetwork agentA = new DefaultAgentNetwork(
                "agent-a",
                new ObjectMapper().findAndRegisterModules(),
                broker,
                Map.of(AgentTransportKind.WEBSOCKET, transport)
        );
        agentA.registerEndpoint(AgentEndpoint.websocket("agent-b", URI.create("ws://example.com/agent-b")));

        agentA.send(AgentMessage.of("agent-a", "agent-b", "query", Map.of("question", "health")));

        assertTrue(sentText.get().contains("\"query\""));
        AgentMessage reply = agentA.receive().orElseThrow();
        assertEquals("agent-b", reply.from());
        assertEquals("agent-a", reply.to());
        assertEquals("result", reply.type());
        assertEquals("ws-ok", reply.payloadMap().get("value"));
    }
}
