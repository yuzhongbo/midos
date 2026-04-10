package com.zhongbo.mindos.assistant.memory.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class GraphMemoryService implements GraphMemoryView, GraphMemoryGateway {

    private final GraphMemoryRepository repository;

    public GraphMemoryService() {
        this(new InMemoryGraphMemoryRepository());
    }

    public GraphMemoryService(GraphMemoryRepository repository) {
        this.repository = repository;
    }

    public MemoryNode addNode(String userId, MemoryNode node) {
        return upsertNode(userId, node);
    }

    public MemoryEdge addEdge(String userId, MemoryEdge edge) {
        return upsertEdge(userId, edge);
    }

    public List<MemoryNode> queryRelated(String userId, String nodeId) {
        return queryRelated(userId, nodeId, "");
    }

    public List<MemoryNode> queryRelated(String userId, String nodeId, String relation) {
        if (nodeId == null || nodeId.isBlank()) {
            return List.of();
        }
        String normalizedRelation = normalize(relation);
        Map<String, MemoryNode> related = new LinkedHashMap<>();
        for (MemoryEdge edge : repository.listEdges(userId)) {
            boolean relationMatch = normalizedRelation.isBlank() || normalize(edge.relation()).equals(normalizedRelation);
            if (!relationMatch) {
                continue;
            }
            if (edge.from().equals(nodeId)) {
                repository.findNode(userId, edge.to()).ifPresent(node -> related.put(node.id(), node));
            } else if (edge.to().equals(nodeId)) {
                repository.findNode(userId, edge.from()).ifPresent(node -> related.put(node.id(), node));
            }
        }
        return List.copyOf(related.values());
    }

    public Optional<Object> infer(String userId, String key) {
        return infer(userId, key, "");
    }

    public Optional<Object> infer(String userId, String key, String hint) {
        String normalizedKey = normalize(key);
        if (normalizedKey.isBlank()) {
            return Optional.empty();
        }
        List<InferenceCandidate> matches = new ArrayList<>();
        for (MemoryNode node : repository.listNodes(userId)) {
            Object directValue = node.data().get(key);
            if (hasValue(directValue)) {
                matches.add(new InferenceCandidate(directValue, scoreNode(node, hint) + 100, "direct-node"));
            }
            for (Map.Entry<String, Object> entry : node.data().entrySet()) {
                if (normalize(entry.getKey()).equals(normalizedKey) && hasValue(entry.getValue())) {
                    matches.add(new InferenceCandidate(entry.getValue(), scoreNode(node, hint) + 100, "direct-node"));
                }
            }
        }
        for (MemoryEdge edge : repository.listEdges(userId)) {
            int relationScore = scoreRelation(edge.relation(), normalizedKey, hint);
            if (relationScore <= 0) {
                continue;
            }
            repository.findNode(userId, edge.to()).ifPresent(target -> {
                Object value = valueFromNode(target, key);
                if (hasValue(value)) {
                    matches.add(new InferenceCandidate(value, scoreNode(target, hint) + relationScore, "edge:" + edge.relation()));
                }
            });
            repository.findNode(userId, edge.from()).ifPresent(source -> {
                Object value = valueFromNode(source, key);
                if (hasValue(value)) {
                    matches.add(new InferenceCandidate(value, scoreNode(source, hint) + relationScore - 5, "edge:" + edge.relation()));
                }
            });
        }
        matches.sort((left, right) -> Integer.compare(right.score(), left.score()));
        return matches.stream().findFirst().map(InferenceCandidate::value);
    }

    public Map<String, Double> scoreCandidates(String userId, String userInput, List<String> candidateNames) {
        if (candidateNames == null || candidateNames.isEmpty()) {
            return Map.of();
        }
        String normalizedInput = normalize(userInput);
        Map<String, Double> scores = new LinkedHashMap<>();
        for (String candidateName : candidateNames) {
            if (candidateName == null || candidateName.isBlank()) {
                continue;
            }
            double score = 0.0;
            for (MemoryNode node : repository.listNodes(userId)) {
                Object skillName = node.data().get("skillName");
                if (candidateName.equals(skillName) || candidateName.equals(node.name())) {
                    score = Math.max(score, 0.65 + scoreNode(node, userInput) / 200.0);
                }
                if (!normalizedInput.isBlank() && nodeText(node).contains(normalizedInput)) {
                    Object skill = node.data().get("skillName");
                    if (candidateName.equals(skill)) {
                        score = Math.max(score, 0.80);
                    }
                }
            }
            scores.put(candidateName, Math.min(1.0, round(score)));
        }
        return Map.copyOf(scores);
    }

    @Override
    public MemoryNode upsertNode(String userId, MemoryNode node) {
        return repository.saveNode(userId, node);
    }

    @Override
    public MemoryEdge upsertEdge(String userId, MemoryEdge edge) {
        return repository.saveEdge(userId, edge);
    }

    @Override
    public GraphMemorySnapshot snapshot(String userId) {
        return new GraphMemorySnapshot(repository.listNodes(userId), repository.listEdges(userId));
    }

    @Override
    public List<MemoryNode> searchNodes(String userId, String keyword, int limit) {
        String normalized = normalize(keyword);
        if (normalized.isBlank()) {
            return repository.listNodes(userId).stream().limit(Math.max(1, limit)).toList();
        }
        List<ScoredNode> scored = new ArrayList<>();
        for (MemoryNode node : repository.listNodes(userId)) {
            int score = matchScore(node, normalized);
            if (score > 0) {
                scored.add(new ScoredNode(node, score));
            }
        }
        scored.sort((left, right) -> Integer.compare(right.score, left.score));
        return scored.stream().limit(Math.max(1, limit)).map(ScoredNode::node).toList();
    }

    @Override
    public List<MemoryEdge> outgoingEdges(String userId, String nodeId) {
        return repository.outgoingEdges(userId, nodeId);
    }

    @Override
    public GraphMemorySnapshot traverse(String userId, GraphMemoryQuery query) {
        if (query == null) {
            return GraphMemorySnapshot.empty();
        }
        Set<String> seeds = !query.seedNodeIds().isEmpty()
                ? query.seedNodeIds()
                : searchNodes(userId, query.keyword(), query.limit()).stream().map(MemoryNode::id).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (seeds.isEmpty()) {
            return GraphMemorySnapshot.empty();
        }
        Map<String, MemoryNode> nodes = new LinkedHashMap<>();
        Map<String, MemoryEdge> edges = new LinkedHashMap<>();
        ArrayDeque<TraversalState> queue = new ArrayDeque<>();
        seeds.forEach(seed -> queue.add(new TraversalState(seed, 0)));
        while (!queue.isEmpty() && nodes.size() < query.limit()) {
            TraversalState state = queue.removeFirst();
            if (state.depth > query.maxDepth()) {
                continue;
            }
            repository.findNode(userId, state.nodeId).ifPresent(node -> nodes.putIfAbsent(node.id(), node));
            for (MemoryEdge edge : repository.outgoingEdges(userId, state.nodeId)) {
                if (!query.relationFilter().isEmpty() && !query.relationFilter().contains(edge.relation())) {
                    continue;
                }
                edges.putIfAbsent(edge.key(), edge);
                if (!nodes.containsKey(edge.to()) && state.depth < query.maxDepth()) {
                    queue.addLast(new TraversalState(edge.to(), state.depth + 1));
                }
            }
        }
        return new GraphMemorySnapshot(List.copyOf(nodes.values()), List.copyOf(edges.values()));
    }

    private Object valueFromNode(MemoryNode node, String key) {
        if (node == null || key == null || key.isBlank()) {
            return null;
        }
        if (node.data().containsKey(key)) {
            return node.data().get(key);
        }
        String normalizedKey = normalize(key);
        for (Map.Entry<String, Object> entry : node.data().entrySet()) {
            if (normalize(entry.getKey()).equals(normalizedKey)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private int scoreRelation(String relation, String normalizedKey, String hint) {
        String normalizedRelation = normalize(relation);
        int score = 0;
        if (normalizedRelation.contains("current") || normalizedRelation.contains("selected") || normalizedRelation.contains("result")) {
            score += 35;
        }
        if (normalizedRelation.contains("student") && normalizedKey.contains("student")) {
            score += 40;
        }
        if (normalizedRelation.contains("entity") || normalizedRelation.contains("about")) {
            score += 15;
        }
        if (!normalize(hint).isBlank() && normalizedRelation.contains(normalize(hint))) {
            score += 15;
        }
        return score;
    }

    private int scoreNode(MemoryNode node, String hint) {
        int score = 0;
        String normalizedHint = normalize(hint);
        if (node.type().contains("result")) {
            score += 20;
        }
        if (node.type().contains("event")) {
            score += 15;
        }
        if (node.type().contains("entity")) {
            score += 10;
        }
        if (!normalizedHint.isBlank() && nodeText(node).contains(normalizedHint)) {
            score += 20;
        }
        return score;
    }

    private int matchScore(MemoryNode node, String normalizedKeyword) {
        int score = 0;
        String name = normalize(node.name());
        String type = normalize(node.type());
        if (name.contains(normalizedKeyword)) {
            score += 100;
        }
        if (type.contains(normalizedKeyword)) {
            score += 40;
        }
        if (nodeText(node).contains(normalizedKeyword)) {
            score += 20;
        }
        return score;
    }

    private String nodeText(MemoryNode node) {
        StringBuilder builder = new StringBuilder();
        builder.append(normalize(node.name())).append(' ').append(normalize(node.type()));
        for (Map.Entry<String, Object> entry : node.data().entrySet()) {
            builder.append(' ')
                    .append(normalize(entry.getKey()))
                    .append(' ')
                    .append(normalize(String.valueOf(entry.getValue())));
        }
        return builder.toString().trim();
    }

    private boolean hasValue(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private record TraversalState(String nodeId, int depth) {
    }

    private record ScoredNode(MemoryNode node, int score) {
    }

    private record InferenceCandidate(Object value, int score, String reason) {
    }
}
