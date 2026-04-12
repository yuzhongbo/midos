package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.common.dto.RoutingDecisionDto;
import com.zhongbo.mindos.assistant.dispatcher.MetaOrchestratorService.MetaOrchestrationResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.MasterOrchestrationResult;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;

import java.util.List;
import java.util.Map;

final class DispatchResultFinalizer {

    private static final String LLM_CHANNEL = "llm";
    private static final String MEMORY_DIRECT_CHANNEL = "memory.direct";

    private final DecisionOrchestrator decisionOrchestrator;
    private final DispatchMemoryLifecycle dispatchMemoryLifecycle;
    private final PersonaCoreService personaCoreService;
    private final FinalizationBridge bridge;

    DispatchResultFinalizer(DecisionOrchestrator decisionOrchestrator,
                            DispatchMemoryLifecycle dispatchMemoryLifecycle,
                            PersonaCoreService personaCoreService,
                            FinalizationBridge bridge) {
        this.decisionOrchestrator = decisionOrchestrator;
        this.dispatchMemoryLifecycle = dispatchMemoryLifecycle;
        this.personaCoreService = personaCoreService;
        this.bridge = bridge;
    }

    DispatchResult finalizeMetaOrchestration(String userId,
                                             String userInput,
                                             MetaOrchestrationResult orchestration,
                                             Map<String, Object> llmContext,
                                             Map<String, Object> resolvedProfileContext,
                                             PromptMemoryContextDto promptMemoryContext,
                                             RoutingReplayProbe replayProbe,
                                             DispatchExecutionState state) {
        SkillResult result = orchestration.result();
        SkillResult normalized = normalizeSkillResult(userInput, result, llmContext, state);
        RoutingDecisionDto routingWithObservability = bridge.enrichRoutingDecisionWithFinalObservability(
                state.routingDecision(),
                normalized.skillName(),
                state.realtimeLookup(),
                state.memoryDirectBypassed(),
                bridge.classifyMcpSearchSource(normalized.skillName())
        );
        ExecutionTraceDto trace = bridge.enrichTraceWithRouting(orchestration.trace(), routingWithObservability);
        return complete(
                userId,
                userInput,
                normalized,
                trace,
                resolvedProfileContext,
                promptMemoryContext,
                replayProbe,
                state.routingDecision(),
                state
        );
    }

    DispatchResult finalizeStreamResult(String userId,
                                        String userInput,
                                        SkillResult result,
                                        Map<String, Object> llmContext,
                                        Map<String, Object> resolvedProfileContext,
                                        PromptMemoryContextDto promptMemoryContext,
                                        RoutingReplayProbe replayProbe,
                                        DispatchExecutionState state) {
        SkillResult normalized = normalizeSkillResult(userInput, result, llmContext, state);
        RoutingDecisionDto routingWithObservability = bridge.enrichRoutingDecisionWithFinalObservability(
                state.routingDecision(),
                normalized.skillName(),
                state.realtimeLookup(),
                state.memoryDirectBypassed(),
                bridge.classifyMcpSearchSource(normalized.skillName())
        );
        ExecutionTraceDto trace = new ExecutionTraceDto("stream-single-pass", 0, null, List.of(), routingWithObservability);
        return complete(
                userId,
                userInput,
                normalized,
                trace,
                resolvedProfileContext,
                promptMemoryContext,
                replayProbe,
                state.routingDecision(),
                state
        );
    }

