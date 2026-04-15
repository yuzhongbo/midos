package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProceduralMemory;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.OrchestratorMemoryWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "mindos.autonomous.runtime.enabled", havingValue = "true")
public class DefaultExecutionMemoryFacade implements ExecutionMemoryFacade {

    private final PostExecutionMemoryRecorder memoryRecorder;
    private final OrchestratorMemoryWriter memoryWriter;

    @Autowired
    public DefaultExecutionMemoryFacade(PostExecutionMemoryRecorder memoryRecorder,
                                        OrchestratorMemoryWriter memoryWriter) {
        this.memoryRecorder = memoryRecorder;
        this.memoryWriter = memoryWriter;
    }

    @Override
    public void record(OrchestrationExecutionResult result) {
        if (result == null) {
            return;
        }
        commit(
                result.userId(),
                memoryRecorder.record(
                        result.userId(),
                        result.userInput(),
                        result.recordableResult(),
                        result.trace(),
                        result.reflection()
                ).merge(result.memoryWrites())
        );
    }

    @Override
    public void record(String userId, String userInput, SkillResult result, ExecutionTraceDto trace) {
        commit(userId, memoryRecorder.record(userId, userInput, result, trace));
    }

    @Override
    public void commit(String userId, MemoryWriteBatch batch) {
        memoryWriter.commit(userId, batch);
    }

    void setProceduralMemory(ProceduralMemory proceduralMemory) {
        memoryWriter.setProceduralMemory(proceduralMemory);
    }
}
