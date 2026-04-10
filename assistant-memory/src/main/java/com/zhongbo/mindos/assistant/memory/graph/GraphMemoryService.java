package com.zhongbo.mindos.assistant.memory.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class GraphMemoryService implements GraphMemoryView, GraphMemoryGateway {

    private final GraphMemoryRepository repository;

    public GraphMemoryService() {
        this(new InMemoryGraphMemoryRepository());
    }

    public GraphMemoryService(GraphMemoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public GraphMemoryNode upsertNode(String userId, GraphMemoryNode node) {
        return repository.saveNode(userId, node);
    }

    @Override
    public GraphMemoryEdge upsertEdge(String userId, GraphMemoryEdge edge) {
        return repository.saveEdge(userId, edge);
    }

    @Override
    public GraphMemorySnapshot snapshot(String userId) {
        return new GraphMemorySnapshot(repository.listNodes(userId), repository.listEdges(userId));
    }

    @Override
    public List<GraphMemoryNode> searchNodes(String userId, String keyword, int limit) {
        String normalized = normalize(keyword);
        if (normalized.isBlank()) {
            return repository.listNodes(userId).stream().limit(Math.max(1, limit)).toList();
        }
        List<ScoredNode> scored = new ArrayList<>();
        for (GraphMemoryNode node : repository.listNodes(userId)) {
            int score = matchScore(node, normalized);
            if (score > 0) {
                scored.add(new ScoredNode(node, score));
            }
        }
        scored.sort((left, right) -> Integer.compare(right.score, left.score));
        return scored.stream().limit(Math.max(1, limit)).map(ScoredNode::node).toList();
    }

    @Override
    public List<GraphMemoryEdge> outgoingEdges(String userId, String nodeId) {
        return repository.outgoingEdges(userId, nodeId);
    }

    @Override
    public GraphMemorySnapshot traverse(String userId, GraphMemoryQuery query) {
        if (query == null) {
            return GraphMemorySnapshot.empty();
        }
        Set<String> seeds = !query.seedNodeIds().isEmpty()
                ? query.seedNodeIds()
                : searchNodes(userId, query.keyword(), query.limit()).stream().map(GraphMemoryNode::id).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (seeds.isEmpty()) {
            return GraphMemorySnapshot.empty();
        }
        Map<String, GraphMemoryNode> nodes = new LinkedHashMap<>();
        Map<String, GraphMemoryEdge> edges = new LinkedHashMap<>();
        ArrayDeque<TraversalState> queue = new ArrayDeque<>();
        seeds.forEach(seed -> queue.add(new TraversalState(seed, 0)));
        while (!queue.isEmpty() && nodes.size() < query.limit()) {
            TraversalState state = queue.removeFirst();
            if (state.depth > query.maxDepth()) {
                continue;
            }
            repository.findNode(userId, state.nodeId).ifPresent(node -> nodes.putIfAbsent(node.id(), node));
            for (GraphMemoryEdge edge : repository.outgoingEdges(userId, state.nodeId)) {
                if (!query.relationFilter().isEmpty() && !query.relationFilter().contains(edge.relation())) {
                    continue;
                }
                edges.putIfAbsent(edge.key(), edge);
                if (!nodes.containsKey(edge.targetId()) && state.depth < query.maxDepth()) {
                    queue.addLast(new TraversalState(edge.targetId(), state.depth + 1));
                }
            }
        }
        return new GraphMemorySnapshot(List.copyOf(nodes.values()), List.copyOf(edges.values()));
    }

    private int matchScore(GraphMemoryNode node, String normalizedKeyword) {
        int score = 0;
        String name = normalize(node.name());
        String type = normalize(node.type());
        if (name.contains(normalizedKeyword)) {
            score += 100;
        }
        if (type.contains(normalizedKeyword)) {
            score += 40;
        }
        for (Map.Entry<String, Object> entry : node.attributes().entrySet()) {
            String text = normalize(entry.getKey() + " " + String.valueOf(entry.getValue()));
            if (text.contains(normalizedKeyword)) {
                score += 15;
            }
        }
        return score;
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private record TraversalState(String nodeId, int depth) {
    }

    private record ScoredNode(GraphMemoryNode node, int score) {
    }
}