    DispatchResult finalizeMasterResult(String userId,
                                        String userInput,
                                        Decision decision,
                                        MasterOrchestrationResult orchestration,
                                        SkillResult result,
                                        Map<String, Object> llmContext,
                                        Map<String, Object> resolvedProfileContext,
                                        PromptMemoryContextDto promptMemoryContext,
                                        RoutingReplayProbe replayProbe,
                                        DispatchExecutionState state) {
        SkillResult normalized = normalizeSkillResult(userInput, result, llmContext, state);
        RoutingDecisionDto baseRoutingDecision = state.routingDecision();
        if (baseRoutingDecision == null) {
            baseRoutingDecision = new RoutingDecisionDto(
                    "multi-agent-master",
                    decision.target(),
                    decision.confidence(),
                    List.of("multi-agent master orchestrator used"),
                    List.of()
            );
        }
        RoutingDecisionDto routingWithObservability = bridge.enrichRoutingDecisionWithFinalObservability(
                baseRoutingDecision,
                normalized.skillName(),
                state.realtimeLookup(),
                state.memoryDirectBypassed(),
                bridge.classifyMcpSearchSource(normalized.skillName())
        );
        state.setRoutingDecision(routingWithObservability);
        ExecutionTraceDto trace = orchestration == null
                ? new ExecutionTraceDto(
                        "multi-agent-master",
                        0,
                        new CritiqueReportDto(
                                normalized.success(),
                                normalized.success() ? "multi-agent success" : defaultText(normalized.output(), "multi-agent failed"),
                                normalized.success() ? "none" : "failed"
                        ),
                        List.of(),
                        routingWithObservability
                )
                : bridge.enrichTraceWithRouting(orchestration.trace(), routingWithObservability);
        return complete(
                userId,
                userInput,
                normalized,
                trace,
                resolvedProfileContext,
                promptMemoryContext,
                replayProbe,
                state.routingDecision(),
                state
        );
    }

    interface FinalizationBridge {
        FinalizedSkill finalizeSkillResult(String userInput, SkillResult result, Map<String, Object> llmContext);

        String capLlmReply(String output);

        String classifyMcpSearchSource(String skillName);

        RoutingDecisionDto enrichRoutingDecisionWithFinalObservability(RoutingDecisionDto routingDecision,
                                                                       String finalChannel,
                                                                       boolean realtimeLookup,
                                                                       boolean memoryDirectBypassed,
                                                                       String actualSearchSource);

        ExecutionTraceDto enrichTraceWithRouting(ExecutionTraceDto trace, RoutingDecisionDto routingDecision);

        void recordRoutingReplaySample(String userInput,
                                       RoutingDecisionDto routingDecision,
                                       RoutingReplayProbe replayProbe,
                                       PromptMemoryContextDto promptMemoryContext,
                                       String finalChannel);
    }

    record FinalizedSkill(SkillResult result, boolean applied) {
    }

    private SkillResult normalizeSkillResult(String userInput,
                                             SkillResult result,
                                             Map<String, Object> llmContext,
                                             DispatchExecutionState state) {
        FinalizedSkill finalized = bridge.finalizeSkillResult(userInput, result, llmContext);
        state.setSkillPostprocessSent(finalized.applied());
        SkillResult normalized = finalized.result();
        if (LLM_CHANNEL.equals(normalized.skillName())) {
            normalized = SkillResult.success(LLM_CHANNEL, bridge.capLlmReply(normalized.output()));
        }
        state.setMemoryDirectBypassed(state.realtimeLookup() && !MEMORY_DIRECT_CHANNEL.equalsIgnoreCase(normalized.skillName()));
        return normalized;
    }

    private DispatchResult complete(String userId,
                                    String userInput,
                                    SkillResult result,
                                    ExecutionTraceDto trace,
                                    Map<String, Object> resolvedProfileContext,
                                    PromptMemoryContextDto promptMemoryContext,
                                    RoutingReplayProbe replayProbe,
                                    RoutingDecisionDto replayRoutingDecision,
                                    DispatchExecutionState state) {
        state.setFinalResultSuccess(result.success());
        MemoryWriteBatch memoryWrites = state.pendingMemoryWrites()
                .merge(dispatchMemoryLifecycle.recordSkillOutcome(userId, result))
                .merge(personaCoreService.learnFromTurn(userId, resolvedProfileContext, result));
        decisionOrchestrator.commitMemoryWrites(userId, memoryWrites);
        decisionOrchestrator.recordOutcome(userId, userInput, result, trace);
        bridge.recordRoutingReplaySample(userInput, replayRoutingDecision, replayProbe, promptMemoryContext, result.skillName());
        return new DispatchResult(result.output(), result.skillName(), trace);
    }

    void flushPendingMemoryWrites(String userId, DispatchExecutionState state) {
        decisionOrchestrator.commitMemoryWrites(userId, state == null ? MemoryWriteBatch.empty() : state.pendingMemoryWrites());
    }

    private String defaultText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
