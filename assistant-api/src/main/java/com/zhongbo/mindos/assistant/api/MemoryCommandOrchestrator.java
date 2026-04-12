package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.memory.MemoryFacade;
import com.zhongbo.mindos.assistant.memory.model.MemoryApplyResult;
import com.zhongbo.mindos.assistant.memory.model.MemoryStyleProfile;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncBatch;
import org.springframework.stereotype.Service;

@Service
public class MemoryCommandOrchestrator {

    private final MemoryFacade memoryFacade;

    public MemoryCommandOrchestrator(MemoryFacade memoryFacade) {
        this.memoryFacade = memoryFacade;
    }

    public MemoryApplyResult applyIncrementalUpdates(String userId, MemorySyncBatch batch) {
        return memoryFacade.applyIncrementalUpdates(userId, batch);
    }

    public MemoryStyleProfile updateMemoryStyleProfile(String userId,
                                                       MemoryStyleProfile profile,
                                                       boolean autoTune,
                                                       String sampleText) {
        return memoryFacade.updateMemoryStyleProfile(userId, profile, autoTune, sampleText);
    }

    public MemoryStyleProfile updateMemoryStyleProfile(String userId, MemoryStyleProfile profile) {
        return memoryFacade.updateMemoryStyleProfile(userId, profile);
    }
}
