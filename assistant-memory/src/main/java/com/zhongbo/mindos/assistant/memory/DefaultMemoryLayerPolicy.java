package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Lightweight layer policy that keeps the current in-memory model intact.
 * Recent items become working memory, dense factual snippets become facts,
 * and conversation rollups stay close to the buffer boundary.
 */
@Service
public class DefaultMemoryLayerPolicy implements MemoryLayerPolicy {

    private final MemoryRuntimeProperties properties;

    @Autowired
    public DefaultMemoryLayerPolicy(MemoryRuntimeProperties properties) {
        this.properties = properties;
    }

    public DefaultMemoryLayerPolicy() {
        this(MemoryRuntimeProperties.fromSystemProperties());
    }

    @Override
    public MemoryLayer classify(SemanticMemoryEntry entry, String bucket, boolean hasKeySignal, long nowMillis) {
        if (entry == null || !properties.getLayers().isEnabled()) {
            return MemoryLayer.SEMANTIC;
        }
        double ageHours = ageHours(entry, nowMillis);
        if ("conversation-rollup".equals(bucket) && ageHours <= properties.getLayers().getBufferHours()) {
            return MemoryLayer.BUFFER;
        }
        if (hasKeySignal && entry.text() != null && entry.text().length() <= properties.getLayers().getFactMaxChars()) {
            return MemoryLayer.FACT;
        }
        if (ageHours <= properties.getLayers().getWorkingHours()) {
            return MemoryLayer.WORKING;
        }
        return MemoryLayer.SEMANTIC;
    }

    @Override
    public double boost(MemoryLayer layer) {
        if (layer == null || !properties.getLayers().isEnabled()) {
            return 0.0d;
        }
        return switch (layer) {
            case BUFFER -> 2.0d;
            case WORKING -> 1.5d;
            case FACT -> 1.0d;
            case SEMANTIC -> 0.0d;
        };
    }

    private double ageHours(SemanticMemoryEntry entry, long nowMillis) {
        if (entry.createdAt() == null) {
            return Double.MAX_VALUE;
        }
        return Math.max(0.0d, (double) (nowMillis - entry.createdAt().toEpochMilli()) / 3_600_000d);
    }
}
