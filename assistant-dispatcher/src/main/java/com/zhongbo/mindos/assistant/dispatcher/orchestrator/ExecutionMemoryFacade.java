package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;

public interface ExecutionMemoryFacade {

    void record(OrchestrationExecutionResult result);

    void record(String userId, String userInput, SkillResult result, ExecutionTraceDto trace);

    void commit(String userId, MemoryWriteBatch batch);
}
