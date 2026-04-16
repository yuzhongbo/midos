package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.ContextCompressionMetricsReader;
import com.zhongbo.mindos.assistant.common.DispatcherRoutingMetricsReader;
import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.ContextCompressionMetricsDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryContributionMetricsDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryHitMetricsDto;
import com.zhongbo.mindos.assistant.common.dto.LocalEscalationMetricsDto;
import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.common.dto.RoutingReplayDatasetDto;
import com.zhongbo.mindos.assistant.common.dto.RoutingReplayItemDto;
import com.zhongbo.mindos.assistant.common.dto.RoutingDecisionDto;
import com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto;
import com.zhongbo.mindos.assistant.common.dto.SkillPreAnalyzeMetricsDto;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import com.zhongbo.mindos.assistant.skill.DecisionCapabilityCatalog;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.InMemoryParamSchemaRegistry;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamValidator;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamSchemaRegistry;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.routing.DispatchPlan;
import com.zhongbo.mindos.assistant.dispatcher.system.DevelopmentWorkflowService;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalyzer;
import com.zhongbo.mindos.assistant.skill.SkillExecutionGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class DispatcherService implements ContextCompressionMetricsReader,
        DispatcherRoutingMetricsReader,
        DispatcherFacade {

    private static final Logger LOGGER = Logger.getLogger(DispatcherService.class.getName());

    private final SkillCatalogFacade skillEngine;
    private final SkillDslParser skillDslParser;
    private final SkillCapabilityPolicy skillCapabilityPolicy;
    private final PersonaCoreService personaCoreService;
    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final DispatcherMemoryCommandService memoryCommandService;
    private final SemanticAnalyzer semanticAnalyzer;
    private final LLMDecisionEngine llmDecisionEngine;
    private final boolean preferenceReuseEnabled;
    private final boolean habitRoutingEnabled;
    private final int habitRoutingMinTotalCount;
    private final double habitRoutingMinSuccessRate;
    private final boolean habitExplainHintEnabled;
    private final int habitContinuationInputMaxLength;
    private final int habitRoutingRecentWindowSize;
    private final int habitRoutingRecentMinSuccessCount;
    private final double habitRoutingRecentMaxAgeHours;
    private final int promptMaxChars;
    private final int memoryContextMaxChars;
    private final int llmReplyMaxChars;
    private final int skillGuardMaxConsecutive;
    private final int skillGuardRecentWindowSize;
    private final int skillGuardRepeatInputThreshold;
    private final long skillGuardCooldownSeconds;
    private final boolean preExecuteHeavySkillLoopGuardEnabled;
    private final Set<String> preExecuteHeavySkillLoopGuardSkills;
    private final long eqCoachImTimeoutMs;
    private final String eqCoachImTimeoutReply;
    private final boolean promptInjectionGuardEnabled;
    private final List<String> promptInjectionRiskTerms;
    private final String promptInjectionSafeReply;
    private final int llmRoutingShortlistMaxSkills;
    private final int llmDslMemoryContextMaxChars;
    private final boolean llmRoutingConversationalBypassEnabled;
    private final boolean realtimeIntentBypassEnabled;
    private final boolean braveFirstSearchRoutingEnabled;
    private final Set<String> realtimeIntentTerms;
    private final boolean realtimeIntentMemoryShrinkEnabled;
    private final int realtimeIntentMemoryShrinkMaxChars;
    private final boolean realtimeIntentMemoryShrinkIncludePersona;
    private final String skillPreAnalyzeMode;
    private final int skillPreAnalyzeConfidenceThreshold;
    private final Set<String> skillPreAnalyzeSkipSkills;
    private final String llmDslProvider;
    private final String llmDslPreset;
    private final String llmDslModel;
    private final String llmFallbackProvider;
    private final String llmFallbackPreset;
    private final String llmFallbackModel;
    private final boolean localEscalationEnabled;
    private final String localEscalationCloudProvider;
    private final String localEscalationCloudPreset;
    private final String localEscalationCloudModel;
    private final int llmDslMaxTokens;
    private final int llmFallbackMaxTokens;
    private final int skillFinalizeMaxTokens;
    private final boolean localEscalationQualityEnabled;
    private final int localEscalationQualityMaxReplyChars;
    private final Set<String> localEscalationQualityInputTerms;
    private final Set<String> localEscalationQualityReplyTerms;
    private final boolean skillFinalizeWithLlmEnabled;
    private final Set<String> skillFinalizeWithLlmSkills;
    private final int skillFinalizeWithLlmMaxOutputChars;
    private final String skillFinalizeWithLlmProvider;
    private final String skillFinalizeWithLlmPreset;
    private final String skillFinalizeWithLlmModel;
    private final boolean localEscalationResourceGuardEnabled;
    private final int localEscalationResourceGuardMinFreeMemoryMb;
    private final double localEscalationResourceGuardMinFreeMemoryRatio;
    private final int localEscalationResourceGuardMinAvailableProcessors;
    private final int memoryContextKeepRecentTurns;
    private final int memoryContextHistorySummaryMinTurns;
    private final double semanticAnalysisRouteMinConfidence;
    private final double semanticAnalysisClarifyMinConfidence;
    private final boolean behaviorLearningEnabled;
    private final int behaviorLearningWindowSize;
    private final double behaviorLearningDefaultParamThreshold;
    private final boolean semanticAnalysisSkipShortSimpleEnabled;
    private final boolean preferSuggestedSkillEnabled;
    private final double preferSuggestedSkillMinConfidence;
    private final boolean parallelDetectedSkillRoutingEnabled;
    private final int parallelDetectedSkillRoutingMaxCandidates;
    private final long parallelDetectedSkillRoutingTimeoutMs;
    private final int routingReplayMaxSamples;
    private final Object routingReplayLock = new Object();
    private final Deque<RoutingReplayItemDto> routingReplaySamples = new ArrayDeque<>();
    private final AtomicLong contextCompressionRequestCount = new AtomicLong();
    private final AtomicLong contextCompressionAppliedCount = new AtomicLong();
    private final AtomicLong contextCompressionInputChars = new AtomicLong();
    private final AtomicLong contextCompressionOutputChars = new AtomicLong();
    private final AtomicLong contextCompressionSummarizedTurns = new AtomicLong();
    private final AtomicLong skillPreAnalyzeRequestCount = new AtomicLong();
    private final AtomicLong skillPreAnalyzeExecutedCount = new AtomicLong();
    private final AtomicLong skillPreAnalyzeAcceptedCount = new AtomicLong();
    private final AtomicLong skillPreAnalyzeSkippedByGateCount = new AtomicLong();
    private final AtomicLong skillPreAnalyzeSkippedBySkillCount = new AtomicLong();
    private final AtomicLong detectedSkillLoopSkipBlockedCount = new AtomicLong();
    private final AtomicLong skillTimeoutTriggeredCount = new AtomicLong();
    private final AtomicLong memoryHitRequestCount = new AtomicLong();
    private final AtomicLong memoryHitSemanticCount = new AtomicLong();
    private final AtomicLong memoryHitProceduralCount = new AtomicLong();
    private final AtomicLong memoryHitRollupCount = new AtomicLong();
    private final AtomicLong memoryContributionRequestCount = new AtomicLong();
    private final AtomicLong memoryContributionRecentCount = new AtomicLong();
    private final AtomicLong memoryContributionSemanticCount = new AtomicLong();
    private final AtomicLong memoryContributionProceduralCount = new AtomicLong();
    private final AtomicLong memoryContributionPersonaCount = new AtomicLong();
    private final AtomicLong memoryContributionRollupCount = new AtomicLong();
    private final AtomicLong localEscalationAttemptCount = new AtomicLong();
    private final AtomicLong localEscalationHitCount = new AtomicLong();
    private final AtomicLong fallbackChainAttemptCount = new AtomicLong();
    private final AtomicLong fallbackChainHitCount = new AtomicLong();
    private final Map<String, AtomicLong> escalationReasonCounters = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong routingReplayTotalCapturedCount = new AtomicLong();
    private final ParamValidator paramValidator;
    private final SkillCommandAssembler skillCommandAssembler;
    private final DecisionParamAssembler decisionParamAssembler;
    private final DispatchMemoryLifecycle dispatchMemoryLifecycle;
    private final DispatchHeuristicsSupport dispatchHeuristicsSupport;
    private final DispatchLlmSupport dispatchLlmSupport;
    private final BehaviorRoutingSupport behaviorRoutingSupport;
    private final SemanticRoutingSupport semanticRoutingSupport;
    private final DispatcherAnswerMode answerMode;
    private final ConversationMemoryModeService conversationMemoryModeService;
    private volatile HermesAssistantRuntime hermesAssistantRuntime;
    private ParamSchemaRegistry paramSchemaRegistry;
    private SkillExecutionGateway skillExecutionGateway;
    private List<DispatchSkillDslResolver> dispatchSkillDslResolvers = List.of();

    public DispatcherService(SkillCatalogFacade skillEngine,
                             SkillDslParser skillDslParser,
                             ParamValidator paramValidator,
                             SkillCapabilityPolicy skillCapabilityPolicy,
                              PersonaCoreService personaCoreService,
                               DispatcherMemoryFacade dispatcherMemoryFacade,
                               LlmClient llmClient,
                               SemanticAnalyzer semanticAnalyzer,
                               PromptBuilder promptBuilder,
                               LLMDecisionEngine llmDecisionEngine,
                              boolean preferenceReuseEnabled,
                              boolean habitRoutingEnabled,
                              int habitRoutingMinTotalCount,
                             double habitRoutingMinSuccessRate,
                             boolean habitExplainHintEnabled,
                             int habitContinuationInputMaxLength,
                             int habitRoutingRecentWindowSize,
                             int habitRoutingRecentMinSuccessCount,
                             int habitRoutingRecentMaxAgeHours,
                             int promptMaxChars,
                             int memoryContextMaxChars,
                             int llmReplyMaxChars,
                             int skillGuardMaxConsecutive,
                             int skillGuardRecentWindowSize,
                             int skillGuardRepeatInputThreshold,
                             int skillGuardCooldownSeconds,
                             boolean preExecuteHeavySkillLoopGuardEnabled,
                             String preExecuteHeavySkillLoopGuardSkills,
                             long eqCoachImTimeoutMs,
                             String eqCoachImTimeoutReply,
                             boolean promptInjectionGuardEnabled,
                             String promptInjectionRiskTerms,
                             String promptInjectionSafeReply,
                             int llmRoutingShortlistMaxSkills,
                             int llmDslMemoryContextMaxChars,
                             boolean llmRoutingConversationalBypassEnabled,
                             boolean realtimeIntentBypassEnabled,
                             boolean braveFirstSearchRoutingEnabled,
                             String realtimeIntentTerms,
                             boolean realtimeIntentMemoryShrinkEnabled,
                             int realtimeIntentMemoryShrinkMaxChars,
                             boolean realtimeIntentMemoryShrinkIncludePersona,
                             String skillPreAnalyzeMode,
                             int skillPreAnalyzeConfidenceThreshold,
                             String skillPreAnalyzeSkipSkills,
                             DispatcherLlmTuningProperties llmTuningProperties,
                             boolean skillFinalizeWithLlmEnabled,
                             String skillFinalizeWithLlmSkills,
                             int skillFinalizeWithLlmMaxOutputChars,
                             String skillFinalizeWithLlmProvider,
                             String skillFinalizeWithLlmPreset,
                             int routingReplayMaxSamples,
                             int memoryContextKeepRecentTurns,
                             int memoryContextHistorySummaryMinTurns,
                             double semanticAnalysisRouteMinConfidence,
                             double semanticAnalysisClarifyMinConfidence,
                             boolean behaviorLearningEnabled,
                             int behaviorLearningWindowSize,
                             double behaviorLearningDefaultParamThreshold,
                             boolean semanticAnalysisSkipShortSimpleEnabled,
                             boolean parallelDetectedSkillRoutingEnabled,
                             int parallelDetectedSkillRoutingMaxCandidates,
                             int parallelDetectedSkillRoutingTimeoutMs) {
        this(
                skillEngine,
                skillDslParser,
                paramValidator,
                skillCapabilityPolicy,
                personaCoreService,
                dispatcherMemoryFacade,
                llmClient,
                semanticAnalyzer,
                promptBuilder,
                llmDecisionEngine,
                preferenceReuseEnabled,
                habitRoutingEnabled,
                habitRoutingMinTotalCount,
                habitRoutingMinSuccessRate,
                habitExplainHintEnabled,
                habitContinuationInputMaxLength,
                habitRoutingRecentWindowSize,
                habitRoutingRecentMinSuccessCount,
                habitRoutingRecentMaxAgeHours,
                promptMaxChars,
                memoryContextMaxChars,
                llmReplyMaxChars,
                skillGuardMaxConsecutive,
                skillGuardRecentWindowSize,
                skillGuardRepeatInputThreshold,
                (long) skillGuardCooldownSeconds,
                preExecuteHeavySkillLoopGuardEnabled,
                preExecuteHeavySkillLoopGuardSkills,
                eqCoachImTimeoutMs,
                eqCoachImTimeoutReply,
                promptInjectionGuardEnabled,
                promptInjectionRiskTerms,
                promptInjectionSafeReply,
                llmRoutingShortlistMaxSkills,
                llmDslMemoryContextMaxChars,
                llmRoutingConversationalBypassEnabled,
                realtimeIntentBypassEnabled,
                braveFirstSearchRoutingEnabled,
                realtimeIntentTerms,
                realtimeIntentMemoryShrinkEnabled,
                realtimeIntentMemoryShrinkMaxChars,
                realtimeIntentMemoryShrinkIncludePersona,
                skillPreAnalyzeMode,
                skillPreAnalyzeConfidenceThreshold,
                skillPreAnalyzeSkipSkills,
                llmTuningProperties,
                skillFinalizeWithLlmEnabled,
                skillFinalizeWithLlmSkills,
                skillFinalizeWithLlmMaxOutputChars,
                skillFinalizeWithLlmProvider,
                skillFinalizeWithLlmPreset,
                routingReplayMaxSamples,
                memoryContextKeepRecentTurns,
                memoryContextHistorySummaryMinTurns,
                semanticAnalysisRouteMinConfidence,
                semanticAnalysisClarifyMinConfidence,
                behaviorLearningEnabled,
                behaviorLearningWindowSize,
                behaviorLearningDefaultParamThreshold,
                semanticAnalysisSkipShortSimpleEnabled,
                parallelDetectedSkillRoutingEnabled,
                parallelDetectedSkillRoutingMaxCandidates,
                (long) parallelDetectedSkillRoutingTimeoutMs
        );
    }

    // Backwards-compatible constructor for tests and callers that do not provide
    // the new preferSuggestedSkill configuration parameters. Delegates to the
    // primary constructor with safe defaults (disabled).
    @Autowired
    public DispatcherService(SkillCatalogFacade skillEngine,
                            SkillDslParser skillDslParser,
                            ParamValidator paramValidator,
                             SkillCapabilityPolicy skillCapabilityPolicy,
                                 PersonaCoreService personaCoreService,
                                 DispatcherMemoryFacade dispatcherMemoryFacade,
                                 LlmClient llmClient,
                                 SemanticAnalyzer semanticAnalyzer,
                                PromptBuilder promptBuilder,
                                LLMDecisionEngine llmDecisionEngine,
                                @Value("${mindos.dispatcher.preference-reuse.enabled:false}") boolean preferenceReuseEnabled,
                             @Value("${mindos.dispatcher.habit-routing.enabled:true}") boolean habitRoutingEnabled,
                             @Value("${mindos.dispatcher.habit-routing.min-total-count:2}") int habitRoutingMinTotalCount,
                             @Value("${mindos.dispatcher.habit-routing.min-success-rate:0.6}") double habitRoutingMinSuccessRate,
                             @Value("${mindos.dispatcher.habit-routing.explain-hint-enabled:true}") boolean habitExplainHintEnabled,
                             @Value("${mindos.dispatcher.habit-routing.max-continuation-input-length:16}") int habitContinuationInputMaxLength,
                             @Value("${mindos.dispatcher.habit-routing.recent-window-size:6}") int habitRoutingRecentWindowSize,
                             @Value("${mindos.dispatcher.habit-routing.recent-min-success-count:2}") int habitRoutingRecentMinSuccessCount,
                             @Value("${mindos.dispatcher.habit-routing.recent-success-max-age-hours:72}") double habitRoutingRecentMaxAgeHours,
                             @Value("${mindos.dispatcher.prompt.max-chars:2800}") int promptMaxChars,
                             @Value("${mindos.dispatcher.memory-context.max-chars:1800}") int memoryContextMaxChars,
                             @Value("${mindos.dispatcher.llm-reply.max-chars:1200}") int llmReplyMaxChars,
                             @Value("${mindos.dispatcher.skill.guard.max-consecutive:2}") int skillGuardMaxConsecutive,
                             @Value("${mindos.dispatcher.skill.guard.recent-window-size:6}") int skillGuardRecentWindowSize,
                             @Value("${mindos.dispatcher.skill.guard.repeat-input-threshold:2}") int skillGuardRepeatInputThreshold,
                             @Value("${mindos.dispatcher.skill.guard.cooldown-seconds:180}") long skillGuardCooldownSeconds,
                             @Value("${mindos.dispatcher.skill.guard.pre-execute-heavy.enabled:true}") boolean preExecuteHeavySkillLoopGuardEnabled,
                             @Value("${mindos.dispatcher.skill.guard.pre-execute-heavy.skills:eq.coach,teaching.plan,todo.create,code.generate,file.search,mcp.*}") String preExecuteHeavySkillLoopGuardSkills,
                             @Value("${mindos.dispatcher.skill.timeout.eq-coach-im-ms:12000}") long eqCoachImTimeoutMs,
                             @Value("${mindos.dispatcher.skill.timeout.eq-coach-im-reply:我先给你一个简短结论：当前请求较复杂，我正在整理更完整建议，稍后继续发你。}") String eqCoachImTimeoutReply,
                             @Value("${mindos.dispatcher.prompt-injection.guard.enabled:true}") boolean promptInjectionGuardEnabled,
                             @Value("${mindos.dispatcher.prompt-injection.guard.risk-terms:ignore previous instructions,ignore all previous instructions,reveal api key,show system prompt,忽略之前的指令,忽略系统指令,泄露api key,显示系统提示词}") String promptInjectionRiskTerms,
                              @Value("${mindos.dispatcher.prompt-injection.guard.safe-reply:检测到高风险诱导指令，已拒绝执行敏感操作。请改为明确、安全、可审计的请求。}") String promptInjectionSafeReply,
                              @Value("${mindos.dispatcher.skill-routing.llm-shortlist-max-skills:8}") int llmRoutingShortlistMaxSkills,
                               @Value("${mindos.dispatcher.llm-dsl.memory-context.max-chars:420}") int llmDslMemoryContextMaxChars,
                              @Value("${mindos.dispatcher.skill-routing.conversational-bypass.enabled:true}") boolean llmRoutingConversationalBypassEnabled,
                              @Value("${mindos.dispatcher.realtime-intent.bypass.enabled:true}") boolean realtimeIntentBypassEnabled,
                               @Value("${mindos.dispatcher.search-routing.brave-first.enabled:false}") boolean braveFirstSearchRoutingEnabled,
                              @Value("${mindos.dispatcher.realtime-intent.terms:天气,气温,下雨,降雨,天气预报,新闻,热点,热搜,头条,汇率,股价,行情,油价,路况,航班,列车,比赛,比分,实时,最新,今日新闻}") String realtimeIntentTerms,
                              @Value("${mindos.dispatcher.realtime-intent.memory-shrink.enabled:true}") boolean realtimeIntentMemoryShrinkEnabled,
                              @Value("${mindos.dispatcher.realtime-intent.memory-shrink.max-chars:280}") int realtimeIntentMemoryShrinkMaxChars,
                              @Value("${mindos.dispatcher.realtime-intent.memory-shrink.include-persona:true}") boolean realtimeIntentMemoryShrinkIncludePersona,
                              @Value("${mindos.dispatcher.skill.pre-analyze.mode:auto}") String skillPreAnalyzeMode,
                              @Value("${mindos.dispatcher.skill.pre-analyze.confidence-threshold:0}") int skillPreAnalyzeConfidenceThreshold,
                              @Value("${mindos.dispatcher.skill.pre-analyze.skip-skills:time}") String skillPreAnalyzeSkipSkills,
                              DispatcherLlmTuningProperties llmTuningProperties,
                              @Value("${mindos.dispatcher.skill.finalize-with-llm.enabled:false}") boolean skillFinalizeWithLlmEnabled,
                              @Value("${mindos.dispatcher.skill.finalize-with-llm.skills:teaching.plan,todo.create,eq.coach,code.generate,file.search,mcp.*}") String skillFinalizeWithLlmSkills,
                              @Value("${mindos.dispatcher.skill.finalize-with-llm.max-output-chars:900}") int skillFinalizeWithLlmMaxOutputChars,
                              @Value("${mindos.dispatcher.skill.finalize-with-llm.provider:}") String skillFinalizeWithLlmProvider,
                               @Value("${mindos.dispatcher.skill.finalize-with-llm.preset:}") String skillFinalizeWithLlmPreset,
                               @Value("${mindos.dispatcher.routing-replay.max-samples:200}") int routingReplayMaxSamples,
                               @Value("${mindos.dispatcher.memory-context.keep-recent-turns:2}") int memoryContextKeepRecentTurns,
                               @Value("${mindos.dispatcher.memory-context.history-summary-min-turns:4}") int memoryContextHistorySummaryMinTurns,
                               @Value("${mindos.dispatcher.semantic-analysis.route-min-confidence:0.72}") double semanticAnalysisRouteMinConfidence,
                               @Value("${mindos.dispatcher.semantic-analysis.clarify-min-confidence:0.70}") double semanticAnalysisClarifyMinConfidence,
                               @Value("${mindos.dispatcher.behavior-learning.enabled:true}") boolean behaviorLearningEnabled,
                               @Value("${mindos.dispatcher.behavior-learning.window-size:50}") int behaviorLearningWindowSize,
                               @Value("${mindos.dispatcher.behavior-learning.default-param-threshold:0.6}") double behaviorLearningDefaultParamThreshold,
                                @Value("${mindos.dispatcher.semantic-analysis.skip-short-simple.enabled:false}") boolean semanticAnalysisSkipShortSimpleEnabled,
                                 @Value("${mindos.dispatcher.parallel-routing.enabled:false}") boolean parallelDetectedSkillRoutingEnabled,
                                 @Value("${mindos.dispatcher.parallel-routing.max-candidates:2}") int parallelDetectedSkillRoutingMaxCandidates,
                                 @Value("${mindos.dispatcher.parallel-routing.per-skill-timeout-ms:2500}") long parallelDetectedSkillRoutingTimeoutMs) {
        this.skillEngine = skillEngine;
        this.skillDslParser = skillDslParser;
        this.skillCapabilityPolicy = skillCapabilityPolicy;
        this.personaCoreService = personaCoreService;
        this.dispatcherMemoryFacade = Objects.requireNonNull(dispatcherMemoryFacade, "dispatcherMemoryFacade");
        this.memoryCommandService = new DispatcherMemoryCommandService(this.dispatcherMemoryFacade, null);
        this.semanticAnalyzer = semanticAnalyzer;
        this.llmDecisionEngine = llmDecisionEngine;
        this.paramValidator = paramValidator;
        this.preferenceReuseEnabled = preferenceReuseEnabled;
        this.habitRoutingEnabled = habitRoutingEnabled;
        this.habitRoutingMinTotalCount = Math.max(1, habitRoutingMinTotalCount);
        this.habitRoutingMinSuccessRate = Math.max(0.0, Math.min(1.0, habitRoutingMinSuccessRate));
        this.habitExplainHintEnabled = habitExplainHintEnabled;
        this.habitContinuationInputMaxLength = Math.max(4, habitContinuationInputMaxLength);
        this.habitRoutingRecentWindowSize = Math.max(3, habitRoutingRecentWindowSize);
        this.habitRoutingRecentMinSuccessCount = Math.max(1, habitRoutingRecentMinSuccessCount);
        this.habitRoutingRecentMaxAgeHours = Math.max(1.0, habitRoutingRecentMaxAgeHours);
        this.promptMaxChars = Math.max(600, promptMaxChars);
        this.memoryContextMaxChars = Math.max(400, memoryContextMaxChars);
        this.llmReplyMaxChars = Math.max(200, llmReplyMaxChars);
        this.skillGuardMaxConsecutive = Math.max(1, skillGuardMaxConsecutive);
        this.skillGuardRecentWindowSize = Math.max(2, skillGuardRecentWindowSize);
        this.skillGuardRepeatInputThreshold = Math.max(2, skillGuardRepeatInputThreshold);
        this.skillGuardCooldownSeconds = Math.max(0L, skillGuardCooldownSeconds);
        this.preExecuteHeavySkillLoopGuardEnabled = preExecuteHeavySkillLoopGuardEnabled;
        this.preExecuteHeavySkillLoopGuardSkills = parseCsvSet(preExecuteHeavySkillLoopGuardSkills);
        this.eqCoachImTimeoutMs = Math.max(0L, eqCoachImTimeoutMs);
        this.eqCoachImTimeoutReply = eqCoachImTimeoutReply == null || eqCoachImTimeoutReply.isBlank()
                ? "我先给你一个简短结论：当前请求较复杂，我正在整理更完整建议，稍后继续发你。"
                : eqCoachImTimeoutReply;
        this.promptInjectionGuardEnabled = promptInjectionGuardEnabled;
        this.promptInjectionRiskTerms = DispatchHeuristicsSupport.parseRiskTerms(promptInjectionRiskTerms);
        this.promptInjectionSafeReply = promptInjectionSafeReply == null || promptInjectionSafeReply.isBlank()
                ? "检测到高风险诱导指令，已拒绝执行敏感操作。请改为明确、安全、可审计的请求。"
                : promptInjectionSafeReply;
        this.llmRoutingShortlistMaxSkills = Math.max(1, llmRoutingShortlistMaxSkills);
        this.llmDslMemoryContextMaxChars = Math.max(160, llmDslMemoryContextMaxChars);
        this.llmRoutingConversationalBypassEnabled = llmRoutingConversationalBypassEnabled;
        this.realtimeIntentBypassEnabled = realtimeIntentBypassEnabled;
        this.braveFirstSearchRoutingEnabled = braveFirstSearchRoutingEnabled;
        this.realtimeIntentTerms = parseCsvSet(realtimeIntentTerms);
        this.realtimeIntentMemoryShrinkEnabled = realtimeIntentMemoryShrinkEnabled;
        this.realtimeIntentMemoryShrinkMaxChars = Math.max(120, realtimeIntentMemoryShrinkMaxChars);
        this.realtimeIntentMemoryShrinkIncludePersona = realtimeIntentMemoryShrinkIncludePersona;
        this.skillPreAnalyzeMode = normalizeSkillPreAnalyzeMode(skillPreAnalyzeMode);
        this.skillPreAnalyzeConfidenceThreshold = Math.max(0, skillPreAnalyzeConfidenceThreshold);
        this.skillPreAnalyzeSkipSkills = parseCsvSet(skillPreAnalyzeSkipSkills);
        DispatcherLlmTuningProperties effectiveLlmTuning = llmTuningProperties == null ? new DispatcherLlmTuningProperties() : llmTuningProperties;
        this.answerMode = DispatcherAnswerMode.from(effectiveLlmTuning.getAnswerMode());
        this.conversationMemoryModeService = new ConversationMemoryModeService();
        this.llmDslProvider = normalizeOptionalConfig(effectiveLlmTuning.getLlmDsl().getProvider());
        this.llmDslPreset = normalizeOptionalConfig(effectiveLlmTuning.getLlmDsl().getPreset());
        this.llmDslModel = normalizeOptionalConfig(effectiveLlmTuning.getLlmDsl().getModel());
        this.llmFallbackProvider = normalizeOptionalConfig(effectiveLlmTuning.getLlmFallback().getProvider());
        this.llmFallbackPreset = normalizeOptionalConfig(effectiveLlmTuning.getLlmFallback().getPreset());
        this.llmFallbackModel = normalizeOptionalConfig(effectiveLlmTuning.getLlmFallback().getModel());
        this.localEscalationEnabled = effectiveLlmTuning.getLocalEscalation().isEnabled();
        this.localEscalationCloudProvider = normalizeOptionalConfig(effectiveLlmTuning.getLocalEscalation().getCloudProvider());
        this.localEscalationCloudPreset = normalizeOptionalConfig(effectiveLlmTuning.getLocalEscalation().getCloudPreset());
        this.localEscalationCloudModel = normalizeOptionalConfig(effectiveLlmTuning.getLocalEscalation().getCloudModel());
        this.llmDslMaxTokens = Math.max(0, effectiveLlmTuning.getLlmDsl().getMaxTokens());
        this.llmFallbackMaxTokens = Math.max(0, effectiveLlmTuning.getLlmFallback().getMaxTokens());
        this.skillFinalizeMaxTokens = Math.max(0, effectiveLlmTuning.getSkillFinalizeWithLlm().getMaxTokens());
        this.localEscalationQualityEnabled = effectiveLlmTuning.getLocalEscalation().getQuality().isEnabled();
        this.localEscalationQualityMaxReplyChars = Math.max(8, effectiveLlmTuning.getLocalEscalation().getQuality().getMaxReplyChars());
        this.localEscalationQualityInputTerms = parseCsvSet(effectiveLlmTuning.getLocalEscalation().getQuality().getInputTerms());
        this.localEscalationQualityReplyTerms = parseCsvSet(effectiveLlmTuning.getLocalEscalation().getQuality().getReplyTerms());
        this.skillFinalizeWithLlmEnabled = skillFinalizeWithLlmEnabled;
        this.skillFinalizeWithLlmSkills = parseCsvSet(skillFinalizeWithLlmSkills);
        this.skillFinalizeWithLlmMaxOutputChars = Math.max(200, skillFinalizeWithLlmMaxOutputChars);
        this.skillFinalizeWithLlmProvider = normalizeOptionalConfig(skillFinalizeWithLlmProvider);
        this.skillFinalizeWithLlmPreset = normalizeOptionalConfig(skillFinalizeWithLlmPreset);
        this.skillFinalizeWithLlmModel = normalizeOptionalConfig(effectiveLlmTuning.getSkillFinalizeWithLlm().getModel());
        this.localEscalationResourceGuardEnabled = effectiveLlmTuning.getLocalEscalation().getResourceGuard().isEnabled();
        this.localEscalationResourceGuardMinFreeMemoryMb = Math.max(64, effectiveLlmTuning.getLocalEscalation().getResourceGuard().getMinFreeMemoryMb());
        this.localEscalationResourceGuardMinFreeMemoryRatio = Math.max(0.0, Math.min(1.0, effectiveLlmTuning.getLocalEscalation().getResourceGuard().getMinFreeMemoryRatio()));
        this.localEscalationResourceGuardMinAvailableProcessors = Math.max(1, effectiveLlmTuning.getLocalEscalation().getResourceGuard().getMinAvailableProcessors());
        this.routingReplayMaxSamples = Math.max(10, routingReplayMaxSamples);
        this.memoryContextKeepRecentTurns = Math.max(1, memoryContextKeepRecentTurns);
        this.memoryContextHistorySummaryMinTurns = Math.max(2, memoryContextHistorySummaryMinTurns);
        this.semanticAnalysisRouteMinConfidence = Math.max(0.0, Math.min(1.0, semanticAnalysisRouteMinConfidence));
        this.semanticAnalysisClarifyMinConfidence = Math.max(0.0, Math.min(1.0, semanticAnalysisClarifyMinConfidence));
        this.behaviorLearningEnabled = behaviorLearningEnabled;
        this.behaviorLearningWindowSize = Math.max(10, behaviorLearningWindowSize);
        this.behaviorLearningDefaultParamThreshold = Math.max(0.5, Math.min(0.95, behaviorLearningDefaultParamThreshold));
        this.semanticAnalysisSkipShortSimpleEnabled = semanticAnalysisSkipShortSimpleEnabled;
        this.preferSuggestedSkillEnabled = false;
        this.preferSuggestedSkillMinConfidence = 0.0;
        this.parallelDetectedSkillRoutingEnabled = parallelDetectedSkillRoutingEnabled;
        this.parallelDetectedSkillRoutingMaxCandidates = Math.max(1, parallelDetectedSkillRoutingMaxCandidates);
        this.parallelDetectedSkillRoutingTimeoutMs = Math.max(100L, parallelDetectedSkillRoutingTimeoutMs);
        this.skillCommandAssembler = new SkillCommandAssembler(this.skillDslParser, this.preferenceReuseEnabled);
        this.decisionParamAssembler = new DecisionParamAssembler(this.skillCommandAssembler);
        this.behaviorRoutingSupport = new BehaviorRoutingSupport(
                this.skillDslParser,
                this.dispatcherMemoryFacade,
                this.skillCommandAssembler,
                this.memoryCommandService,
                this.habitRoutingEnabled,
                this.habitRoutingMinTotalCount,
                this.habitRoutingMinSuccessRate,
                this.habitContinuationInputMaxLength,
                this.habitRoutingRecentWindowSize,
                this.habitRoutingRecentMinSuccessCount,
                this.habitRoutingRecentMaxAgeHours,
                this.behaviorLearningEnabled,
                this.behaviorLearningWindowSize,
                this.behaviorLearningDefaultParamThreshold
        );
        this.semanticRoutingSupport = new SemanticRoutingSupport(
                this.dispatcherMemoryFacade,
                this.memoryCommandService,
                this.behaviorRoutingSupport,
                this.skillCommandAssembler,
                this.paramValidator,
                this::isKnownSkillName,
                this::inferMemoryBucket,
                this.semanticAnalysisRouteMinConfidence,
                this.semanticAnalysisClarifyMinConfidence,
                this.preferSuggestedSkillEnabled,
                this.preferSuggestedSkillMinConfidence
        );
        this.dispatchMemoryLifecycle = new DispatchMemoryLifecycle(
                this.dispatcherMemoryFacade,
                this.memoryCommandService,
                this.behaviorRoutingSupport,
                this::inferMemoryBucket
        );
        this.dispatchHeuristicsSupport = new DispatchHeuristicsSupport(
                this.dispatchMemoryLifecycle,
                this.promptInjectionGuardEnabled,
                this.promptInjectionRiskTerms,
                this.semanticAnalysisSkipShortSimpleEnabled,
                this.realtimeIntentBypassEnabled,
                this.realtimeIntentTerms
        );
        this.dispatchLlmSupport = new DispatchLlmSupport(
                llmClient,
                promptBuilder,
                this.dispatchHeuristicsSupport,
                new DispatchLlmSupport.PromptConfig(
                        this.promptMaxChars,
                        this.llmReplyMaxChars,
                        this.llmDslMemoryContextMaxChars,
                        this.realtimeIntentMemoryShrinkEnabled,
                        this.realtimeIntentMemoryShrinkMaxChars,
                        this.realtimeIntentMemoryShrinkIncludePersona
                ),
                new DispatchLlmSupport.StageRouteConfig(
                        this.llmDslProvider,
                        this.llmDslPreset,
                        this.llmDslModel,
                        this.llmDslMaxTokens,
                        this.llmFallbackProvider,
                        this.llmFallbackPreset,
                        this.llmFallbackModel,
                        this.llmFallbackMaxTokens,
                        this.skillFinalizeWithLlmProvider,
                        this.skillFinalizeWithLlmPreset,
                        this.skillFinalizeWithLlmModel,
                        this.skillFinalizeMaxTokens
                ),
                new DispatchLlmSupport.SkillFinalizeConfig(
                        this.skillFinalizeWithLlmEnabled,
                        this.skillFinalizeWithLlmSkills,
                        this.skillFinalizeWithLlmMaxOutputChars
                ),
                new DispatchLlmSupport.EscalationConfig(
                        this.localEscalationEnabled,
                        this.localEscalationCloudProvider,
                        this.localEscalationCloudPreset,
                        this.localEscalationCloudModel,
                        this.localEscalationQualityEnabled,
                        this.localEscalationQualityMaxReplyChars,
                        this.localEscalationQualityInputTerms,
                        this.localEscalationQualityReplyTerms,
                        this.localEscalationResourceGuardEnabled,
                        this.localEscalationResourceGuardMinFreeMemoryMb,
                        this.localEscalationResourceGuardMinFreeMemoryRatio,
                        this.localEscalationResourceGuardMinAvailableProcessors
                ),
                new DispatchLlmSupport.Metrics(
                        this.localEscalationAttemptCount,
                        this.localEscalationHitCount,
                        this.fallbackChainAttemptCount,
                        this.fallbackChainHitCount,
                        this.escalationReasonCounters
                )
        );
    }

    @Autowired(required = false)
    void setParamSchemaRegistry(ParamSchemaRegistry paramSchemaRegistry) {
        this.paramSchemaRegistry = paramSchemaRegistry;
    }

    @Autowired(required = false)
    void setSkillExecutionGateway(SkillExecutionGateway skillExecutionGateway) {
        this.skillExecutionGateway = skillExecutionGateway;
    }

    @Autowired(required = false)
    void setDispatchSkillDslResolvers(List<DispatchSkillDslResolver> dispatchSkillDslResolvers) {
        this.dispatchSkillDslResolvers = dispatchSkillDslResolvers == null ? List.of() : List.copyOf(dispatchSkillDslResolvers);
    }

    private DispatcherMemoryFacade activeDispatcherMemoryFacade() {
        return dispatcherMemoryFacade;
    }

    private String inferMemoryBucket(String input) {
        String normalized = normalize(input);
        if (normalized.isBlank()) {
            return "general";
        }
        if (containsAny(normalized,
                "学习计划", "教学规划", "复习计划", "备考", "课程", "学科", "数学", "英语", "物理", "化学")) {
            return "learning";
        }
        if (containsAny(normalized,
                "情商", "沟通", "同事", "关系", "冲突", "安抚", "eq", "coach")) {
            return "eq";
        }
        if (containsAny(normalized,
                "待办", "todo", "截止", "任务", "清单", "优先级", "计划")) {
            return "task";
        }
        if (containsAny(normalized,
                "代码", "编译", "java", "spring", "bug", "接口", "mcp", "sdk")) {
            return "coding";
        }
        return "general";
    }

    private boolean containsAny(String value, String... phrases) {
        if (value == null || value.isBlank() || phrases == null) {
            return false;
        }
        for (String phrase : phrases) {
            if (phrase != null && !phrase.isBlank() && value.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private HermesAssistantRuntime hermesAssistantRuntime() {
        HermesAssistantRuntime runtime = this.hermesAssistantRuntime;
        if (runtime != null) {
            return runtime;
        }
        synchronized (this) {
            if (this.hermesAssistantRuntime == null) {
                ParamSchemaRegistry effectiveSchemaRegistry = this.paramSchemaRegistry;
                if (effectiveSchemaRegistry == null) {
                    InMemoryParamSchemaRegistry fallbackRegistry = new InMemoryParamSchemaRegistry();
                    fallbackRegistry.registerDefaults();
                    effectiveSchemaRegistry = fallbackRegistry;
                }
                HermesToolSchemaCatalog toolSchemaCatalog = new HermesToolSchemaCatalog(this.skillEngine, effectiveSchemaRegistry);
                this.hermesAssistantRuntime = new HermesAssistantRuntime(
                        this.dispatchHeuristicsSupport,
                        new HermesDecisionContextFactory(
                                this.dispatcherMemoryFacade,
                                this.personaCoreService,
                                this.semanticAnalyzer,
                                toolSchemaCatalog,
                                this.dispatchHeuristicsSupport,
                                this.answerMode,
                                this.promptMaxChars,
                                this.memoryContextMaxChars,
                                this.realtimeIntentMemoryShrinkEnabled,
                                this.realtimeIntentMemoryShrinkMaxChars,
                                stats -> this.recordContextCompressionMetrics(
                                        stats.rawChars(),
                                        stats.finalChars(),
                                        stats.compressed(),
                                        stats.summarizedTurns()
                                )
                        ),
                        new HermesDecisionEngine(
                                this.skillDslParser,
                                this.skillEngine,
                                toolSchemaCatalog,
                                this.semanticRoutingSupport,
                                this.behaviorRoutingSupport,
                                this.decisionParamAssembler,
                                this.llmDecisionEngine,
                                this.dispatchHeuristicsSupport,
                                this.answerMode,
                                this.conversationMemoryModeService,
                                resolveHermesSearchPriorityOrder(),
                                this.dispatchSkillDslResolvers
                        ),
                        this.paramValidator,
                        new HermesSkillRouter(
                                this.skillExecutionGateway,
                                toolSchemaCatalog,
                                new DevelopmentWorkflowService(this.skillExecutionGateway)
                        ),
                        new HermesMemoryRecorder(
                                this.dispatcherMemoryFacade,
                                this.memoryCommandService,
                                this.dispatchMemoryLifecycle,
                                this.semanticRoutingSupport
                        ),
                        this.dispatchLlmSupport,
                        this.dispatcherMemoryFacade,
                        new HermesExecutionGuard(
                                this.dispatcherMemoryFacade,
                                this.skillCapabilityPolicy,
                                this.behaviorRoutingSupport,
                                this.skillGuardMaxConsecutive,
                                this.skillGuardRecentWindowSize,
                                this.skillGuardRepeatInputThreshold,
                                this.skillGuardCooldownSeconds,
                                this.habitExplainHintEnabled,
                                this.preferenceReuseEnabled
                        ),
                        this.promptInjectionSafeReply,
                        this.conversationMemoryModeService
                );
            }
            return this.hermesAssistantRuntime;
        }
    }

    public DispatchResult dispatch(String userId, String userInput) {
        return dispatch(userId, userInput, Map.of());
    }

    public DispatchResult dispatch(String userId, String userInput, Map<String, Object> profileContext) {
        Instant startedAt = Instant.now();
        Map<String, Object> effectiveProfileContext = enrichHermesProfileContext(profileContext);
        try {
            DispatchResult result = hermesAssistantRuntime().dispatch(userId, userInput, effectiveProfileContext);
            logHermesDispatchCompletion(userId, result, false, startedAt, null);
            return result;
        } catch (RuntimeException ex) {
            logHermesDispatchCompletion(userId, null, false, startedAt, ex);
            throw ex;
        }
    }

    public CompletableFuture<DispatchResult> dispatchAsync(String userId, String userInput) {
        return dispatchAsync(userId, userInput, Map.of());
    }

    public CompletableFuture<DispatchResult> dispatchAsync(String userId, String userInput, Map<String, Object> profileContext) {
        Instant startedAt = Instant.now();
        Map<String, Object> effectiveProfileContext = enrichHermesProfileContext(profileContext);
        return hermesAssistantRuntime().dispatchAsync(userId, userInput, effectiveProfileContext)
                .whenComplete((result, error) -> logHermesDispatchCompletion(userId, result, false, startedAt, error));
    }

    public CompletableFuture<DispatchResult> dispatchStream(String userId,
                                                            String userInput,
                                                            Map<String, Object> profileContext,
                                                            Consumer<String> deltaConsumer) {
        Instant startedAt = Instant.now();
        Map<String, Object> effectiveProfileContext = enrichHermesProfileContext(profileContext);
        return hermesAssistantRuntime().dispatchStream(userId, userInput, effectiveProfileContext, deltaConsumer)
                .whenComplete((result, error) -> logHermesDispatchCompletion(userId, result, true, startedAt, error));
    }

    private void logDispatchCompletion(String userId,
                                       DispatchResult result,
                                       DispatchExecutionState executionState,
                                       boolean streamMode,
                                       Instant startTime,
                                       Throwable error) {
        long durationMs = Duration.between(startTime, Instant.now()).toMillis();
        String dispatcherLabel = streamMode ? "Dispatcher(stream)" : "Dispatcher";
        if (error != null) {
            LOGGER.log(Level.SEVERE,
                    dispatcherLabel + " error: userId=" + userId + ", durationMs=" + durationMs,
                    error);
            return;
        }
        LOGGER.info(dispatcherLabel + " output: userId=" + userId
                + ", channel=" + result.channel()
                + ", output=" + clip(result.reply())
                + ", durationMs=" + durationMs);
        logFinalAggregateTrace(
                userId,
                observedFinalTraceChannel(result),
                result.executionTrace(),
                executionState.skillPostprocessSent(),
                executionState.finalResultSuccess(),
                executionState.realtimeLookup(),
                executionState.memoryDirectBypassed()
        );
    }

    private void logHermesDispatchCompletion(String userId,
                                             DispatchResult result,
                                             boolean streamMode,
                                             Instant startTime,
                                             Throwable error) {
        if (error != null) {
            long durationMs = Duration.between(startTime, Instant.now()).toMillis();
            String dispatcherLabel = streamMode ? "Dispatcher(stream)" : "Dispatcher";
            LOGGER.log(Level.SEVERE,
                    dispatcherLabel + " error: userId=" + userId + ", durationMs=" + durationMs,
                    error);
            return;
        }
        if (result == null) {
            return;
        }
        DispatchExecutionState state = new DispatchExecutionState();
        state.setFinalResultSuccess(isSuccessfulFinalResult(result));
        state.setSkillPostprocessSent(isHermesSkillPostprocessSent(result));
        state.setRealtimeLookup(extractTraceBoolean(result, "realtimeLookup"));
        state.setMemoryDirectBypassed(extractTraceBoolean(result, "memoryDirectBypassed"));
        logDispatchCompletion(userId, result, state, streamMode, startTime, null);
    }

    private void logFinalAggregateTrace(String userId,
                                        String finalChannel,
                                        ExecutionTraceDto trace,
                                        boolean skillPostprocessSent,
                                        boolean finalResultSuccess,
                                        boolean realtimeLookup,
                                        boolean memoryDirectBypassed) {
        RoutingDecisionDto routing = trace == null ? null : trace.routing();
        String selectedSkill = routing == null ? "" : normalizeOptional(routing.selectedSkill());
        String tracedActualSearchSource = extractTraceValue(trace, "actualSearchSource");
        String searchSource = dispatchLlmSupport.classifyMcpSearchSource(!selectedSkill.isBlank() ? selectedSkill : finalChannel);
        if (searchSource.isBlank()) {
            searchSource = tracedActualSearchSource;
        }
        String actualSearchSource = tracedActualSearchSource.isBlank() ? searchSource : tracedActualSearchSource;
        boolean builtinNewsSearch = "news_search".equalsIgnoreCase(firstNonBlank(selectedSkill, finalChannel));
        boolean searchAttempted = builtinNewsSearch || !actualSearchSource.isBlank();
        String searchStatus = resolveSearchStatus(searchAttempted, selectedSkill, finalChannel, trace, finalResultSuccess);
        boolean fallbackUsed = trace != null && trace.replanCount() > 0;
        String loggedSearchSource = searchSource;
        String loggedActualSearchSource = actualSearchSource;
        boolean loggedSearchAttempted = searchAttempted;
        String loggedSearchStatus = searchStatus;
        String loggedSelectedSkill = selectedSkill;
        boolean loggedFallbackUsed = fallbackUsed;
        LOGGER.info(() -> "{\"event\":\"dispatcher.final.trace\",\"userId\":\""
                + (userId == null ? "" : userId)
                + "\",\"searchSource\":\""
                + loggedSearchSource
                + "\",\"actualSearchSource\":\""
                + loggedActualSearchSource
                + "\",\"searchAttempted\":"
                + loggedSearchAttempted
                + ",\"searchStatus\":\""
                + loggedSearchStatus
                + "\",\"selectedSkill\":\""
                + loggedSelectedSkill
                + "\",\"postprocessSent\":"
                + skillPostprocessSent
                + ",\"realtimeLookup\":"
                + realtimeLookup
                + ",\"memoryDirectBypassed\":"
                + memoryDirectBypassed
                + ",\"fallbackUsed\":"
                + loggedFallbackUsed
                + ",\"finalChannel\":\""
                + normalizeOptional(finalChannel)
                + "\"}");
    }

    private String resolveSearchStatus(boolean searchAttempted,
                                       String selectedSkill,
                                       String finalChannel,
                                       ExecutionTraceDto trace,
                                       boolean finalResultSuccess) {
        if (!searchAttempted) {
            return "not-applicable";
        }
        if (finalResultSuccess
                && !selectedSkill.isBlank()
                && selectedSkill.equalsIgnoreCase(normalizeOptional(finalChannel))) {
            return "success";
        }
        if (!finalResultSuccess) {
            return "failed";
        }
        if (trace != null && trace.steps() != null && !trace.steps().isEmpty()) {
            var primary = trace.steps().get(0);
            if (primary != null && "primary".equalsIgnoreCase(normalizeOptional(primary.stepName()))) {
                if ("success".equalsIgnoreCase(normalizeOptional(primary.status()))) {
                    return "success";
                }
                if ("failed".equalsIgnoreCase(normalizeOptional(primary.status()))) {
                    return "failed";
                }
            }
        }
        if ("llm".equalsIgnoreCase(normalizeOptional(finalChannel))) {
            return "failed";
        }
        return "unknown";
    }

    private String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isSuccessfulFinalResult(DispatchResult result) {
        if (result == null) {
            return false;
        }
        if (result.executionTrace() != null
                && result.executionTrace().steps() != null
                && !result.executionTrace().steps().isEmpty()) {
            var primary = result.executionTrace().steps().get(0);
            if (primary != null
                    && "primary".equalsIgnoreCase(normalizeOptional(primary.stepName()))
                    && "failed".equalsIgnoreCase(normalizeOptional(primary.status()))) {
                return false;
            }
        }
        if (result.executionTrace() == null || result.executionTrace().critique() == null) {
            return result != null;
        }
        return result.executionTrace().critique().success();
    }

    private boolean isHermesSkillPostprocessSent(DispatchResult result) {
        if (result == null) {
            return false;
        }
        String observed = extractTraceValue(result.executionTrace(), "skillPostprocessSent");
        if (!observed.isBlank()) {
            return Boolean.parseBoolean(observed);
        }
        if (!skillFinalizeWithLlmEnabled) {
            return false;
        }
        String selected = result.executionTrace() == null || result.executionTrace().routing() == null
                ? null
                : result.executionTrace().routing().selectedSkill();
        String effectiveSkill = firstNonBlank(selected, result.channel());
        return matchesConfiguredSkill(effectiveSkill, skillFinalizeWithLlmSkills);
    }

    private boolean extractTraceBoolean(DispatchResult result, String key) {
        String observed = extractTraceValue(result == null ? null : result.executionTrace(), key);
        return !observed.isBlank() && Boolean.parseBoolean(observed);
    }

    private String extractTraceValue(ExecutionTraceDto trace, String key) {
        if (trace == null
                || trace.routing() == null
                || trace.routing().reasons() == null) {
            return "";
        }
        String prefix = key + "=";
        return trace.routing().reasons().stream()
                .filter(reason -> reason != null && reason.startsWith(prefix))
                .map(reason -> reason.substring(prefix.length()))
                .findFirst()
                .orElse("");
    }

    private String observedFinalTraceChannel(DispatchResult result) {
        if (result == null) {
            return "";
        }
        String channel = normalizeOptional(result.channel());
        if (!"llm".equalsIgnoreCase(channel)
                || result.executionTrace() == null
                || result.executionTrace().routing() == null
                || result.executionTrace().steps() == null
                || result.executionTrace().steps().isEmpty()) {
            return channel;
        }
        String selectedSkill = normalizeOptional(result.executionTrace().routing().selectedSkill());
        var primary = result.executionTrace().steps().get(0);
        if (!selectedSkill.isBlank()
                && selectedSkill.startsWith("mcp.")
                && primary != null
                && "primary".equalsIgnoreCase(normalizeOptional(primary.stepName()))
                && "failed".equalsIgnoreCase(normalizeOptional(primary.status()))) {
            return selectedSkill;
        }
        return channel;
    }

    public void beginDrain() {
        hermesAssistantRuntime().beginDrain();
    }

    public void resumeAcceptingRequests() {
        hermesAssistantRuntime().resumeAcceptingRequests();
    }

    public boolean isAcceptingRequests() {
        return hermesAssistantRuntime().isAcceptingRequests();
    }

    public long getActiveDispatchCount() {
        return hermesAssistantRuntime().getActiveDispatchCount();
    }

    public boolean waitForActiveDispatches(long timeoutMs) {
        return hermesAssistantRuntime().waitForActiveDispatches(timeoutMs);
    }

    private DispatchResult buildDrainingResult(String userInput) {
        String reply = "系统正在升级维护，请稍后重试。";
        RoutingDecisionDto decision = new RoutingDecisionDto(
                "system.draining",
                "system.draining",
                1.0,
                List.of("dispatcher is currently draining and rejecting new requests"),
                List.of(normalizeOptional(userInput))
        );
        ExecutionTraceDto trace = new ExecutionTraceDto("single-pass", 0, null, List.of(), decision);
        return new DispatchResult(reply, "system.draining", trace);
    }

    private CompletableFuture<SkillResult> applySkillTimeoutIfNeeded(String skillName,
                                                                      SkillContext context,
                                                                      CompletableFuture<SkillResult> executionFuture) {
        if (executionFuture == null
                || eqCoachImTimeoutMs <= 0L
                || !"eq.coach".equals(skillName)
                || context == null
                || !isImInteractionContext(context.attributes())) {
            return executionFuture;
        }
        SkillResult timeoutFallback = SkillResult.success(skillName, eqCoachImTimeoutReply);
        CompletableFuture<SkillResult> timeoutFuture = CompletableFuture.supplyAsync(
                () -> timeoutFallback,
                CompletableFuture.delayedExecutor(eqCoachImTimeoutMs, TimeUnit.MILLISECONDS)
        );
        return executionFuture.applyToEither(timeoutFuture, result -> {
            if (result == timeoutFallback) {
                skillTimeoutTriggeredCount.incrementAndGet();
            }
            return result;
        });
    }

    private CompletableFuture<Optional<SkillResult>> applySkillTimeoutForOptionalResult(String skillName,
                                                                                         SkillContext context,
                                                                                         CompletableFuture<Optional<SkillResult>> executionFuture) {
        if (executionFuture == null
                || eqCoachImTimeoutMs <= 0L
                || !"eq.coach".equals(skillName)
                || context == null
                || !isImInteractionContext(context.attributes())) {
            return executionFuture;
        }
        Optional<SkillResult> timeoutFallback = Optional.of(SkillResult.success(skillName, eqCoachImTimeoutReply));
        CompletableFuture<Optional<SkillResult>> timeoutFuture = CompletableFuture.supplyAsync(
                () -> timeoutFallback,
                CompletableFuture.delayedExecutor(eqCoachImTimeoutMs, TimeUnit.MILLISECONDS)
        );
        return executionFuture.applyToEither(timeoutFuture, result -> {
            if (result == timeoutFallback) {
                skillTimeoutTriggeredCount.incrementAndGet();
            }
            return result;
        });
    }

    private boolean isImInteractionContext(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return false;
        }
        if (attributes.containsKey("imPlatform") || attributes.containsKey("imSenderId") || attributes.containsKey("imChatId")) {
            return true;
        }
        Object channel = attributes.get("interactionChannel");
        return channel != null && "im".equalsIgnoreCase(String.valueOf(channel));
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String safeText(String value, String fallback) {
        String normalized = asString(value);
        return normalized == null ? fallback : normalized;
    }

    private void copyInteractionContext(Map<String, Object> profileContext, Map<String, Object> llmContext) {
        if (profileContext == null || profileContext.isEmpty() || llmContext == null) {
            return;
        }
        String imPlatform = asString(profileContext.get("imPlatform"));
        if (imPlatform == null) {
            return;
        }
        llmContext.put("interactionChannel", "im");
        llmContext.put("imPlatform", imPlatform);
        String imSenderId = asString(profileContext.get("imSenderId"));
        if (imSenderId != null) {
            llmContext.put("imSenderId", imSenderId);
        }
        String imChatId = asString(profileContext.get("imChatId"));
        if (imChatId != null) {
            llmContext.put("imChatId", imChatId);
        }
    }

    private void copyEscalationHints(Map<String, Object> profileContext, Map<String, Object> llmContext) {
        if (profileContext == null || profileContext.isEmpty() || llmContext == null) {
            return;
        }
        if (profileContext.containsKey("localEscalationReason")) {
            llmContext.put("localEscalationReason", profileContext.get("localEscalationReason"));
        }
        if (profileContext.containsKey("forceCloudRetry")) {
            llmContext.put("forceCloudRetry", profileContext.get("forceCloudRetry"));
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private String normalizeOptionalConfig(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void recordRoutingReplaySample(String userInput,
                                           RoutingDecisionDto routingDecision,
                                           RoutingReplayProbe replayProbe,
                                           PromptMemoryContextDto promptMemoryContext,
                                           String finalChannel) {
        List<String> memorySegments = collectMemorySegments(promptMemoryContext);
        memoryContributionRequestCount.incrementAndGet();
        if (memorySegments.contains("recent")) {
            memoryContributionRecentCount.incrementAndGet();
        }
        if (memorySegments.contains("semantic")) {
            memoryContributionSemanticCount.incrementAndGet();
        }
        if (memorySegments.contains("procedural")) {
            memoryContributionProceduralCount.incrementAndGet();
        }
        if (memorySegments.contains("persona")) {
            memoryContributionPersonaCount.incrementAndGet();
        }
        if (memorySegments.contains("rollup")) {
            memoryContributionRollupCount.incrementAndGet();
        }

        RoutingReplayItemDto sample = new RoutingReplayItemDto(
                Instant.now(),
                routingDecision == null ? "unknown" : safeValue(routingDecision.route()),
                safeValue(finalChannel),
                replayProbe == null ? "NONE" : safeValue(replayProbe.ruleCandidate()),
                replayProbe == null ? "NOT_RUN" : safeValue(replayProbe.preAnalyzeCandidate()),
                memorySegments,
                capText(userInput == null ? "" : userInput.trim(), 260)
        );
        synchronized (routingReplayLock) {
            routingReplaySamples.addLast(sample);
            while (routingReplaySamples.size() > routingReplayMaxSamples) {
                routingReplaySamples.removeFirst();
            }
        }
        routingReplayTotalCapturedCount.incrementAndGet();
    }

    private List<String> collectMemorySegments(PromptMemoryContextDto promptMemoryContext) {
        if (promptMemoryContext == null) {
            return List.of();
        }
        List<String> segments = new java.util.ArrayList<>();
        if (promptMemoryContext.recentConversation() != null && !promptMemoryContext.recentConversation().isBlank()) {
            segments.add("recent");
        }
        if (promptMemoryContext.semanticContext() != null && !promptMemoryContext.semanticContext().isBlank()) {
            segments.add("semantic");
            if (promptMemoryContext.semanticContext().contains("persisted rollup:")) {
                segments.add("rollup");
            }
        }
        if (promptMemoryContext.proceduralHints() != null && !promptMemoryContext.proceduralHints().isBlank()) {
            segments.add("procedural");
        }
        if (promptMemoryContext.personaSnapshot() != null && !promptMemoryContext.personaSnapshot().isEmpty()) {
            segments.add("persona");
        }
        return List.copyOf(segments);
    }

    private String safeValue(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return value.trim();
    }

    private String capText(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 14)) + "\n...[truncated]";
    }

    private void recordContextCompressionMetrics(int rawChars,
                                                 int finalChars,
                                                 boolean compressed,
                                                 int summarizedTurns) {
        long input = Math.max(0, rawChars);
        long output = Math.max(0, finalChars);
        contextCompressionRequestCount.incrementAndGet();
        contextCompressionInputChars.addAndGet(input);
        contextCompressionOutputChars.addAndGet(output);
        if (compressed) {
            contextCompressionAppliedCount.incrementAndGet();
        }
        contextCompressionSummarizedTurns.addAndGet(Math.max(0, summarizedTurns));
    }

    @Override
    public ContextCompressionMetricsDto snapshotContextCompressionMetrics() {
        long requests = contextCompressionRequestCount.get();
        long inputChars = contextCompressionInputChars.get();
        long outputChars = contextCompressionOutputChars.get();
        double ratio = inputChars <= 0 ? 0.0 : (double) outputChars / inputChars;
        return new ContextCompressionMetricsDto(
                requests,
                contextCompressionAppliedCount.get(),
                inputChars,
                outputChars,
                ratio,
                contextCompressionSummarizedTurns.get()
        );
    }

    @Override
    public SkillPreAnalyzeMetricsDto snapshotSkillPreAnalyzeMetrics() {
        return new SkillPreAnalyzeMetricsDto(
                skillPreAnalyzeMode,
                skillPreAnalyzeConfidenceThreshold,
                skillPreAnalyzeRequestCount.get(),
                skillPreAnalyzeExecutedCount.get(),
                skillPreAnalyzeAcceptedCount.get(),
                skillPreAnalyzeSkippedByGateCount.get(),
                skillPreAnalyzeSkippedBySkillCount.get(),
                detectedSkillLoopSkipBlockedCount.get(),
                skillTimeoutTriggeredCount.get()
        );
    }

    @Override
    public MemoryHitMetricsDto snapshotMemoryHitMetrics() {
        long requests = memoryHitRequestCount.get();
        long semanticHits = memoryHitSemanticCount.get();
        long proceduralHits = memoryHitProceduralCount.get();
        long rollupHits = memoryHitRollupCount.get();
        long totalHits = semanticHits + proceduralHits + rollupHits;
        double hitRate = requests <= 0 ? 0.0 : Math.min(1.0, totalHits / (double) (requests * 3L));
        return new MemoryHitMetricsDto(
                requests,
                semanticHits,
                proceduralHits,
                rollupHits,
                hitRate
        );
    }

    @Override
    public MemoryContributionMetricsDto snapshotMemoryContributionMetrics() {
        return new MemoryContributionMetricsDto(
                memoryContributionRequestCount.get(),
                memoryContributionRecentCount.get(),
                memoryContributionSemanticCount.get(),
                memoryContributionProceduralCount.get(),
                memoryContributionPersonaCount.get(),
                memoryContributionRollupCount.get()
        );
    }

    @Override
    public LocalEscalationMetricsDto snapshotLocalEscalationMetrics() {
        long localAttempts = localEscalationAttemptCount.get();
        long localHits = localEscalationHitCount.get();
        long fallbackAttempts = fallbackChainAttemptCount.get();
        long fallbackHits = fallbackChainHitCount.get();
        Map<String, Long> reasons = new LinkedHashMap<>();
        reasons.put("timeout", escalationReasonCounters.getOrDefault("timeout", new AtomicLong()).get());
        reasons.put("upstream_5xx", escalationReasonCounters.getOrDefault("upstream_5xx", new AtomicLong()).get());
        reasons.put("empty_response", escalationReasonCounters.getOrDefault("empty_response", new AtomicLong()).get());
        reasons.put("quality", escalationReasonCounters.getOrDefault("quality", new AtomicLong()).get());
        reasons.put("manual", escalationReasonCounters.getOrDefault("manual", new AtomicLong()).get());
        reasons.put("resource_guard", escalationReasonCounters.getOrDefault("resource_guard", new AtomicLong()).get());
        return new LocalEscalationMetricsDto(
                localAttempts,
                localHits,
                localAttempts <= 0 ? 0.0 : localHits / (double) localAttempts,
                fallbackAttempts,
                fallbackHits,
                fallbackAttempts <= 0 ? 0.0 : fallbackHits / (double) fallbackAttempts,
                Map.copyOf(reasons)
        );
    }

    @Override
    public RoutingReplayDatasetDto snapshotRoutingReplay(int limit) {
        int effectiveLimit = Math.max(1, Math.min(Math.max(1, limit), routingReplayMaxSamples));
        List<RoutingReplayItemDto> copy;
        synchronized (routingReplayLock) {
            copy = List.copyOf(routingReplaySamples);
        }
        int fromIndex = Math.max(0, copy.size() - effectiveLimit);
        List<RoutingReplayItemDto> samples = copy.subList(fromIndex, copy.size());

        Map<String, Long> byRoute = new LinkedHashMap<>();
        Map<String, Long> byFinalChannel = new LinkedHashMap<>();
        for (RoutingReplayItemDto sample : samples) {
            byRoute.merge(safeValue(sample.route()), 1L, Long::sum);
            byFinalChannel.merge(safeValue(sample.finalChannel()), 1L, Long::sum);
        }
        return new RoutingReplayDatasetDto(
                effectiveLimit,
                routingReplayTotalCapturedCount.get(),
                samples,
                Map.copyOf(byRoute),
                Map.copyOf(byFinalChannel)
        );
    }

    private String normalizeSkillPreAnalyzeMode(String mode) {
        String normalized = normalize(mode);
        if ("always".equals(normalized) || "never".equals(normalized)) {
            return normalized;
        }
        return "auto";
    }

    private Set<String> parseCsvSet(String rawCsv) {
        if (rawCsv == null || rawCsv.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> parsed = new LinkedHashSet<>();
        String[] parts = rawCsv.split(",");
        for (String part : parts) {
            String normalized = normalize(part);
            if (!normalized.isBlank()) {
                parsed.add(normalized);
            }
        }
        return Set.copyOf(parsed);
    }

    private List<String> parseCsvList(String rawCsv) {
        if (rawCsv == null || rawCsv.isBlank()) {
            return List.of();
        }
        List<String> parsed = new ArrayList<>();
        String[] parts = rawCsv.split(",");
        for (String part : parts) {
            String normalized = normalize(part);
            if (!normalized.isBlank() && !parsed.contains(normalized)) {
                parsed.add(normalized);
            }
        }
        return parsed.isEmpty() ? List.of() : List.copyOf(parsed);
    }

    private List<String> resolveHermesSearchPriorityOrder() {
        List<String> configured = parseCsvList(System.getProperty("mindos.dispatcher.parallel-routing.search-priority-order", ""));
        if (!configured.isEmpty()) {
            return configured;
        }
        if (braveFirstSearchRoutingEnabled || parallelDetectedSkillRoutingEnabled) {
            return List.of(
                    "mcp.bravesearch.websearch",
                    "mcp.qwensearch.websearch",
                    "mcp.serper.websearch",
                    "mcp.serpapi.websearch"
            );
        }
        return List.of();
    }

    private Map<String, Object> enrichHermesProfileContext(Map<String, Object> profileContext) {
        Map<String, Object> safeProfileContext = profileContext == null ? Map.of() : profileContext;
        List<String> searchPriorityOrder = resolveHermesSearchPriorityOrder();
        if (searchPriorityOrder.isEmpty() || safeProfileContext.containsKey("searchPriorityOrder")) {
            return safeProfileContext;
        }
        Map<String, Object> enriched = new LinkedHashMap<>(safeProfileContext);
        enriched.put("searchPriorityOrder", searchPriorityOrder);
        return Map.copyOf(enriched);
    }

    private boolean matchesConfiguredSkill(String skillName, Set<String> configuredSkills) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        if (configuredSkills == null || configuredSkills.isEmpty()) {
            return true;
        }
        String normalized = normalize(skillName);
        for (String configured : configuredSkills) {
            if (configured == null || configured.isBlank()) {
                continue;
            }
            String candidate = normalize(configured);
            if (candidate.endsWith(".*")) {
                String prefix = candidate.substring(0, candidate.length() - 1);
                if (!prefix.isBlank() && normalized.startsWith(prefix)) {
                    return true;
                }
                continue;
            }
            if (normalized.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private ExecutionTraceDto enrichTraceWithRouting(ExecutionTraceDto trace, RoutingDecisionDto routingDecision) {
        if (trace == null) {
            return new ExecutionTraceDto("single-pass", 0, null, List.of(), routingDecision);
        }
        return new ExecutionTraceDto(
                trace.strategy(),
                trace.replanCount(),
                trace.critique(),
                trace.steps(),
                routingDecision
        );
    }

    private RoutingDecisionDto enrichRoutingDecisionWithFinalObservability(RoutingDecisionDto routingDecision,
                                                                           String finalChannel,
                                                                           boolean realtimeLookup,
                                                                           boolean memoryDirectBypassed,
                                                                           String actualSearchSource) {
        RoutingDecisionDto base = routingDecision == null
                ? new RoutingDecisionDto("llm-fallback", normalizeOptional(finalChannel), 0.0, List.of(), List.of())
                : routingDecision;
        List<String> reasons = new ArrayList<>(base.reasons());
        upsertTraceReason(reasons, "realtimeLookup", String.valueOf(realtimeLookup));
        upsertTraceReason(reasons, "memoryDirectBypassed", String.valueOf(memoryDirectBypassed));
        upsertTraceReason(reasons, "actualSearchSource", normalizeOptional(actualSearchSource));
        return new RoutingDecisionDto(
                base.route(),
                base.selectedSkill(),
                base.confidence(),
                reasons,
                base.rejectedReasons()
        );
    }

    private void upsertTraceReason(List<String> reasons, String key, String value) {
        if (reasons == null || key == null || key.isBlank()) {
            return;
        }
        reasons.removeIf(reason -> reason != null && reason.startsWith(key + "="));
        reasons.add(key + "=" + (value == null ? "" : value));
    }

    private boolean isKnownSkillName(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        if (DecisionCapabilityCatalog.findByDecisionTarget(skillName)
                .map(DecisionCapabilityCatalog.CapabilityDefinition::executionSkill)
                .filter(this::isKnownExecutionSkillName)
                .isPresent()) {
            return true;
        }
        return isKnownExecutionSkillName(skillName);
    }

    private boolean isKnownExecutionSkillName(String skillName) {
        return skillEngine.listAvailableSkillSummaries().stream()
                .map(summary -> {
                    int separator = summary.indexOf(" - ");
                    return separator >= 0 ? summary.substring(0, separator).trim() : summary.trim();
                })
                .anyMatch(skillName::equals);
    }

    private String clip(String value) {
        if (value == null) {
            return "null";
        }
        int max = 240;
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
