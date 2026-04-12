package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.OrchestratorMemoryWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
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
                )
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
}
