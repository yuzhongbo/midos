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
    public MemoryNode saveNode(String userId, MemoryNode node) {
        UserGraph graph = graphs.computeIfAbsent(safeUserId(userId), ignored -> new UserGraph());
        MemoryNode existing = graph.nodes.get(node.id());
        MemoryNode saved = existing == null
                ? new MemoryNode(node.id(), node.type(), node.data(), node.createdAt(), node.updatedAt())
                : existing.touch(merge(existing.data(), node.data()));
        graph.nodes.put(saved.id(), saved);
        return saved;
    }

    @Override
    public MemoryEdge saveEdge(String userId, MemoryEdge edge) {
        UserGraph graph = graphs.computeIfAbsent(safeUserId(userId), ignored -> new UserGraph());
        MemoryEdge saved = new MemoryEdge(
                edge.from(),
                edge.to(),
                edge.relation(),
                edge.weight(),
                edge.data(),
                edge.createdAt() == null ? Instant.now() : edge.createdAt()
        );
        graph.edges.put(saved.key(), saved);
        return saved;
    }

    @Override
    public Optional<MemoryNode> findNode(String userId, String nodeId) {
        return Optional.ofNullable(graphs.getOrDefault(safeUserId(userId), UserGraph.empty()).nodes.get(nodeId));
    }

    @Override
    public List<MemoryNode> listNodes(String userId) {
        return List.copyOf(graphs.getOrDefault(safeUserId(userId), UserGraph.empty()).nodes.values());
    }

    @Override
    public List<MemoryEdge> listEdges(String userId) {
        return List.copyOf(graphs.getOrDefault(safeUserId(userId), UserGraph.empty()).edges.values());
    }

    @Override
    public List<MemoryEdge> outgoingEdges(String userId, String nodeId) {
        List<MemoryEdge> matched = new ArrayList<>();
        for (MemoryEdge edge : listEdges(userId)) {
            if (edge.from().equals(nodeId)) {
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
        private final Map<String, MemoryNode> nodes = new LinkedHashMap<>();
        private final Map<String, MemoryEdge> edges = new LinkedHashMap<>();

        private static UserGraph empty() {
            return new UserGraph();
        }
    }
}
