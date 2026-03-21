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
    private static final String DEFAULT_BUCKET = "global";
    private static final String PROP_MEMORY_DECAY_HALF_LIFE_HOURS = "mindos.memory.search.decay-half-life-hours";
    private static final String PROP_SEARCH_CROSS_BUCKET_MAX = "mindos.memory.search.cross-bucket.max";
    private static final String PROP_SEARCH_CROSS_BUCKET_RATIO = "mindos.memory.search.cross-bucket.ratio";

    private final MemoryConsolidationService memoryConsolidationService;
    private final Map<String, UserSemanticStore> entriesByUser = new ConcurrentHashMap<>();

    public SemanticMemoryService(MemoryConsolidationService memoryConsolidationService) {
        this.memoryConsolidationService = memoryConsolidationService;
    }

    public void remember(String userId, String text, List<Double> embedding) {
        addEntry(userId, SemanticMemoryEntry.of(text, embedding));
    }

    public void addEntry(String userId, SemanticMemoryEntry entry) {
        addEntry(userId, entry, null);
    }

    public void addEntry(String userId, SemanticMemoryEntry entry, String bucket) {
        SemanticMemoryEntry consolidated = memoryConsolidationService.consolidateSemanticEntry(entry);
        if (consolidated == null || consolidated.text().isBlank()) {
            return;
        }
        entriesByUser.computeIfAbsent(userId, key -> new UserSemanticStore()).upsert(
                memoryConsolidationService.semanticKey(consolidated.text()),
                consolidated,
                normalizeBucket(bucket)
        );
    }

    public List<SemanticMemoryEntry> search(String userId, int limit) {
        return search(userId, null, limit);
    }

    public List<SemanticMemoryEntry> search(String userId, String query, int limit) {
        return search(userId, query, limit, null);
    }

    public List<SemanticMemoryEntry> search(String userId, String query, int limit, String preferredBucket) {
        if (limit <= 0) {
            return List.of();
        }
        UserSemanticStore store = entriesByUser.get(userId);
        if (store == null) {
            return List.of();
        }
        boolean explicitPreferredBucket = preferredBucket != null && !preferredBucket.isBlank();
        String normalizedPreferredBucket = explicitPreferredBucket ? normalizeBucket(preferredBucket) : null;
        return store.search(query, limit, normalizedPreferredBucket, explicitPreferredBucket);
    }

    public boolean containsEquivalentEntry(String userId, SemanticMemoryEntry entry) {
        return containsEquivalentEntry(userId, entry, null);
    }

    public boolean containsEquivalentEntry(String userId, SemanticMemoryEntry entry, String bucket) {
        UserSemanticStore store = entriesByUser.get(userId);
        if (store == null) {
            return false;
        }
        SemanticMemoryEntry consolidated = memoryConsolidationService.consolidateSemanticEntry(entry);
        if (consolidated == null || consolidated.text().isBlank()) {
            return false;
        }
        return store.containsEquivalent(
                memoryConsolidationService.semanticKey(consolidated.text()),
                consolidated,
                normalizeBucket(bucket)
        );
    }

    private String normalizeBucket(String bucket) {
        if (bucket == null || bucket.isBlank()) {
            return DEFAULT_BUCKET;
        }
        return bucket.trim().toLowerCase(Locale.ROOT);
    }

    private final class UserSemanticStore {
        private final LinkedHashMap<String, StoredSemanticEntry> entries = new LinkedHashMap<>();
        private final Map<String, Set<String>> keysByToken = new HashMap<>();
        private long sequence;

        synchronized void upsert(String key, SemanticMemoryEntry entry, String bucket) {
            String bucketedKey = bucket + "::" + key;
            StoredSemanticEntry existing = entries.remove(bucketedKey);
            if (existing != null) {
                removeTokens(bucketedKey, existing.tokens());
                entry = merge(existing.entry(), entry);
            }

            LinkedHashSet<String> tokens = tokenize(entry.text());
            StoredSemanticEntry stored = new StoredSemanticEntry(entry, tokens, ++sequence, bucket);
            entries.put(bucketedKey, stored);
            for (String token : tokens) {
                keysByToken.computeIfAbsent(token, ignored -> new LinkedHashSet<>()).add(bucketedKey);
            }
        }

        synchronized boolean containsKey(String key) {
            return entries.containsKey(key);
        }

        synchronized boolean containsEquivalent(String key, SemanticMemoryEntry entry, String bucket) {
            String bucketedKey = bucket + "::" + key;
            if (containsKey(bucketedKey)) {
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
                if (!candidate.bucket().equals(bucket)) {
                    continue;
                }
                if (isApproxEquivalent(entry, queryTokens, normalizedInput, candidate)) {
                    return true;
                }
            }
            return false;
        }

        synchronized List<SemanticMemoryEntry> search(String query,
                                                      int limit,
                                                      String preferredBucket,
                                                      boolean enforceCrossBucketCap) {
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
            List<StoredSemanticEntry> ranked = candidates.stream()
                    .sorted(Comparator
                            .comparingDouble((StoredSemanticEntry entry) -> score(entry, queryTokens, normalizedQuery, preferredBucket))
                            .thenComparingLong(StoredSemanticEntry::sequence)
                            .reversed())
                    .toList();

            List<StoredSemanticEntry> mixed = enforceCrossBucketCap
                    ? applyPreferredBucketMix(ranked, limit, preferredBucket)
                    : ranked.stream().limit(limit).toList();

            return mixed.stream()
                    .map(StoredSemanticEntry::entry)
                    .toList();
        }

        private List<StoredSemanticEntry> applyPreferredBucketMix(List<StoredSemanticEntry> ranked,
                                                                   int limit,
                                                                   String preferredBucket) {
            if (preferredBucket == null || preferredBucket.isBlank()) {
                return ranked.stream().limit(limit).toList();
            }
            int crossBucketCap = resolveCrossBucketCap(limit);
            List<StoredSemanticEntry> sameBucket = new ArrayList<>();
            List<StoredSemanticEntry> crossBucket = new ArrayList<>();
            for (StoredSemanticEntry entry : ranked) {
                if (preferredBucket.equals(entry.bucket())) {
                    sameBucket.add(entry);
                } else if (crossBucket.size() < crossBucketCap) {
                    crossBucket.add(entry);
                }
            }

            List<StoredSemanticEntry> mixed = new ArrayList<>(limit);
            for (StoredSemanticEntry entry : sameBucket) {
                if (mixed.size() >= limit) {
                    break;
                }
                mixed.add(entry);
            }
            for (StoredSemanticEntry entry : crossBucket) {
                if (mixed.size() >= limit) {
                    break;
                }
                mixed.add(entry);
            }
            return mixed;
        }

        private int resolveCrossBucketCap(int limit) {
            int maxCap = parsePositiveInt(System.getProperty(PROP_SEARCH_CROSS_BUCKET_MAX, "2"), 2);
            double ratio = parseRatio(System.getProperty(PROP_SEARCH_CROSS_BUCKET_RATIO, "0.5"), 0.5);
            int ratioCap = (int) Math.floor(limit * ratio);
            int cap = Math.min(maxCap, Math.max(0, ratioCap));
            return Math.min(limit, Math.max(0, cap));
        }

        private int parsePositiveInt(String raw, int fallback) {
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            try {
                int value = Integer.parseInt(raw);
                return value > 0 ? value : fallback;
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }

        private double parseRatio(String raw, double fallback) {
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            try {
                double value = Double.parseDouble(raw);
                if (Double.isNaN(value) || Double.isInfinite(value)) {
                    return fallback;
                }
                return Math.max(0.0, Math.min(1.0, value));
            } catch (NumberFormatException ex) {
                return fallback;
            }
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

        private double score(StoredSemanticEntry entry,
                             Set<String> queryTokens,
                             String normalizedQuery,
                             String preferredBucket) {
            if (queryTokens.isEmpty()) {
                return 1 + bucketBoost(entry.bucket(), preferredBucket) + recencyBoost(entry.entry());
            }
            int overlap = 0;
            for (String token : queryTokens) {
                if (entry.tokens().contains(token)) {
                    overlap++;
                }
            }
            String normalizedText = memoryConsolidationService.normalizeText(entry.entry().text()).toLowerCase(Locale.ROOT);
            int containsBonus = normalizedQuery.isBlank() ? 0 : (normalizedText.contains(normalizedQuery) ? 4 : 0);
            return overlap * 10
                    + containsBonus
                    + bucketBoost(entry.bucket(), preferredBucket)
                    + recencyBoost(entry.entry());
        }

        private double bucketBoost(String bucket, String preferredBucket) {
            if (preferredBucket == null || preferredBucket.isBlank()) {
                return 0.0;
            }
            return preferredBucket.equals(bucket) ? 8.0 : 0.0;
        }

        private double recencyBoost(SemanticMemoryEntry entry) {
            double halfLifeHours = parseHalfLifeHours();
            if (halfLifeHours <= 0) {
                return 0.0;
            }
            double ageHours = Math.max(0.0,
                    (double) (System.currentTimeMillis() - entry.createdAt().toEpochMilli()) / 3_600_000d);
            return Math.exp(-ageHours / halfLifeHours) * 3.0;
        }

        private double parseHalfLifeHours() {
            String raw = System.getProperty(PROP_MEMORY_DECAY_HALF_LIFE_HOURS, "72");
            try {
                return Math.max(1.0, Double.parseDouble(raw));
            } catch (NumberFormatException ex) {
                return 72.0;
            }
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

    private record StoredSemanticEntry(SemanticMemoryEntry entry,
                                       Set<String> tokens,
                                       long sequence,
                                       String bucket) {
    }
}

