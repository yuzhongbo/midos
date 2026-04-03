package com.zhongbo.mindos.assistant.memory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A cheap local embedder used as the default in-memory implementation.
 * It favors deterministic token hashing so writes and searches stay local
 * until a stronger ONNX-backed provider is plugged in.
 */
@Service
public class HashingLocalEmbeddingService implements LocalEmbeddingService {

    private static final Pattern TOKEN_SPLIT_PATTERN = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final String BIGRAM_SEPARATOR = "␟";
    private static final double BIGRAM_WEIGHT = 0.6d;
    private static final double ROUNDING_SCALE = 10_000d;

    private final MemoryConsolidationService memoryConsolidationService;
    private final MemoryRuntimeProperties properties;

    @Autowired
    public HashingLocalEmbeddingService(MemoryConsolidationService memoryConsolidationService,
                                        MemoryRuntimeProperties properties) {
        this.memoryConsolidationService = memoryConsolidationService;
        this.properties = properties;
    }

    public HashingLocalEmbeddingService(MemoryConsolidationService memoryConsolidationService) {
        this(memoryConsolidationService, MemoryRuntimeProperties.fromSystemProperties());
    }

    @Override
    public List<Double> embed(String text) {
        if (!properties.getEmbedding().getLocal().isEnabled()) {
            return List.of();
        }
        String normalized = memoryConsolidationService
                .normalizeForEmbedding(text, properties.getEmbedding())
                .toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return List.of();
        }
        int dimensions = properties.getEmbedding().getLocal().getDimensions();
        double[] vector = new double[dimensions];
        List<String> tokens = tokenize(normalized);
        if (tokens.isEmpty()) {
            return List.of();
        }
        for (int i = 0; i < tokens.size(); i++) {
            accumulate(vector, tokens.get(i), 1.0d);
            if (i + 1 < tokens.size()) {
                accumulate(vector, tokens.get(i) + BIGRAM_SEPARATOR + tokens.get(i + 1), BIGRAM_WEIGHT);
            }
        }
        return normalize(vector);
    }

    private List<String> tokenize(String normalized) {
        String[] parts = TOKEN_SPLIT_PATTERN.split(normalized, -1);
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            tokens.add(part);
            // Character bigrams keep Chinese retrieval fully local without bringing in a
            // separate segmenter dependency; the dimension cap bounds the final vector size.
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

    private void accumulate(double[] vector, String token, double weight) {
        int slot = Math.floorMod(token.hashCode(), vector.length);
        vector[slot] += weight;
    }

    private List<Double> normalize(double[] vector) {
        double norm = 0.0d;
        for (double value : vector) {
            norm += value * value;
        }
        if (norm <= 0.0d) {
            return List.of();
        }
        double scale = Math.sqrt(norm);
        List<Double> normalized = new ArrayList<>(vector.length);
        for (double value : vector) {
            normalized.add(round(value / scale));
        }
        return List.copyOf(normalized);
    }

    private double round(double value) {
        return Math.round(value * ROUNDING_SCALE) / ROUNDING_SCALE;
    }
}
