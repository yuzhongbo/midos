package com.zhongbo.mindos.assistant.memory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class SemanticWriteGatePolicy {


    private final MemoryConsolidationService memoryConsolidationService;
    private final MemoryRuntimeProperties properties;

    @Autowired
    public SemanticWriteGatePolicy(MemoryConsolidationService memoryConsolidationService,
                                   MemoryRuntimeProperties properties) {
        this.memoryConsolidationService = memoryConsolidationService;
        this.properties = properties;
    }

    public SemanticWriteGatePolicy(MemoryConsolidationService memoryConsolidationService) {
        this(memoryConsolidationService, MemoryRuntimeProperties.fromSystemProperties());
    }

    public boolean shouldStore(String text, String bucket) {
        if (!properties.getWriteGate().isEnabled()) {
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
        int globalMinLength = parsePositiveInt(String.valueOf(properties.getWriteGate().getMinLength()), 10);
        String normalizedBucket = normalizeBucket(bucket);
        if (normalizedBucket == null) {
            return globalMinLength;
        }
        Map<String, Integer> byBucket = properties.getWriteGate().getMinLengthByBucket();
        Integer bucketValue = byBucket == null ? null : byBucket.get(normalizedBucket);
        String rawBucketValue = bucketValue == null ? null : String.valueOf(bucketValue);
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
