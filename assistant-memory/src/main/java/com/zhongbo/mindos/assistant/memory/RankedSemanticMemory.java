package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;

record RankedSemanticMemory(SemanticMemoryEntry entry,
                            String bucket,
                            long sequence,
                            MemoryLayer layer,
                            double lexicalScore,
                            double vectorScore,
                            double recencyScore,
                            double finalScore) {
}
