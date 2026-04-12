package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.ReflectionAgent;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.ReflectionResult;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class DefaultOrchestrationReflectionAnalyzer implements OrchestrationReflectionAnalyzer {

    private final ReflectionAgent reflectionAgent;

    @Autowired
    public DefaultOrchestrationReflectionAnalyzer(@Autowired(required = false) ReflectionAgent reflectionAgent) {
        this.reflectionAgent = reflectionAgent;
    }

    @Override
    public OrchestrationExecutionResult analyze(OrchestrationExecutionResult result) {
        if (result == null || result.recordableResult() == null || reflectionAgent == null) {
            return result == null ? null : result.withReflection(emptyReflection(result));
        }
        ReflectionResult reflection = reflectionAgent.reflect(
                result.userId(),
                result.userInput(),
                result.trace(),
                result.recordableResult(),
                result.params(),
                result.request() == null || result.request().skillContext() == null
                        ? Map.of()
                        : result.request().skillContext().attributes()
        );
        return result.withReflection(reflection == null ? emptyReflection(result) : reflection);
    }

    private ReflectionResult emptyReflection(OrchestrationExecutionResult result) {
        return new ReflectionResult(
                result != null && result.response() != null && result.response().success(),
                "",
                "",
                "none",
                Map.of(),
                List.of(),
                false,
                false,
                Instant.now(),
                MemoryWriteBatch.empty()
        );
    }
}
