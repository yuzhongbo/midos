package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;

/**
 * Encapsulates semantic memory layer classification so ranking and prompt
 * assembly can reuse the same lifecycle policy.
 */
public interface MemoryLayerPolicy {

    MemoryLayer classify(SemanticMemoryEntry entry, String bucket, boolean hasKeySignal, long nowMillis);

    double boost(MemoryLayer layer);
}
