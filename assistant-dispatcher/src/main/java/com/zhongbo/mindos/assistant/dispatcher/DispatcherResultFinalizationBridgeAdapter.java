package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.common.dto.RoutingDecisionDto;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

final class DispatcherResultFinalizationBridgeAdapter implements DispatchResultFinalizer.FinalizationBridge {

    @FunctionalInterface
    interface SkillResultFinalizer {
        SkillFinalizeOutcome finalize(String userInput, SkillResult result, Map<String, Object> llmContext);
    }

    @FunctionalInterface
    interface RoutingDecisionEnricher {
        RoutingDecisionDto enrich(RoutingDecisionDto routingDecision,
                                  String finalChannel,
                                  boolean realtimeLookup,
                                  boolean memoryDirectBypassed,
                                  String actualSearchSource);
    }

    @FunctionalInterface
    interface RoutingReplayRecorder {
        void record(String userInput,
                    RoutingDecisionDto routingDecision,
                    RoutingReplayProbe replayProbe,
                    PromptMemoryContextDto promptMemoryContext,
                    String finalChannel);
    }

    private final SkillResultFinalizer skillResultFinalizer;
    private final Function<String, String> llmReplyCapper;
    private final Function<String, String> mcpSearchClassifier;
    private final RoutingDecisionEnricher routingDecisionEnricher;
    private final BiFunction<ExecutionTraceDto, RoutingDecisionDto, ExecutionTraceDto> traceEnricher;
    private final RoutingReplayRecorder routingReplayRecorder;

    DispatcherResultFinalizationBridgeAdapter(SkillResultFinalizer skillResultFinalizer,
                                              Function<String, String> llmReplyCapper,
                                              Function<String, String> mcpSearchClassifier,
                                              RoutingDecisionEnricher routingDecisionEnricher,
                                              BiFunction<ExecutionTraceDto, RoutingDecisionDto, ExecutionTraceDto> traceEnricher,
                                              RoutingReplayRecorder routingReplayRecorder) {
        this.skillResultFinalizer = skillResultFinalizer;
        this.llmReplyCapper = llmReplyCapper;
        this.mcpSearchClassifier = mcpSearchClassifier;
        this.routingDecisionEnricher = routingDecisionEnricher;
        this.traceEnricher = traceEnricher;
        this.routingReplayRecorder = routingReplayRecorder;
    }

    @Override
    public DispatchResultFinalizer.FinalizedSkill finalizeSkillResult(String userInput,
                                                                      SkillResult result,
                                                                      Map<String, Object> llmContext) {
        SkillFinalizeOutcome outcome = skillResultFinalizer.finalize(userInput, result, llmContext);
        return new DispatchResultFinalizer.FinalizedSkill(outcome.result(), outcome.applied());
    }

    @Override
    public String capLlmReply(String output) {
        return llmReplyCapper.apply(output);
    }

    @Override
    public String classifyMcpSearchSource(String skillName) {
        return mcpSearchClassifier.apply(skillName);
    }

    @Override
    public RoutingDecisionDto enrichRoutingDecisionWithFinalObservability(RoutingDecisionDto routingDecision,
                                                                          String finalChannel,
                                                                          boolean realtimeLookup,
                                                                          boolean memoryDirectBypassed,
                                                                          String actualSearchSource) {
        return routingDecisionEnricher.enrich(
                routingDecision,
                finalChannel,
                realtimeLookup,
                memoryDirectBypassed,
                actualSearchSource
        );
    }

    @Override
    public ExecutionTraceDto enrichTraceWithRouting(ExecutionTraceDto trace, RoutingDecisionDto routingDecision) {
        return traceEnricher.apply(trace, routingDecision);
    }

    @Override
    public void recordRoutingReplaySample(String userInput,
                                          RoutingDecisionDto routingDecision,
                                          RoutingReplayProbe replayProbe,
                                          PromptMemoryContextDto promptMemoryContext,
                                          String finalChannel) {
        routingReplayRecorder.record(userInput, routingDecision, replayProbe, promptMemoryContext, finalChannel);
    }
}
