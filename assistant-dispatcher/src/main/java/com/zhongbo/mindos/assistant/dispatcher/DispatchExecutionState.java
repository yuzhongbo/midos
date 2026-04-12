package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.dto.RoutingDecisionDto;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class DispatchExecutionState {

    private final AtomicReference<RoutingDecisionDto> routingDecision = new AtomicReference<>();
    private final AtomicBoolean skillPostprocessSent = new AtomicBoolean(false);
    private final AtomicBoolean finalResultSuccess = new AtomicBoolean(false);
    private final AtomicBoolean realtimeLookup = new AtomicBoolean(false);
    private final AtomicBoolean memoryDirectBypassed = new AtomicBoolean(false);
    private final AtomicBoolean outcomeAlreadyRecorded = new AtomicBoolean(false);
    private final AtomicReference<MemoryWriteBatch> pendingMemoryWrites = new AtomicReference<>(MemoryWriteBatch.empty());

    RoutingDecisionDto routingDecision() {
        return routingDecision.get();
    }

    void setRoutingDecision(RoutingDecisionDto value) {
        routingDecision.set(value);
    }

    boolean skillPostprocessSent() {
        return skillPostprocessSent.get();
    }

    void setSkillPostprocessSent(boolean value) {
        skillPostprocessSent.set(value);
    }

    boolean finalResultSuccess() {
        return finalResultSuccess.get();
    }

    void setFinalResultSuccess(boolean value) {
        finalResultSuccess.set(value);
    }

    boolean realtimeLookup() {
        return realtimeLookup.get();
    }

    void setRealtimeLookup(boolean value) {
        realtimeLookup.set(value);
    }

    boolean memoryDirectBypassed() {
        return memoryDirectBypassed.get();
    }

    void setMemoryDirectBypassed(boolean value) {
        memoryDirectBypassed.set(value);
    }

    boolean outcomeAlreadyRecorded() {
        return outcomeAlreadyRecorded.get();
    }

    void setOutcomeAlreadyRecorded(boolean value) {
        outcomeAlreadyRecorded.set(value);
    }

    MemoryWriteBatch pendingMemoryWrites() {
        return pendingMemoryWrites.get();
    }

    void mergeMemoryWrites(MemoryWriteBatch writes) {
        if (writes == null || writes.isEmpty()) {
            return;
        }
        pendingMemoryWrites.updateAndGet(existing -> existing == null ? writes : existing.merge(writes));
    }
}
