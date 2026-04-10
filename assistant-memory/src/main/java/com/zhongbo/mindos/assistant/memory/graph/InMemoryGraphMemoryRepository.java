package com.zhongbo.mindos.assistant.memory.graph;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryGraphMemoryRepository implements GraphMemoryRepository {

    private final Map<String, UserGraph> graphs = new ConcurrentHashMap<>();

    @Override
    public GraphMemoryNode saveNode(String userId, GraphMemoryNode node) {
        UserGraph graph = graphs.computeIfAbsent(safeUserId(userId), ignored -> new UserGraph());
        GraphMemoryNode existing = graph.nodes.get(node.id());
        GraphMemoryNode saved = existing == null
                ? new GraphMemoryNode(node.id(), node.type(), node.name(), node.attributes(), node.createdAt(), node.updatedAt())
                : existing.touch(merge(existing.attributes(), node.attributes()));
        graph.nodes.put(saved.id(), saved);
        return saved;
    }

    @Override
    public GraphMemoryEdge saveEdge(String userId, GraphMemoryEdge edge) {
        UserGraph graph = graphs.computeIfAbsent(safeUserId(userId), ignored -> new UserGraph());
        GraphMemoryEdge saved = new GraphMemoryEdge(
                edge.sourceId(),
                edge.relation(),
                edge.targetId(),
                edge.weight(),
                edge.attributes(),
                edge.createdAt() == null ? Instant.now() : edge.createdAt()
        );
        graph.edges.put(saved.key(), saved);
        return saved;
    }

    @Override
    public Optional<GraphMemoryNode> findNode(String userId, String nodeId) {
        return Optional.ofNullable(graphs.getOrDefault(safeUserId(userId), UserGraph.empty()).nodes.get(nodeId));
    }

    @Override
    public List<GraphMemoryNode> listNodes(String userId) {
        return List.copyOf(graphs.getOrDefault(safeUserId(userId), UserGraph.empty()).nodes.values());
    }

    @Override
    public List<GraphMemoryEdge> listEdges(String userId) {
        return List.copyOf(graphs.getOrDefault(safeUserId(userId), UserGraph.empty()).edges.values());
    }

    @Override
    public List<GraphMemoryEdge> outgoingEdges(String userId, String nodeId) {
        List<GraphMemoryEdge> matched = new ArrayList<>();
        for (GraphMemoryEdge edge : listEdges(userId)) {
            if (edge.sourceId().equals(nodeId)) {
                matched.add(edge);
            }
        }
        return List.copyOf(matched);
    }

    private String safeUserId(String userId) {
        return userId == null ? "" : userId.trim();
    }

    private Map<String, Object> merge(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (left != null) {
            merged.putAll(left);
        }
        if (right != null) {
            merged.putAll(right);
        }
        return Map.copyOf(merged);
    }

    private static final class UserGraph {
        private final Map<String, GraphMemoryNode> nodes = new LinkedHashMap<>();
        private final Map<String, GraphMemoryEdge> edges = new LinkedHashMap<>();

        private static UserGraph empty() {
            return new UserGraph();
        }
    }
}
