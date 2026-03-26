package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.common.MemoryWriteGateMetricsReader;
import com.zhongbo.mindos.assistant.common.dto.MemoryWriteGateMetricsDto;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SemanticMemoryService implements MemoryWriteGateMetricsReader {

    private static final int MAX_APPROX_CANDIDATES = 64;
    private static final double APPROX_JACCARD_THRESHOLD = 0.82;
    private static final double APPROX_JACCARD_WITH_EMBEDDING_THRESHOLD = 0.65;
    private static final double APPROX_EMBEDDING_SIMILARITY_THRESHOLD = 0.985;
    private static final String DEFAULT_BUCKET = "global";
    private static final Pattern TOKEN_SPLIT_PATTERN = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final MemoryConsolidationService memoryConsolidationService;
    private final MemoryRuntimeProperties properties;
    private final Map<String, UserSemanticStore> entriesByUser = new ConcurrentHashMap<>();
    private final AtomicLong secondaryDuplicateCheckCount = new AtomicLong();
    private final AtomicLong secondaryDuplicateInterceptCount = new AtomicLong();

    @Autowired
    public SemanticMemoryService(MemoryConsolidationService memoryConsolidationService,
                                 MemoryRuntimeProperties properties) {
        this.memoryConsolidationService = memoryConsolidationService;
        this.properties = properties;
    }

    public SemanticMemoryService(MemoryConsolidationService memoryConsolidationService) {
        this(memoryConsolidationService, MemoryRuntimeProperties.fromSystemProperties());
    }

    public void remember(String userId, String text, List<Double> embedding) {
        addEntry(userId, SemanticMemoryEntry.of(text, embedding));
    }

    public boolean storeAcceptedEntry(String userId, SemanticMemoryEntry entry) {
        return storeAcceptedEntry(userId, entry, null);
    }

    public boolean storeAcceptedEntry(String userId, SemanticMemoryEntry entry, String bucket) {
        SemanticMemoryEntry consolidated = memoryConsolidationService.consolidateSemanticEntry(entry);
        if (consolidated == null || consolidated.text().isBlank()) {
            return false;
        }
        String normalizedBucket = normalizeBucket(bucket);
        String semanticKey = memoryConsolidationService.semanticKey(consolidated.text());
        UserSemanticStore store = entriesByUser.computeIfAbsent(userId, key -> new UserSemanticStore());
        return store.storeAccepted(
                semanticKey,
                consolidated,
                normalizedBucket,
                properties.getWriteGate().isSemanticDuplicateEnabled(),
                properties.getWriteGate().getSemanticDuplicateThreshold()
        );
    }

    public void addEntry(String userId, SemanticMemoryEntry entry) {
        addEntry(userId, entry, null);
    }

    public void addEntry(String userId, SemanticMemoryEntry entry, String bucket) {
        SemanticMemoryEntry consolidated = memoryConsolidationService.consolidateSemanticEntry(entry);
        if (consolidated == null || consolidated.text().isBlank()) {
            return;
        }
        String normalizedBucket = normalizeBucket(bucket);
        String semanticKey = memoryConsolidationService.semanticKey(consolidated.text());
        UserSemanticStore store = entriesByUser.computeIfAbsent(userId, key -> new UserSemanticStore());
        if (properties.getWriteGate().isSemanticDuplicateEnabled()) {
            secondaryDuplicateCheckCount.incrementAndGet();
            if (store.containsEquivalentForWrite(semanticKey,
                    consolidated,
                    normalizedBucket,
                    properties.getWriteGate().getSemanticDuplicateThreshold())) {
                secondaryDuplicateInterceptCount.incrementAndGet();
                return;
            }
        }
        store.upsert(semanticKey, consolidated, normalizedBucket);
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

    @Override
    public MemoryWriteGateMetricsDto snapshotWriteGateMetrics() {
        long checks = secondaryDuplicateCheckCount.get();
        long intercepted = secondaryDuplicateInterceptCount.get();
        double interceptRate = checks <= 0 ? 0.0 : (double) intercepted / checks;
        return new MemoryWriteGateMetricsDto(
                properties.getWriteGate().isSemanticDuplicateEnabled(),
                checks,
                intercepted,
                interceptRate
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
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private long sequence;

        void upsert(String key, SemanticMemoryEntry entry, String bucket) {
            Lock writeLock = lock.writeLock();
            writeLock.lock();
            try {
            String bucketedKey = bucket + "::" + key;
            StoredSemanticEntry existing = entries.remove(bucketedKey);
            if (existing != null) {
                removeTokens(bucketedKey, existing.tokens());
                entry = merge(existing.entry(), entry);
            }

            LinkedHashSet<String> tokens = tokenize(entry.text());
            String normalizedText = normalizeLower(entry.text());
            StoredSemanticEntry stored = new StoredSemanticEntry(
                    entry,
                    normalizedText,
                    tokens,
                    ++sequence,
                    bucket,
                    memoryConsolidationService.containsKeySignal(normalizedText)
            );
            entries.put(bucketedKey, stored);
            for (String token : tokens) {
                keysByToken.computeIfAbsent(token, ignored -> new LinkedHashSet<>()).add(bucketedKey);
            }
            } finally {
                writeLock.unlock();
            }
        }

        boolean storeAccepted(String key,
                              SemanticMemoryEntry entry,
                              String bucket,
                              boolean duplicateGateEnabled,
                              double tokenThresholdOverride) {
            Lock writeLock = lock.writeLock();
            writeLock.lock();
            try {
                if (containsEquivalentInternal(key, entry, bucket, null)) {
                    return false;
                }
                if (duplicateGateEnabled) {
                    secondaryDuplicateCheckCount.incrementAndGet();
                    if (containsEquivalentInternal(key, entry, bucket, tokenThresholdOverride)) {
                        secondaryDuplicateInterceptCount.incrementAndGet();
                        return false;
                    }
                }
                upsertUnsafe(key, entry, bucket);
                return true;
            } finally {
                writeLock.unlock();
            }
        }

        boolean containsEquivalent(String key, SemanticMemoryEntry entry, String bucket) {
            return containsEquivalentInternal(key, entry, bucket, null);
        }

        boolean containsEquivalentForWrite(String key,
                                           SemanticMemoryEntry entry,
                                           String bucket,
                                           double tokenThresholdOverride) {
            return containsEquivalentInternal(key, entry, bucket, tokenThresholdOverride);
        }

        private boolean containsEquivalentInternal(String key,
                                                   SemanticMemoryEntry entry,
                                                   String bucket,
                                                   Double tokenThresholdOverride) {
            Lock readLock = lock.readLock();
            readLock.lock();
            try {
            String bucketedKey = bucket + "::" + key;
            if (entries.containsKey(bucketedKey)) {
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
                int added = 0;
                for (Map.Entry<String, StoredSemanticEntry> candidateEntry : entries.entrySet()) {
                    if (!bucket.equals(candidateEntry.getValue().bucket())) {
                        continue;
                    }
                    candidateKeys.add(candidateEntry.getKey());
                    added++;
                    if (added >= MAX_APPROX_CANDIDATES) {
                        break;
                    }
                }
            }

            String normalizedInput = normalizeLower(entry.text());
            boolean inputHasKeySignal = memoryConsolidationService.containsKeySignal(normalizedInput);
            for (String candidateKey : candidateKeys) {
                StoredSemanticEntry candidate = entries.get(candidateKey);
                if (candidate == null) {
                    continue;
                }
                if (!candidate.bucket().equals(bucket)) {
                    continue;
                }
                if (isApproxEquivalent(entry,
                        queryTokens,
                        normalizedInput,
                        inputHasKeySignal,
                        candidate,
                        tokenThresholdOverride)) {
                    return true;
                }
            }
            return false;
            } finally {
                readLock.unlock();
            }
        }

        List<SemanticMemoryEntry> search(String query,
                                                      int limit,
                                                      String preferredBucket,
                                                      boolean enforceCrossBucketCap) {
            Lock readLock = lock.readLock();
            readLock.lock();
            try {
            List<StoredSemanticEntry> candidates = new ArrayList<>();
            LinkedHashSet<String> queryTokens = tokenize(query);
            if (queryTokens.isEmpty()) {
                candidates.addAll(entries.values());
            } else {
                LinkedHashSet<String> candidateKeys = collectCoarseCandidateKeys(queryTokens, limit);
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

            String normalizedQuery = normalizeLower(query);
            long nowMillis = System.currentTimeMillis();
            Comparator<StoredSemanticEntry> comparator = Comparator
                    .comparingDouble((StoredSemanticEntry entry) -> score(entry, queryTokens, normalizedQuery, preferredBucket, nowMillis))
                    .thenComparingLong(StoredSemanticEntry::sequence)
                    .reversed();

            List<StoredSemanticEntry> ranked = enforceCrossBucketCap
                    ? candidates.stream().sorted(comparator).toList()
                    : rankTopK(candidates, limit, comparator);

            List<StoredSemanticEntry> mixed = enforceCrossBucketCap
                    ? applyPreferredBucketMix(ranked, limit, preferredBucket)
                    : ranked.stream().limit(limit).toList();

            return mixed.stream()
                    .map(StoredSemanticEntry::entry)
                    .toList();
            } finally {
                readLock.unlock();
            }
        }

        private LinkedHashSet<String> collectCoarseCandidateKeys(Set<String> queryTokens, int limit) {
            LinkedHashSet<String> candidateKeys = new LinkedHashSet<>();
            int coarseCap = resolveCoarseCandidateCap(limit);
            for (String token : queryTokens) {
                Set<String> keys = keysByToken.getOrDefault(token, Set.of());
                for (String key : keys) {
                    candidateKeys.add(key);
                }
            }
            if (candidateKeys.size() <= coarseCap) {
                return candidateKeys;
            }
            List<String> ordered = new ArrayList<>(candidateKeys);
            LinkedHashSet<String> trimmed = new LinkedHashSet<>();
            int start = Math.max(0, ordered.size() - coarseCap);
            for (int i = start; i < ordered.size(); i++) {
                trimmed.add(ordered.get(i));
            }
            return trimmed;
        }

        private int resolveCoarseCandidateCap(int limit) {
            int minCandidates = Math.max(1, properties.getSearch().getCoarseMinCandidates());
            int multiplier = Math.max(1, properties.getSearch().getCoarseMultiplier());
            int byLimit = Math.max(1, limit) * multiplier;
            return Math.max(minCandidates, byLimit);
        }

        private List<StoredSemanticEntry> rankTopK(List<StoredSemanticEntry> candidates,
                                                    int limit,
                                                    Comparator<StoredSemanticEntry> comparator) {
            if (limit <= 0 || candidates.isEmpty()) {
                return List.of();
            }
            PriorityQueue<StoredSemanticEntry> top = new PriorityQueue<>(limit, comparator.reversed());
            for (StoredSemanticEntry candidate : candidates) {
                if (top.size() < limit) {
                    top.offer(candidate);
                    continue;
                }
                StoredSemanticEntry weakest = top.peek();
                if (weakest != null && comparator.compare(candidate, weakest) > 0) {
                    top.poll();
                    top.offer(candidate);
                }
            }
            List<StoredSemanticEntry> ranked = new ArrayList<>(top);
            ranked.sort(comparator);
            return ranked;
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
            int maxCap = parsePositiveInt(properties.getSearch().getCrossBucketMax(), 2);
            double ratio = parseRatio(properties.getSearch().getCrossBucketRatio(), 0.5);
            int ratioCap = (int) Math.floor(limit * ratio);
            int cap = Math.min(maxCap, Math.max(0, ratioCap));
            return Math.min(limit, Math.max(0, cap));
        }

        private int parsePositiveInt(int raw, int fallback) {
            return raw > 0 ? raw : fallback;
        }

        private double parseRatio(double raw, double fallback) {
            if (Double.isNaN(raw) || Double.isInfinite(raw)) {
                return fallback;
            }
            return Math.max(0.0, Math.min(1.0, raw));
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

        private void upsertUnsafe(String key, SemanticMemoryEntry entry, String bucket) {
            String bucketedKey = bucket + "::" + key;
            StoredSemanticEntry existing = entries.remove(bucketedKey);
            if (existing != null) {
                removeTokens(bucketedKey, existing.tokens());
                entry = merge(existing.entry(), entry);
            }

            LinkedHashSet<String> tokens = tokenize(entry.text());
            String normalizedText = normalizeLower(entry.text());
            StoredSemanticEntry stored = new StoredSemanticEntry(
                    entry,
                    normalizedText,
                    tokens,
                    ++sequence,
                    bucket,
                    memoryConsolidationService.containsKeySignal(normalizedText)
            );
            entries.put(bucketedKey, stored);
            for (String token : tokens) {
                keysByToken.computeIfAbsent(token, ignored -> new LinkedHashSet<>()).add(bucketedKey);
            }
        }

        private double score(StoredSemanticEntry entry,
                             Set<String> queryTokens,
                             String normalizedQuery,
                             String preferredBucket,
                             long nowMillis) {
            if (queryTokens.isEmpty()) {
                return 1 + bucketBoost(entry.bucket(), preferredBucket) + recencyBoost(entry.entry(), nowMillis);
            }
            int overlap = 0;
            for (String token : queryTokens) {
                if (entry.tokens().contains(token)) {
                    overlap++;
                }
            }
            int containsBonus = normalizedQuery.isBlank() ? 0 : (entry.normalizedText().contains(normalizedQuery) ? 4 : 0);
            return overlap * 10
                    + containsBonus
                    + bucketBoost(entry.bucket(), preferredBucket)
                    + recencyBoost(entry.entry(), nowMillis);
        }

        private double bucketBoost(String bucket, String preferredBucket) {
            if (preferredBucket == null || preferredBucket.isBlank()) {
                return 0.0;
            }
            return preferredBucket.equals(bucket) ? 8.0 : 0.0;
        }

        private double recencyBoost(SemanticMemoryEntry entry, long nowMillis) {
            double halfLifeHours = parseHalfLifeHours();
            if (halfLifeHours <= 0) {
                return 0.0;
            }
            double ageHours = Math.max(0.0,
                    (double) (nowMillis - entry.createdAt().toEpochMilli()) / 3_600_000d);
            return Math.exp(-ageHours / halfLifeHours) * 3.0;
        }

        private double parseHalfLifeHours() {
            return Math.max(1.0, properties.getSearch().getDecayHalfLifeHours());
        }

        private boolean isApproxEquivalent(SemanticMemoryEntry input,
                                           Set<String> inputTokens,
                                           String normalizedInput,
                                           boolean inputHasKeySignal,
                                           StoredSemanticEntry candidate,
                                           Double tokenThresholdOverride) {
            String normalizedCandidate = candidate.normalizedText();
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
            boolean hasKeySignal = inputHasKeySignal || candidate.hasKeySignal();
            double thresholdOverride = sanitizeThreshold(tokenThresholdOverride);
            double strictTokenThreshold = hasKeySignal
                    ? Math.max(thresholdOverride, 0.9)
                    : thresholdOverride;

            if (tokenSimilarity >= strictTokenThreshold) {
                return true;
            }
            return tokenSimilarity >= APPROX_JACCARD_WITH_EMBEDDING_THRESHOLD
                    && embeddingSimilarity >= APPROX_EMBEDDING_SIMILARITY_THRESHOLD;
        }

        private double sanitizeThreshold(Double threshold) {
            if (threshold == null || !Double.isFinite(threshold)) {
                return APPROX_JACCARD_THRESHOLD;
            }
            return Math.max(0.0, Math.min(1.0, threshold));
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
        String normalized = normalizeLower(text);
        if (normalized.isBlank()) {
            return new LinkedHashSet<>();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        String[] parts = TOKEN_SPLIT_PATTERN.split(normalized, -1);
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

    private String normalizeLower(String text) {
        return memoryConsolidationService.normalizeText(text).toLowerCase(Locale.ROOT);
    }

    private record StoredSemanticEntry(SemanticMemoryEntry entry,
                                       String normalizedText,
                                       Set<String> tokens,
                                       long sequence,
                                       String bucket,
                                       boolean hasKeySignal) {
    }
}



