package com.zhongbo.mindos.assistant.memory;

import org.springframework.stereotype.Service;

@Service
public class SemanticWriteGatePolicy {

    static final String PROP_WRITE_GATE_ENABLED = "mindos.memory.write-gate.enabled";
    static final String PROP_WRITE_GATE_MIN_LENGTH = "mindos.memory.write-gate.min-length";
    static final String PROP_WRITE_GATE_MIN_LENGTH_PREFIX = "mindos.memory.write-gate.min-length.";

    private final MemoryConsolidationService memoryConsolidationService;

    public SemanticWriteGatePolicy(MemoryConsolidationService memoryConsolidationService) {
        this.memoryConsolidationService = memoryConsolidationService;
    }

    public boolean shouldStore(String text, String bucket) {
        if (!Boolean.parseBoolean(System.getProperty(PROP_WRITE_GATE_ENABLED, "false"))) {
            return true;
        }
        String normalized = memoryConsolidationService.normalizeText(text);
        if (normalized.isBlank()) {
            return false;
        }
        if (memoryConsolidationService.containsKeySignal(normalized)) {
            return true;
        }
        int minLength = resolveMinLength(bucket);
        return normalized.length() >= minLength;
    }

    private int resolveMinLength(String bucket) {
        int globalMinLength = parsePositiveInt(System.getProperty(PROP_WRITE_GATE_MIN_LENGTH, "10"), 10);
        String normalizedBucket = normalizeBucket(bucket);
        if (normalizedBucket == null) {
            return globalMinLength;
        }
        String rawBucketValue = System.getProperty(PROP_WRITE_GATE_MIN_LENGTH_PREFIX + normalizedBucket);
        return parsePositiveInt(rawBucketValue, globalMinLength);
    }

    private String normalizeBucket(String bucket) {
        if (bucket == null) {
            return null;
        }
        String normalized = bucket.trim();
        return normalized.isBlank() ? null : normalized;
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
}
