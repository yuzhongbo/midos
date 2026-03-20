package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SemanticMemoryService {

    private static final int MAX_APPROX_CANDIDATES = 64;
    private static final double APPROX_JACCARD_THRESHOLD = 0.82;
    private static final double APPROX_JACCARD_WITH_EMBEDDING_THRESHOLD = 0.65;
    private static final double APPROX_EMBEDDING_SIMILARITY_THRESHOLD = 0.985;

    private final MemoryConsolidationService memoryConsolidationService;
    private final Map<String, UserSemanticStore> entriesByUser = new ConcurrentHashMap<>();

    public SemanticMemoryService(MemoryConsolidationService memoryConsolidationService) {
        this.memoryConsolidationService = memoryConsolidationService;
    }

    public void remember(String userId, String text, List<Double> embedding) {
        addEntry(userId, SemanticMemoryEntry.of(text, embedding));
    }

    public void addEntry(String userId, SemanticMemoryEntry entry) {
        SemanticMemoryEntry consolidated = memoryConsolidationService.consolidateSemanticEntry(entry);
        if (consolidated == null || consolidated.text().isBlank()) {
            return;
        }
        entriesByUser.computeIfAbsent(userId, key -> new UserSemanticStore()).upsert(
                memoryConsolidationService.semanticKey(consolidated.text()),
                consolidated
        );
    }

    public List<SemanticMemoryEntry> search(String userId, int limit) {
        return search(userId, null, limit);
    }

    public List<SemanticMemoryEntry> search(String userId, String query, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        UserSemanticStore store = entriesByUser.get(userId);
        if (store == null) {
            return List.of();
        }
        return store.search(query, limit);
    }

    public boolean containsEquivalentEntry(String userId, SemanticMemoryEntry entry) {
        UserSemanticStore store = entriesByUser.get(userId);
        if (store == null) {
            return false;
        }
        SemanticMemoryEntry consolidated = memoryConsolidationService.consolidateSemanticEntry(entry);
        if (consolidated == null || consolidated.text().isBlank()) {
            return false;
        }
        return store.containsEquivalent(memoryConsolidationService.semanticKey(consolidated.text()), consolidated);
    }

    private final class UserSemanticStore {
        private final LinkedHashMap<String, StoredSemanticEntry> entries = new LinkedHashMap<>();
        private final Map<String, Set<String>> keysByToken = new HashMap<>();
        private long sequence;

        synchronized void upsert(String key, SemanticMemoryEntry entry) {
            StoredSemanticEntry existing = entries.remove(key);
            if (existing != null) {
                removeTokens(key, existing.tokens());
                entry = merge(existing.entry(), entry);
            }

            LinkedHashSet<String> tokens = tokenize(entry.text());
            StoredSemanticEntry stored = new StoredSemanticEntry(entry, tokens, ++sequence);
            entries.put(key, stored);
            for (String token : tokens) {
                keysByToken.computeIfAbsent(token, ignored -> new LinkedHashSet<>()).add(key);
            }
        }

        synchronized boolean containsKey(String key) {
            return entries.containsKey(key);
        }

        synchronized boolean containsEquivalent(String key, SemanticMemoryEntry entry) {
            if (containsKey(key)) {
                return true;
            }

            LinkedHashSet<String> queryTokens = tokenize(entry.text());
            LinkedHashSet<String> candidateKeys = new LinkedHashSet<>();
            for (String token : queryTokens) {
                candidateKeys.addAll(keysByToken.getOrDefault(token, Set.of()));
                if (candidateKeys.size() >= MAX_APPROX_CANDIDATES) {
                    break;
                }
            }
            if (candidateKeys.isEmpty()) {
                int scanned = 0;
                for (String candidateKey : entries.keySet()) {
                    candidateKeys.add(candidateKey);
                    scanned++;
                    if (scanned >= MAX_APPROX_CANDIDATES) {
                        break;
                    }
                }
            }

            String normalizedInput = memoryConsolidationService.normalizeText(entry.text()).toLowerCase(Locale.ROOT);
            for (String candidateKey : candidateKeys) {
                StoredSemanticEntry candidate = entries.get(candidateKey);
                if (candidate == null) {
                    continue;
                }
                if (isApproxEquivalent(entry, queryTokens, normalizedInput, candidate)) {
                    return true;
                }
            }
            return false;
        }

        synchronized List<SemanticMemoryEntry> search(String query, int limit) {
            List<StoredSemanticEntry> candidates = new ArrayList<>();
            LinkedHashSet<String> queryTokens = tokenize(query);
            if (queryTokens.isEmpty()) {
                candidates.addAll(entries.values());
            } else {
                LinkedHashSet<String> candidateKeys = new LinkedHashSet<>();
                for (String token : queryTokens) {
                    candidateKeys.addAll(keysByToken.getOrDefault(token, Set.of()));
                }
                if (candidateKeys.isEmpty()) {
                    candidates.addAll(entries.values());
                } else {
                    for (String candidateKey : candidateKeys) {
                        StoredSemanticEntry entry = entries.get(candidateKey);
                        if (entry != null) {
                            candidates.add(entry);
                        }
                    }
                }
            }

            String normalizedQuery = memoryConsolidationService.normalizeText(query).toLowerCase(Locale.ROOT);
            return candidates.stream()
                    .sorted(Comparator
                            .comparingInt((StoredSemanticEntry entry) -> score(entry, queryTokens, normalizedQuery))
                            .thenComparingLong(StoredSemanticEntry::sequence)
                            .reversed())
                    .limit(limit)
                    .map(StoredSemanticEntry::entry)
                    .toList();
        }

        private SemanticMemoryEntry merge(SemanticMemoryEntry existing, SemanticMemoryEntry candidate) {
            String text = candidate.text().length() >= existing.text().length() ? candidate.text() : existing.text();
            List<Double> embedding = candidate.embedding().size() >= existing.embedding().size()
                    ? candidate.embedding()
                    : existing.embedding();
            return new SemanticMemoryEntry(text, embedding,
                    existing.createdAt().isBefore(candidate.createdAt()) ? existing.createdAt() : candidate.createdAt());
        }

        private void removeTokens(String key, Set<String> tokens) {
            for (String token : tokens) {
                Set<String> keys = keysByToken.get(token);
                if (keys == null) {
                    continue;
                }
                keys.remove(key);
                if (keys.isEmpty()) {
                    keysByToken.remove(token);
                }
            }
        }

        private int score(StoredSemanticEntry entry, Set<String> queryTokens, String normalizedQuery) {
            if (queryTokens.isEmpty()) {
                return 1;
            }
            int overlap = 0;
            for (String token : queryTokens) {
                if (entry.tokens().contains(token)) {
                    overlap++;
                }
            }
            String normalizedText = memoryConsolidationService.normalizeText(entry.entry().text()).toLowerCase(Locale.ROOT);
            int containsBonus = normalizedQuery.isBlank() ? 0 : (normalizedText.contains(normalizedQuery) ? 4 : 0);
            return overlap * 10 + containsBonus;
        }

        private boolean isApproxEquivalent(SemanticMemoryEntry input,
                                           Set<String> inputTokens,
                                           String normalizedInput,
                                           StoredSemanticEntry candidate) {
            String normalizedCandidate = memoryConsolidationService.normalizeText(candidate.entry().text())
                    .toLowerCase(Locale.ROOT);
            if (normalizedInput.equals(normalizedCandidate)) {
                return true;
            }

            if (normalizedInput.length() >= 8
                    && normalizedCandidate.length() >= 8
                    && (normalizedInput.contains(normalizedCandidate) || normalizedCandidate.contains(normalizedInput))) {
                return true;
            }

            double tokenSimilarity = jaccard(inputTokens, candidate.tokens());
            double embeddingSimilarity = cosineSimilarity(input.embedding(), candidate.entry().embedding());
            boolean hasKeySignal = memoryConsolidationService.containsKeySignal(normalizedInput)
                    || memoryConsolidationService.containsKeySignal(normalizedCandidate);
            double strictTokenThreshold = hasKeySignal ? 0.9 : APPROX_JACCARD_THRESHOLD;

            if (tokenSimilarity >= strictTokenThreshold) {
                return true;
            }
            return tokenSimilarity >= APPROX_JACCARD_WITH_EMBEDDING_THRESHOLD
                    && embeddingSimilarity >= APPROX_EMBEDDING_SIMILARITY_THRESHOLD;
        }

        private double jaccard(Set<String> left, Set<String> right) {
            if (left.isEmpty() || right.isEmpty()) {
                return 0.0;
            }
            int intersection = 0;
            for (String token : left) {
                if (right.contains(token)) {
                    intersection++;
                }
            }
            if (intersection == 0) {
                return 0.0;
            }
            int union = left.size() + right.size() - intersection;
            if (union <= 0) {
                return 0.0;
            }
            return (double) intersection / union;
        }

        private double cosineSimilarity(List<Double> left, List<Double> right) {
            if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
                return 0.0;
            }
            int dimension = Math.min(left.size(), right.size());

            double dot = 0.0;
            double leftNorm = 0.0;
            double rightNorm = 0.0;
            for (int i = 0; i < dimension; i++) {
                double lv = left.get(i);
                double rv = right.get(i);
                dot += lv * rv;
                leftNorm += lv * lv;
                rightNorm += rv * rv;
            }
            if (leftNorm <= 0 || rightNorm <= 0) {
                return 0.0;
            }
            return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
        }
    }

    private LinkedHashSet<String> tokenize(String text) {
        String normalized = memoryConsolidationService.normalizeText(text).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return new LinkedHashSet<>();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        String[] parts = normalized.split("[^\\p{L}\\p{N}]+", -1);
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (part.length() == 1 && !containsHan(part)) {
                continue;
            }
            tokens.add(part);
            if (containsHan(part) && part.length() > 2) {
                for (int i = 0; i < part.length() - 1; i++) {
                    tokens.add(part.substring(i, i + 2));
                }
            }
        }
        return tokens;
    }

    private boolean containsHan(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private record StoredSemanticEntry(SemanticMemoryEntry entry, Set<String> tokens, long sequence) {
    }
}

