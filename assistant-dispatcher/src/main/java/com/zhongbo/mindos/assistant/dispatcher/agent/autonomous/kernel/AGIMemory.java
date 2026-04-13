package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AGIMemory {

    private final KVStore shortTerm = new KVStore();
    private final GraphStore longTerm = new GraphStore();
    private final VectorStore semantic = new VectorStore();

    public KVStore shortTerm() {
        return shortTerm;
    }

    public GraphStore longTerm() {
        return longTerm;
    }

    public VectorStore semantic() {
        return semantic;
    }

    public static final class KVStore {
        private final Map<String, Map<String, Object>> values = new ConcurrentHashMap<>();

        public void put(String namespace, Map<String, Object> attributes) {
            if (namespace == null || namespace.isBlank()) {
                return;
            }
            values.put(namespace.trim(), attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes)));
        }

        public Map<String, Object> get(String namespace) {
            if (namespace == null || namespace.isBlank()) {
                return Map.of();
            }
            return values.getOrDefault(namespace.trim(), Map.of());
        }
    }

    public static final class GraphStore {
        private final Map<String, Set<String>> adjacency = new ConcurrentHashMap<>();

        public void link(String from, String to) {
            if (from == null || from.isBlank() || to == null || to.isBlank()) {
                return;
            }
            adjacency.computeIfAbsent(from.trim(), ignored -> java.util.Collections.synchronizedSet(new LinkedHashSet<>())).add(to.trim());
        }

        public List<String> neighbors(String from) {
            if (from == null || from.isBlank()) {
                return List.of();
            }
            Set<String> neighbors = adjacency.get(from.trim());
            return neighbors == null || neighbors.isEmpty() ? List.of() : List.copyOf(neighbors);
        }
    }

    public static final class VectorStore {
        private final Map<String, String> texts = new ConcurrentHashMap<>();

        public void put(String id, String text) {
            if (id == null || id.isBlank() || text == null || text.isBlank()) {
                return;
            }
            texts.put(id.trim(), text.trim());
        }

        public List<String> search(String query, int limit) {
            if (query == null || query.isBlank()) {
                return List.of();
            }
            String normalizedQuery = query.trim().toLowerCase(java.util.Locale.ROOT);
            List<Map.Entry<String, Double>> scored = new ArrayList<>();
            for (Map.Entry<String, String> entry : texts.entrySet()) {
                double score = overlap(normalizedQuery, entry.getValue().toLowerCase(java.util.Locale.ROOT));
                if (score > 0.0) {
                    scored.add(Map.entry(entry.getKey(), score));
                }
            }
            scored.sort(Comparator.comparingDouble(Map.Entry<String, Double>::getValue).reversed());
            int safeLimit = limit <= 0 ? 3 : limit;
            return scored.stream().limit(safeLimit).map(Map.Entry::getKey).toList();
        }

        private double overlap(String query, String text) {
            Set<String> queryTerms = new LinkedHashSet<>(List.of(query.split("[\\s,，。;；]+")));
            if (queryTerms.isEmpty()) {
                return 0.0;
            }
            long matches = queryTerms.stream().filter(term -> !term.isBlank() && text.contains(term)).count();
            return matches / (double) queryTerms.size();
        }
    }
}
