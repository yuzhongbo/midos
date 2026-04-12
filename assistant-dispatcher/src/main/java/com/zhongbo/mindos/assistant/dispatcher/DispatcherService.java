package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.ContextCompressionMetricsReader;
import com.zhongbo.mindos.assistant.common.DispatcherRoutingMetricsReader;
import com.zhongbo.mindos.assistant.common.ImDegradedReplyMarker;
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
import com.zhongbo.mindos.assistant.skill.SkillEngineFacade;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.decision.DecisionParser;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.CandidatePlanner;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamValidator;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.SimpleCandidatePlanner;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.SimpleConversationLoop;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.SimpleFallbackPlan;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.SimpleParamValidator;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.MasterOrchestrationResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.MasterOrchestrator;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.routing.DispatchPlan;
import com.zhongbo.mindos.assistant.dispatcher.routing.RoutingCoordinator;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
public class DispatcherService implements ContextCompressionMetricsReader, DispatcherRoutingMetricsReader, DispatcherFacade {

    private static final Logger LOGGER = Logger.getLogger(DispatcherService.class.getName());
    private static final String SKILL_HELP_CHANNEL = "skills.help";
    private static final Set<String> SMALL_TALK_INPUTS = Set.of(
            "hi", "hello", "hey", "thanks", "thank you", "ok", "okay", "got it", "roger",
            "你好", "您好", "嗨", "谢谢", "多谢", "收到", "好的", "好", "嗯", "嗯嗯", "晚安", "早上好"
    );
    private static final Set<String> NEWS_DOMAIN_WHITELIST_TERMS = Set.of(
            "新闻", "热点", "快讯", "发布", "政策", "监管", "部委", "国务院", "央行",
            "科技", "ai", "芯片", "大模型", "算力", "机器人", "云计算",
            "财经", "金融", "经济", "市场", "产业", "融资", "并购", "上市", "财报", "投资", "a股", "港股", "美股"
    );
    private static final Set<String> WEATHER_DOMAIN_PENALTY_TERMS = Set.of(
            "天气", "天气预报", "气温", "降雨", "湿度", "空气质量", "风力", "台风",
            "accuweather", "weather.com", "weathernews", "中国气象局", "全国天气网"
    );

    private final SkillEngineFacade skillEngine;
    private final SkillDslParser skillDslParser;
    private final IntentModelRoutingPolicy intentModelRoutingPolicy;
    private final MetaOrchestratorService metaOrchestratorService;
    private final SkillCapabilityPolicy skillCapabilityPolicy;
    private final PersonaCoreService personaCoreService;
    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final LlmClient llmClient;
    private final SemanticAnalysisService semanticAnalysisService;
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
    private final AtomicBoolean acceptingRequests = new AtomicBoolean(true);
    private final AtomicLong activeDispatchCount = new AtomicLong();
    private final PromptBuilder promptBuilder;
    private final LLMDecisionEngine llmDecisionEngine;
    private final DecisionParser decisionParser;
    private final DecisionOrchestrator decisionOrchestrator;
    private final ParamValidator paramValidator;
    private final MemoryGateway memoryGateway;
    private final DecisionParamAssembler decisionParamAssembler;
    private final DispatchMemoryLifecycle dispatchMemoryLifecycle;
    private final DispatchPreparationSupport dispatchPreparationSupport;
    private final DispatchResultFinalizer dispatchResultFinalizer;
    private final BehaviorRoutingSupport behaviorRoutingSupport;
    private final SkillRoutingSupport skillRoutingSupport;
    private final SemanticRoutingSupport semanticRoutingSupport;
    private final DispatchRoutingPipeline dispatchRoutingPipeline;
    private MasterOrchestrator masterOrchestrator;
    private RoutingCoordinator routingCoordinator;

    public DispatcherService(SkillEngineFacade skillEngine,
                             SkillDslParser skillDslParser,
                             ParamValidator paramValidator,
                             DecisionOrchestrator decisionOrchestrator,
                             IntentModelRoutingPolicy intentModelRoutingPolicy,
                             MetaOrchestratorService metaOrchestratorService,
                             SkillCapabilityPolicy skillCapabilityPolicy,
                             PersonaCoreService personaCoreService,
                             DispatcherMemoryFacade dispatcherMemoryFacade,
                             LlmClient llmClient,
                             SemanticAnalysisService semanticAnalysisService,
                             PromptBuilder promptBuilder,
                             LLMDecisionEngine llmDecisionEngine,
                             DecisionParser decisionParser,
                             MemoryGateway memoryGateway,
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
                decisionOrchestrator,
                intentModelRoutingPolicy,
                metaOrchestratorService,
                skillCapabilityPolicy,
                personaCoreService,
                dispatcherMemoryFacade,
                llmClient,
                semanticAnalysisService,
                promptBuilder,
                llmDecisionEngine,
                decisionParser,
                memoryGateway,
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
    public DispatcherService(SkillEngineFacade skillEngine,
                           SkillDslParser skillDslParser,
                           ParamValidator paramValidator,
                           DecisionOrchestrator decisionOrchestrator,
                           IntentModelRoutingPolicy intentModelRoutingPolicy,
                           MetaOrchestratorService metaOrchestratorService,
                            SkillCapabilityPolicy skillCapabilityPolicy,
                               PersonaCoreService personaCoreService,
                               DispatcherMemoryFacade dispatcherMemoryFacade,
                               LlmClient llmClient,
                               SemanticAnalysisService semanticAnalysisService,
                              PromptBuilder promptBuilder,
                              LLMDecisionEngine llmDecisionEngine,
                              DecisionParser decisionParser,
                              MemoryGateway memoryGateway,
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
        this.intentModelRoutingPolicy = intentModelRoutingPolicy;
        this.metaOrchestratorService = metaOrchestratorService;
        this.skillCapabilityPolicy = skillCapabilityPolicy;
        this.personaCoreService = personaCoreService;
        this.dispatcherMemoryFacade = Objects.requireNonNull(dispatcherMemoryFacade, "dispatcherMemoryFacade");
        this.llmClient = llmClient;
        this.semanticAnalysisService = semanticAnalysisService;
        this.promptBuilder = promptBuilder;
        this.llmDecisionEngine = llmDecisionEngine;
        this.decisionParser = decisionParser;
        this.paramValidator = paramValidator;
        this.decisionOrchestrator = decisionOrchestrator;
        this.memoryGateway = memoryGateway;
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
        this.promptInjectionRiskTerms = parseRiskTerms(promptInjectionRiskTerms);
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
        this.decisionParamAssembler = new DecisionParamAssembler();
        this.behaviorRoutingSupport = new BehaviorRoutingSupport(
                this.skillDslParser,
                this.dispatcherMemoryFacade,
                this.preferenceReuseEnabled,
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
        this.skillRoutingSupport = new SkillRoutingSupport(
                this.skillEngine,
                this.dispatcherMemoryFacade,
                this.behaviorRoutingSupport,
                this.llmRoutingShortlistMaxSkills,
                this::inferMemoryBucket
        );
        this.semanticRoutingSupport = new SemanticRoutingSupport(
                this.dispatcherMemoryFacade,
                this.behaviorRoutingSupport,
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
                this.behaviorRoutingSupport,
                this::inferMemoryBucket
        );
        this.dispatchPreparationSupport = new DispatchPreparationSupport(
                this.dispatcherMemoryFacade,
                this.skillEngine,
                this.personaCoreService,
                this.semanticAnalysisService,
                this.semanticRoutingSupport,
                this.intentModelRoutingPolicy,
                new DispatchPreparationSupport.PreparationBridge() {
                    @Override
                    public void recordContextCompressionMetrics(int rawChars,
                                                                int finalChars,
                                                                boolean compressed,
                                                                int summarizedTurns) {
                        DispatcherService.this.recordContextCompressionMetrics(
                                rawChars,
                                finalChars,
                                compressed,
                                summarizedTurns
                        );
                    }

                    @Override
                    public boolean shouldSkipSemanticAnalysis(String userInput) {
                        return DispatcherService.this.shouldSkipSemanticAnalysis(userInput);
                    }

                    @Override
                    public boolean isRealtimeIntent(String userInput, SemanticAnalysisResult semanticAnalysis) {
                        return DispatcherService.this.isRealtimeIntent(userInput, semanticAnalysis);
                    }

                    @Override
                    public boolean isRealtimeLikeInput(String userInput, SemanticAnalysisResult semanticAnalysis) {
                        return DispatcherService.this.isRealtimeLikeInput(userInput, semanticAnalysis);
                    }

                    @Override
                    public void copyEscalationHints(Map<String, Object> source, Map<String, Object> llmContext) {
                        DispatcherService.this.copyEscalationHints(source, llmContext);
                    }

                    @Override
                    public void copyInteractionContext(Map<String, Object> profileContext, Map<String, Object> llmContext) {
                        DispatcherService.this.copyInteractionContext(profileContext, llmContext);
                    }

                    @Override
                    public void applyStageLlmRoute(String stage,
                                                  Map<String, Object> profileContext,
                                                  Map<String, Object> llmContext) {
                        DispatcherService.this.applyStageLlmRoute(stage, profileContext, llmContext);
                    }
                },
                this.memoryContextMaxChars,
                this.realtimeIntentMemoryShrinkEnabled,
                this.realtimeIntentMemoryShrinkIncludePersona,
                this.realtimeIntentMemoryShrinkMaxChars
        );
        this.dispatchResultFinalizer = new DispatchResultFinalizer(
                this.decisionOrchestrator,
                this.dispatchMemoryLifecycle,
                this.personaCoreService,
                new DispatchResultFinalizer.FinalizationBridge() {
                    @Override
                    public DispatchResultFinalizer.FinalizedSkill finalizeSkillResult(String userInput,
                                                                                      SkillResult result,
                                                                                      Map<String, Object> llmContext) {
                        SkillFinalizeOutcome outcome = DispatcherService.this.maybeFinalizeSkillResultWithLlm(userInput, result, llmContext);
                        return new DispatchResultFinalizer.FinalizedSkill(outcome.result(), outcome.applied());
                    }

                    @Override
                    public String capLlmReply(String output) {
                        return DispatcherService.this.capText(output, DispatcherService.this.llmReplyMaxChars);
                    }

                    @Override
                    public String classifyMcpSearchSource(String skillName) {
                        return DispatcherService.this.classifyMcpSearchSource(skillName);
                    }

                    @Override
                    public RoutingDecisionDto enrichRoutingDecisionWithFinalObservability(RoutingDecisionDto routingDecision,
                                                                                          String finalChannel,
                                                                                          boolean realtimeLookup,
                                                                                          boolean memoryDirectBypassed,
                                                                                          String actualSearchSource) {
                        return DispatcherService.this.enrichRoutingDecisionWithFinalObservability(
                                routingDecision,
                                finalChannel,
                                realtimeLookup,
                                memoryDirectBypassed,
                                actualSearchSource
                        );
                    }

                    @Override
                    public ExecutionTraceDto enrichTraceWithRouting(ExecutionTraceDto trace, RoutingDecisionDto routingDecision) {
                        return DispatcherService.this.enrichTraceWithRouting(trace, routingDecision);
                    }

                    @Override
                    public void recordRoutingReplaySample(String userInput,
                                                          RoutingDecisionDto routingDecision,
                                                          RoutingReplayProbe replayProbe,
                                                          PromptMemoryContextDto promptMemoryContext,
                                                          String finalChannel) {
                        DispatcherService.this.recordRoutingReplaySample(
                                userInput,
                                routingDecision,
                                replayProbe,
                                promptMemoryContext,
                                finalChannel
                        );
                    }
                }
        );
        this.dispatchRoutingPipeline = new DispatchRoutingPipeline(
                this.skillEngine,
                this.skillDslParser,
                this.behaviorRoutingSupport,
                this.semanticRoutingSupport,
                this.decisionOrchestrator,
                this.decisionParamAssembler,
                new DispatchRoutingPipeline.RoutingBridge() {
                    @Override
                    public Optional<SkillResult> maybeBlockByCapability(String skillName) {
                        return DispatcherService.this.maybeBlockByCapability(skillName);
                    }

                    @Override
                    public boolean isSkillPreExecuteGuardBlocked(String userId, String skillName, String userInput) {
                        return DispatcherService.this.isSkillPreExecuteGuardBlocked(userId, skillName, userInput);
                    }

                    @Override
                    public boolean isSkillLoopGuardBlocked(String userId, String skillName, String userInput) {
                        return DispatcherService.this.isSkillLoopGuardBlocked(userId, skillName, userInput);
                    }

                    @Override
                    public boolean isRealtimeIntent(String userInput, SemanticAnalysisResult semanticAnalysis) {
                        return DispatcherService.this.isRealtimeIntent(userInput, semanticAnalysis);
                    }

                    @Override
                    public boolean isRealtimeLikeInput(String userInput, SemanticAnalysisResult semanticAnalysis) {
                        return DispatcherService.this.isRealtimeLikeInput(userInput, semanticAnalysis);
                    }

                    @Override
                    public boolean shouldRunSkillPreAnalyze(String userId, String userInput) {
                        return DispatcherService.this.shouldRunSkillPreAnalyze(userId, userInput);
                    }

                    @Override
                    public boolean isCodeGenerationIntent(String userInput) {
                        return DispatcherService.this.isCodeGenerationIntent(userInput);
                    }

                    @Override
                    public Optional<SkillResult> answerMetaQuestion(String userInput) {
                        return DispatcherService.this.answerMetaQuestion(userInput);
                    }

                    @Override
                    public LlmDetectionResult detectSkillWithLlm(String userId,
                                                                 String userInput,
                                                                 String memoryContext,
                                                                 SkillContext context,
                                                                 Map<String, Object> profileContext) {
                        return DispatcherService.this.detectSkillWithLlm(userId, userInput, memoryContext, context, profileContext);
                    }

                    @Override
                    public SkillResult enrichMemoryHabitResult(SkillResult result, String routedSkill, Map<String, Object> profileContext) {
                        return DispatcherService.this.enrichMemoryHabitResult(result, routedSkill, profileContext);
                    }
                },
                this.braveFirstSearchRoutingEnabled,
                this.parallelDetectedSkillRoutingEnabled,
                this.parallelDetectedSkillRoutingMaxCandidates,
                this.parallelDetectedSkillRoutingTimeoutMs,
                this.semanticAnalysisRouteMinConfidence,
                this.skillPreAnalyzeSkipSkills,
                this.skillPreAnalyzeRequestCount,
                this.skillPreAnalyzeExecutedCount,
                this.skillPreAnalyzeAcceptedCount,
                this.skillPreAnalyzeSkippedByGateCount,
                this.skillPreAnalyzeSkippedBySkillCount,
                this.detectedSkillLoopSkipBlockedCount
        );
    }

    @Autowired(required = false)
    void setMasterOrchestrator(MasterOrchestrator masterOrchestrator) {
        this.masterOrchestrator = masterOrchestrator;
    }

    @Autowired(required = false)
    void setRoutingCoordinator(RoutingCoordinator routingCoordinator) {
        this.routingCoordinator = routingCoordinator;
    }

    private DispatcherMemoryFacade activeDispatcherMemoryFacade() {
        return dispatcherMemoryFacade;
    }

    public DispatchResult dispatch(String userId, String userInput) {
        return dispatch(userId, userInput, Map.of());
    }

    public DispatchResult dispatch(String userId, String userInput, Map<String, Object> profileContext) {
        return dispatchAsync(userId, userInput, profileContext).join();
    }

    public CompletableFuture<DispatchResult> dispatchAsync(String userId, String userInput) {
        return dispatchAsync(userId, userInput, Map.of());
    }

    public CompletableFuture<DispatchResult> dispatchAsync(String userId, String userInput, Map<String, Object> profileContext) {
        if (!acceptingRequests.get()) {
            return CompletableFuture.completedFuture(buildDrainingResult(userInput));
        }
        try {
            Instant startTime = Instant.now();
            LOGGER.info("Dispatcher input: userId=" + userId + ", input=" + clip(userInput));
            DispatchExecutionState executionState = new DispatchExecutionState();
            RoutingReplayProbe replayProbe = new RoutingReplayProbe();

            dispatchMemoryLifecycle.recordUserInput(userId, userInput);

            // Fast-path: short conversational acknowledgements should avoid expensive routing.
            String normalizedInputForBypass = normalize(userInput);
            if (semanticAnalysisSkipShortSimpleEnabled && isConversationalBypassInput(normalizedInputForBypass)) {
                DispatchResult bypass = handleConversationalBypass(userId, normalizedInputForBypass);
                return CompletableFuture.completedFuture(bypass);
            }

            // Fast-path: short conversational acknowledgements (e.g. 谢谢/好的/收到) should avoid
            // expensive routing and LLM calls. Return a lightweight canned/no-op reply and record
            // it in conversation history to keep memory consistent.

            if (isPromptInjectionAttempt(userInput)) {
                LOGGER.warning("Dispatcher guard=prompt-injection, userId=" + userId + ", input=" + clip(userInput));
                dispatchMemoryLifecycle.recordAssistantReply(userId, promptInjectionSafeReply);
                executionState.setRoutingDecision(new RoutingDecisionDto(
                        "security.guard",
                        "security.guard",
                        1.0,
                        List.of("prompt injection guard matched configured risky terms"),
                        List.of()
                ));
                return CompletableFuture.completedFuture(new DispatchResult(
                        promptInjectionSafeReply,
                        "security.guard",
                        new ExecutionTraceDto("single-pass", 0, null, List.of(), executionState.routingDecision())
                ));
            }

            activeDispatchCount.incrementAndGet();
            DispatchPreparationSupport.PreparedDispatch preparedDispatch = dispatchPreparationSupport.prepare(
                    userId,
                    userInput,
                    profileContext,
                    routingCoordinator
            );
            Map<String, Object> resolvedProfileContext = preparedDispatch.resolvedProfileContext();
            PromptMemoryContextDto promptMemoryContext = preparedDispatch.promptMemoryContext();
            SemanticAnalysisResult semanticAnalysis = preparedDispatch.semanticAnalysis();
            boolean realtimeIntentInput = preparedDispatch.realtimeIntentInput();
            executionState.setRealtimeLookup(preparedDispatch.realtimeLookup());
            String routingInput = preparedDispatch.routingInput();
            String effectiveMemoryContext = preparedDispatch.effectiveMemoryContext();
            SkillContext context = preparedDispatch.context();
            Map<String, Object> llmContext = preparedDispatch.llmContext();

            DispatchPlan routingPlan = routingCoordinator == null
                    ? null
                    : routingCoordinator.preparePlan(userInput, semanticAnalysis, context, resolvedProfileContext);
            boolean useMasterOrchestrator = routingPlan == null
                    ? shouldUseMasterOrchestrator(resolvedProfileContext)
                    : routingPlan.usesMultiAgent();
            Decision multiAgentDecision = routingPlan == null
                    ? buildMultiAgentDecision(userInput, semanticAnalysis, context)
                    : routingPlan.decision();
            if (useMasterOrchestrator) {
                return attachDispatchCompletion(
                        executeMasterOrchestratorDispatch(
                                userId,
                                userInput,
                                promptMemoryContext,
                                llmContext,
                                realtimeIntentInput,
                                executionState,
                                semanticAnalysis,
                                replayProbe,
                                multiAgentDecision,
                                resolvedProfileContext
                        ),
                        userId,
                        startTime,
                        executionState,
                        false
                );
            }

            CompletableFuture<DispatchResult> future = metaOrchestratorService.orchestrate(
                            () -> executeSinglePass(userId, userInput, context, effectiveMemoryContext, promptMemoryContext, llmContext, realtimeIntentInput, executionState, semanticAnalysis, replayProbe),
                            () -> CompletableFuture.completedFuture(buildFallbackResult(effectiveMemoryContext, promptMemoryContext, routingInput, llmContext, realtimeIntentInput))
                    )
                    .thenApply(orchestration -> dispatchResultFinalizer.finalizeMetaOrchestration(
                            userId,
                            userInput,
                            orchestration,
                            llmContext,
                            resolvedProfileContext,
                            promptMemoryContext,
                            replayProbe,
                            executionState
                    ));
            return attachDispatchCompletion(future, userId, startTime, executionState, false);
        } catch (RuntimeException | Error ex) {
            activeDispatchCount.decrementAndGet();
            throw ex;
        }
    }

    public CompletableFuture<DispatchResult> dispatchStream(String userId,
                                                            String userInput,
                                                            Map<String, Object> profileContext,
                                                            Consumer<String> deltaConsumer) {
        if (!acceptingRequests.get()) {
            return CompletableFuture.completedFuture(buildDrainingResult(userInput));
        }
        try {
            Instant startTime = Instant.now();
            LOGGER.info("Dispatcher(stream) input: userId=" + userId + ", input=" + clip(userInput));
            DispatchExecutionState executionState = new DispatchExecutionState();
            RoutingReplayProbe replayProbe = new RoutingReplayProbe();

            dispatchMemoryLifecycle.recordUserInput(userId, userInput);

            // Fast-path: short conversational acknowledgements should avoid expensive routing
            // and streaming LLM calls. Mirror the non-streaming `dispatch` behaviour so
            // short replies like "收到" / "好的" are handled quickly.
            String normalizedInputForBypass = normalize(userInput);
            if (semanticAnalysisSkipShortSimpleEnabled && isConversationalBypassInput(normalizedInputForBypass)) {
                DispatchResult bypass = handleConversationalBypass(userId, normalizedInputForBypass);
                return CompletableFuture.completedFuture(bypass);
            }

            if (isPromptInjectionAttempt(userInput)) {
                String safeReply = promptInjectionSafeReply;
                dispatchMemoryLifecycle.recordAssistantReply(userId, safeReply);
                RoutingDecisionDto decision = new RoutingDecisionDto(
                        "security.guard",
                        "security.guard",
                        1.0,
                        List.of("prompt injection guard matched configured risky terms"),
                        List.of()
                );
                ExecutionTraceDto trace = new ExecutionTraceDto("stream-single-pass", 0, null, List.of(), decision);
                return CompletableFuture.completedFuture(new DispatchResult(safeReply, "security.guard", trace));
            }

            activeDispatchCount.incrementAndGet();
            DispatchPreparationSupport.PreparedDispatch preparedDispatch = dispatchPreparationSupport.prepare(
                    userId,
                    userInput,
                    profileContext,
                    routingCoordinator
            );
            Map<String, Object> resolvedProfileContext = preparedDispatch.resolvedProfileContext();
            PromptMemoryContextDto promptMemoryContext = preparedDispatch.promptMemoryContext();
            SemanticAnalysisResult semanticAnalysis = preparedDispatch.semanticAnalysis();
            boolean realtimeIntentInput = preparedDispatch.realtimeIntentInput();
            executionState.setRealtimeLookup(preparedDispatch.realtimeLookup());
            String routingInput = preparedDispatch.routingInput();
            String effectiveMemoryContext = preparedDispatch.effectiveMemoryContext();
            SkillContext context = preparedDispatch.context();
            Map<String, Object> llmContext = preparedDispatch.llmContext();

            CompletableFuture<DispatchResult> future = dispatchRoutingPipeline.routeToSkillAsync(userId, userInput, context, effectiveMemoryContext, semanticAnalysis, replayProbe)
                    .thenApply(routingOutcome -> {
                        executionState.setRoutingDecision(routingOutcome.routingDecision());
                        return routingOutcome.result().orElseGet(() ->
                                buildLlmFallbackStreamResult(effectiveMemoryContext, promptMemoryContext, routingInput, llmContext, realtimeIntentInput, deltaConsumer));
                    })
                    .thenApply(result -> dispatchResultFinalizer.finalizeStreamResult(
                            userId,
                            userInput,
                            result,
                            llmContext,
                            resolvedProfileContext,
                            promptMemoryContext,
                            replayProbe,
                            executionState
                    ));
            return attachDispatchCompletion(future, userId, startTime, executionState, true);
        } catch (RuntimeException | Error ex) {
            activeDispatchCount.decrementAndGet();
            throw ex;
        }
    }

    private CompletableFuture<SkillResult> executeSinglePass(String userId,
                                                             String userInput,
                                                             SkillContext context,
                                                             String memoryContext,
                                                             PromptMemoryContextDto promptMemoryContext,
                                                             Map<String, Object> llmContext,
                                                             boolean realtimeIntentInput,
                                                             DispatchExecutionState executionState,
                                                             SemanticAnalysisResult semanticAnalysis,
                                                             RoutingReplayProbe replayProbe) {
        return dispatchRoutingPipeline.routeToSkillAsync(userId, userInput, context, memoryContext, semanticAnalysis, replayProbe)
                .thenApply(routingOutcome -> {
                    executionState.setRoutingDecision(routingOutcome.routingDecision());
                    return routingOutcome.result().orElseGet(() ->
                        buildFallbackResult(memoryContext, promptMemoryContext, context.input(), llmContext, realtimeIntentInput));
                });
    }

    private CompletableFuture<DispatchResult> executeMasterOrchestratorDispatch(String userId,
                                                                                String userInput,
                                                                                PromptMemoryContextDto promptMemoryContext,
                                                                                Map<String, Object> llmContext,
                                                                                boolean realtimeIntentInput,
                                                                                DispatchExecutionState executionState,
                                                                                SemanticAnalysisResult semanticAnalysis,
                                                                                RoutingReplayProbe replayProbe,
                                                                                Decision decision,
                                                                                Map<String, Object> resolvedProfileContext) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> multiAgentProfileContext = new LinkedHashMap<>(resolvedProfileContext == null ? Map.of() : resolvedProfileContext);
            multiAgentProfileContext.put("multiAgent", true);
            multiAgentProfileContext.put("orchestrationMode", "multi-agent");
            multiAgentProfileContext.put("multiAgent.skipMemoryWrite", true);
            if (decision == null || masterOrchestrator == null) {
                return new DispatchResult("multi-agent orchestrator unavailable", "multi-agent.master",
                        new ExecutionTraceDto("multi-agent-master", 0,
                                new CritiqueReportDto(false, "multi-agent orchestrator unavailable", "replan"),
                                List.of()));
            }
            replayProbe.setRuleCandidate("multi-agent-master");
            llmContext.put("routeStage", "multi-agent-master");
            executionState.setRealtimeLookup(realtimeIntentInput || isRealtimeLikeInput(userInput, semanticAnalysis));

            MasterOrchestrationResult orchestration = masterOrchestrator.execute(
                    userId,
                    userInput,
                    decision,
                    multiAgentProfileContext
            );
            SkillResult result = orchestration == null || orchestration.result() == null
                    ? SkillResult.failure(firstNonBlank(decision.target(), "multi-agent.master"), "master orchestrator produced no result")
                    : orchestration.result();
            return dispatchResultFinalizer.finalizeMasterResult(
                    userId,
                    userInput,
                    decision,
                    orchestration,
                    result,
                    llmContext,
                    resolvedProfileContext,
                    promptMemoryContext,
                    replayProbe,
                    executionState
            );
        });
    }

    private Decision buildMultiAgentDecision(String userInput,
                                             SemanticAnalysisResult semanticAnalysis,
                                             SkillContext context) {
        if (context == null) {
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>(context.attributes() == null ? Map.of() : context.attributes());
        if (semanticAnalysis != null) {
            params.putAll(semanticAnalysis.asAttributes());
            if (semanticAnalysis.payload() != null && !semanticAnalysis.payload().isEmpty()) {
                params.putIfAbsent("semanticPayload", semanticAnalysis.payload());
            }
            if (semanticAnalysis.keywords() != null && !semanticAnalysis.keywords().isEmpty()) {
                params.putIfAbsent("semanticKeywords", semanticAnalysis.keywords());
            }
        }
        params.putIfAbsent("input", context.input());
        params.putIfAbsent("multiAgent", true);
        params.putIfAbsent("orchestrationMode", "multi-agent");
        String intent = firstNonBlank(
                semanticAnalysis == null ? null : semanticAnalysis.intent(),
                semanticAnalysis == null ? null : semanticAnalysis.suggestedSkill(),
                context.input()
        );
        String target = firstNonBlank(
                semanticAnalysis == null ? null : semanticAnalysis.suggestedSkill(),
                semanticAnalysis == null ? null : semanticAnalysis.intent(),
                "llm.orchestrate"
        );
        double confidence = semanticAnalysis == null ? 0.75 : Math.max(semanticAnalysis.effectiveConfidence(), 0.75);
        return new Decision(intent, target, params, confidence, false);
    }

    private boolean shouldUseMasterOrchestrator(Map<String, Object> profileContext) {
        if (masterOrchestrator == null) {
            return false;
        }
        if (routingCoordinator != null) {
            return routingCoordinator.shouldUseMasterOrchestrator(profileContext);
        }
        if (isTruthy(profileContext == null ? null : profileContext.get("multiAgent"))) {
            return true;
        }
        String orchestrationMode = asString(profileContext == null ? null : profileContext.get("orchestrationMode"));
        return "multi-agent".equalsIgnoreCase(orchestrationMode)
                || "master".equalsIgnoreCase(orchestrationMode)
                || "master-orchestrator".equalsIgnoreCase(orchestrationMode);
    }

    private boolean isTruthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized)
                || "1".equals(normalized)
                || "yes".equals(normalized)
                || "on".equals(normalized);
    }

    private CompletableFuture<DispatchResult> attachDispatchCompletion(CompletableFuture<DispatchResult> future,
                                                                       String userId,
                                                                       Instant startTime,
                                                                       DispatchExecutionState executionState,
                                                                       boolean streamMode) {
        return future.whenComplete((result, error) -> {
            activeDispatchCount.decrementAndGet();
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
                    result.channel(),
                    result.executionTrace(),
                    executionState.skillPostprocessSent(),
                    executionState.finalResultSuccess(),
                    executionState.realtimeLookup(),
                    executionState.memoryDirectBypassed()
            );
        });
    }

    private SkillResult buildFallbackResult(String memoryContext,
                                            PromptMemoryContextDto promptMemoryContext,
                                            String userInput,
                                            Map<String, Object> llmContext,
                                            boolean realtimeIntentInput) {
        QueryContext queryContext = buildQueryContext(llmContext, userInput, promptMemoryContext);
        boolean realtimeLookup = realtimeIntentInput || isRealtimeLikeInput(userInput);
        try {
            LOGGER.info(() -> "dispatcher.llm.debug userQuery=" + clip(userInput)
                    + ", explicit=" + queryContext.explicitLlmRequest()
                    + ", complex=" + queryContext.complexReasoningRequired()
                    + ", realtimeLookup=" + realtimeLookup
                    + ", memoryDebug=" + promptMemoryDebugSummary(promptMemoryContext)
            );
        } catch (Exception e) {
            // best-effort debug logging
        }
        if (!realtimeLookup && !llmDecisionEngine.shouldCallLLM(queryContext)) {
            return buildMemoryDirectResult(promptMemoryContext, userInput);
        }
        return SkillResult.success("llm", callLlmWithLocalEscalation(
                buildFallbackPrompt(memoryContext, promptMemoryContext, userInput, realtimeIntentInput),
                llmContext
        ));
    }

    private SkillResult buildLlmFallbackStreamResult(String memoryContext,
                                                     PromptMemoryContextDto promptMemoryContext,
                                                     String userInput,
                                                     Map<String, Object> llmContext,
                                                     boolean realtimeIntentInput,
                                                     Consumer<String> deltaConsumer) {
        QueryContext queryContext = buildQueryContext(llmContext, userInput, promptMemoryContext);
        boolean realtimeLookup = realtimeIntentInput || isRealtimeLikeInput(userInput);
        try {
            LOGGER.info(() -> "dispatcher.llm.stream.debug userQuery=" + clip(userInput)
                    + ", explicit=" + queryContext.explicitLlmRequest()
                    + ", complex=" + queryContext.complexReasoningRequired()
                    + ", realtimeLookup=" + realtimeLookup
                    + ", memoryDebug=" + promptMemoryDebugSummary(promptMemoryContext)
            );
        } catch (Exception e) {
            // best-effort debug logging
        }
        if (!realtimeLookup && !llmDecisionEngine.shouldCallLLM(queryContext)) {
            SkillResult result = buildMemoryDirectResult(promptMemoryContext, userInput);
            if (deltaConsumer != null) {
                deltaConsumer.accept(result.output());
            }
            return result;
        }
        String prompt = buildFallbackPrompt(memoryContext, promptMemoryContext, userInput, realtimeIntentInput);
        StringBuilder aggregated = new StringBuilder();
        llmClient.streamResponse(prompt, llmContext, chunk -> {
            if (chunk == null || chunk.isBlank()) {
                return;
            }
            aggregated.append(chunk);
            if (deltaConsumer != null) {
                deltaConsumer.accept(chunk);
            }
        });
        String output = aggregated.toString().trim();
        if (output.isBlank()) {
            output = callLlmWithLocalEscalation(prompt, llmContext);
        }
        return SkillResult.success("llm", output);
    }

    private SkillFinalizeOutcome maybeFinalizeSkillResultWithLlm(String userInput,
                                                                 SkillResult result,
                                                                 Map<String, Object> llmContext) {
        if (!skillFinalizeWithLlmEnabled || result == null || !result.success()) {
            return SkillFinalizeOutcome.notApplied(result);
        }
        String channel = result.skillName();
        if (channel == null || channel.isBlank() || "llm".equals(channel) || "security.guard".equals(channel)) {
            return SkillFinalizeOutcome.notApplied(result);
        }
        if (!matchesConfiguredSkill(channel, skillFinalizeWithLlmSkills)) {
            return SkillFinalizeOutcome.notApplied(result);
        }
        String rawOutput = result.output() == null ? "" : result.output();
        if (rawOutput.isBlank()) {
            return SkillFinalizeOutcome.notApplied(result);
        }

        String prompt = buildSkillFinalizePrompt(userInput, channel, rawOutput);
        Map<String, Object> finalizeContext = new LinkedHashMap<>(llmContext == null ? Map.of() : llmContext);
        finalizeContext.put("routeStage", "skill-postprocess");
        finalizeContext.put("skillChannel", channel);
        logMcpPostprocessTrace(channel, rawOutput);
        if (skillFinalizeWithLlmProvider != null) {
            finalizeContext.put("llmProvider", skillFinalizeWithLlmProvider);
        }
        if (skillFinalizeWithLlmPreset != null) {
            finalizeContext.put("llmPreset", skillFinalizeWithLlmPreset);
        }
        applyStageLlmRoute("skill-postprocess", null, finalizeContext);
        String optimized = callLlmWithLocalEscalation(prompt, finalizeContext);
        if (optimized == null || optimized.isBlank()) {
            return SkillFinalizeOutcome.notApplied(result);
        }
        if (optimized.startsWith("[LLM ")) {
            return SkillFinalizeOutcome.notApplied(result);
        }
        String finalizedOutput = optimized.trim();
        if (isNewsSearchFinalizeChannel(channel)) {
            finalizedOutput = ensureNewsBriefShape(finalizedOutput, rawOutput);
        }
        return SkillFinalizeOutcome.applied(SkillResult.success(channel, capText(finalizedOutput, skillFinalizeWithLlmMaxOutputChars)));
    }

    private String buildSkillFinalizePrompt(String userInput, String channel, String rawOutput) {
        StringBuilder summary = new StringBuilder();
        summary.append("skill=").append(channel).append('\n');
        summary.append("input=").append(capText(userInput == null ? "" : userInput, 220)).append('\n');
        summary.append("raw_output=\n").append(capText(rawOutput, 1200));

        if (isNewsSearchFinalizeChannel(channel)) {
            String prompt = "你是新闻整理助手。给你一份搜索得到的新闻/资讯结果，请整理成适合直接发给用户的新闻简报。"
                    + "要求：\n"
                    + "1. 保持新闻特点，严格按下面结构输出，不要改标题名：\n"
                    + "今日新闻标题：\n"
                    + "1. ...\n"
                    + "2. ...\n"
                    + "3. ...\n"
                    + "总结：...\n"
                    + "2. 直接输出结果，不要写任何开场白、寒暄、致歉、确认、等待或自我说明；不要出现“好的”“请稍等”“我正在搜索”“已收到”“稍后给你”等话术；\n"
                    + "3. ‘今日新闻标题：’下面列出 3-6 条新闻标题或核心要点，每条单独一行，优先保留原始标题信息；\n"
                    + "4. 标题要尽量贴近原始结果，不要凭空编造；\n"
                    + "5. 最后必须单独输出“总结：”，概括今天的整体动态、趋势或值得关注点；\n"
                    + "6. 如果原始结果不足，就按实际数量输出，不要凑数；\n"
                    + "7. 语言自然、信息清晰，不要泄露内部字段名；控制在 8-12 行中文。\n"
                    + summary;
            return capText(prompt, promptMaxChars);
        }

        String prompt = "你是回复优化助手。给你一个技能结构化执行结果，请输出面向用户的最终答复。"
                + "要求：自然、简洁、可执行，避免模板化列表；不要泄露内部字段名；不要写任何开场白、寒暄、致歉、确认、等待或“我正在…”类句子；控制在 6-10 行中文。\n"
                + summary;
        return capText(prompt, promptMaxChars);
    }

    private boolean isNewsSearchFinalizeChannel(String channel) {
        String normalized = normalize(channel);
        return "mcp.qwensearch.websearch".equals(normalized)
                || "mcp.bravesearch.websearch".equals(normalized)
                || "mcp.brave.websearch".equals(normalized);
    }

    private String ensureNewsBriefShape(String optimizedOutput, String rawOutput) {
        String normalizedOutput = normalizeMultilineText(stripImDegradedMarkers(optimizedOutput));
        String normalizedRawOutput = normalizeMultilineText(stripImDegradedMarkers(rawOutput));
        if (normalizedOutput.isBlank()) {
            return optimizedOutput == null ? "" : optimizedOutput.trim();
        }

        List<String> headlines = extractNewsHeadlines(normalizedOutput);
        if (headlines.size() < 2) {
            headlines = mergeHeadlines(headlines, extractNewsHeadlines(normalizedRawOutput));
        }
        String summary = extractNewsSummary(normalizedOutput);
        if (summary.isBlank()) {
            summary = synthesizeNewsSummary(headlines, normalizedOutput);
        }

        if (headlines.isEmpty()) {
            return normalizedOutput.contains("总结：") ? normalizedOutput : normalizedOutput + "\n总结：" + summary;
        }

        StringBuilder builder = new StringBuilder("今日新闻标题：\n");
        int index = 1;
        for (String headline : headlines) {
            builder.append(index).append(". ").append(headline).append('\n');
            index++;
            if (index > 6) {
                break;
            }
        }
        builder.append("总结：").append(summary);
        return builder.toString().trim();
    }

    private String normalizeMultilineText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
    }

    private List<String> extractNewsHeadlines(String text) {
        String normalized = normalizeMultilineText(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<RankedHeadlineCandidate> rankedCandidates = new ArrayList<>();
        int sourceOrder = 0;
        for (String line : normalized.split("\n+")) {
            String originalLine = normalizeMultilineText(line);
            String candidate = sanitizeNewsLine(line);
            if (candidate.isBlank()) {
                continue;
            }
            boolean structuredLine = originalLine.matches("^(?:[-*•]+|[0-9]+[.)、]).*");
            if (candidate.startsWith("今日新闻标题")) {
                continue;
            }
            if (candidate.startsWith("总结：") || candidate.startsWith("总结:")) {
                continue;
            }
            if (ImDegradedReplyMarker.parse(candidate).isPresent()) {
                continue;
            }
            if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                continue;
            }
            if (candidate.startsWith("Brave 搜索（") || candidate.startsWith("Qwen MCP") || candidate.startsWith("搜索结果")) {
                continue;
            }
            if (isLikelyWeatherNoiseLine(candidate)) {
                continue;
            }
            if (candidate.length() < 4) {
                continue;
            }
            if (!structuredLine && candidate.length() > 24 && (candidate.contains("，") || candidate.contains("。"))) {
                continue;
            }
            int separatorIndex = candidate.indexOf(" - ");
            if (separatorIndex > 0) {
                candidate = candidate.substring(0, separatorIndex).trim();
            }
            int domainScore = scoreNewsDomainRelevance(candidate);
            if (domainScore <= 0) {
                continue;
            }
            rankedCandidates.add(new RankedHeadlineCandidate(candidate, domainScore, sourceOrder));
            sourceOrder++;
        }
        if (rankedCandidates.isEmpty()) {
            return List.of();
        }
        rankedCandidates.sort(Comparator
                .comparingInt(RankedHeadlineCandidate::score).reversed()
                .thenComparingInt(RankedHeadlineCandidate::sourceOrder));
        LinkedHashSet<String> deduped = new LinkedHashSet<>();
        for (RankedHeadlineCandidate candidate : rankedCandidates) {
            deduped.add(candidate.text());
            if (deduped.size() >= 6) {
                break;
            }
        }
        return deduped.isEmpty() ? List.of() : List.copyOf(deduped);
    }

    private List<String> mergeHeadlines(List<String> first, List<String> second) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return merged.isEmpty() ? List.of() : List.copyOf(merged);
    }

    private String sanitizeNewsLine(String line) {
        String candidate = normalizeMultilineText(line);
        candidate = candidate.replaceFirst("^(?:[-*•]+|[0-9]+[.)、])\\s*", "");
        candidate = candidate.replaceFirst("^(?:标题|要点)[:：]\\s*", "");
        return candidate.trim();
    }

    private String extractNewsSummary(String text) {
        String normalized = normalizeMultilineText(stripImDegradedMarkers(text));
        if (normalized.isBlank()) {
            return "";
        }
        for (String line : normalized.split("\n+")) {
            String candidate = normalizeMultilineText(line);
            if (candidate.startsWith("总结：") || candidate.startsWith("总结:")) {
                String extracted = candidate.substring(candidate.indexOf('：') >= 0 ? candidate.indexOf('：') + 1 : candidate.indexOf(':') + 1).trim();
                String sanitized = normalizeMultilineText(stripImDegradedMarkers(extracted));
                if (!sanitized.isBlank()) {
                    return sanitized;
                }
            }
        }
        return "";
    }

    private String synthesizeNewsSummary(List<String> headlines, String fallbackText) {
        if (headlines != null && !headlines.isEmpty()) {
            if (headlines.size() == 1) {
                return "今天的重点主要围绕“" + headlines.get(0) + "”展开，值得继续关注后续进展。";
            }
            return "今天的新闻重点主要集中在“" + headlines.get(0) + "”以及“" + headlines.get(1) + "”等方向，整体仍以持续推进和阶段性进展为主。";
        }
        String normalized = normalizeMultilineText(stripImDegradedMarkers(fallbackText));
        if (normalized.isBlank()) {
            return "今天的新闻动态以阶段性进展为主，建议结合后续更新持续关注。";
        }
        return normalized.length() <= 90 ? normalized : normalized.substring(0, 90).trim() + "…";
    }

    private String stripImDegradedMarkers(String text) {
        String normalized = normalizeMultilineText(text);
        if (normalized.isBlank()) {
            return "";
        }
        List<String> keptLines = new ArrayList<>();
        for (String line : normalized.split("\n+")) {
            String candidate = normalizeMultilineText(line);
            if (candidate.isBlank()) {
                continue;
            }
            var parsedMarker = ImDegradedReplyMarker.parse(candidate).orElse(null);
            if (parsedMarker != null) {
                if (!parsedMarker.remainder().isBlank()) {
                    keptLines.add(parsedMarker.remainder());
                }
                continue;
            }
            keptLines.add(candidate);
        }
        return keptLines.isEmpty() ? "" : String.join("\n", keptLines);
    }


    private boolean isLikelyWeatherNoiseLine(String line) {
        String normalized = normalize(line);
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.contains("天气预报")
                || normalized.contains("7天天气")
                || normalized.contains("10天天气")
                || normalized.contains("15天天气")
                || normalized.contains("accuweather")
                || normalized.contains("中国气象局")
                || normalized.contains("weather.com")
                || normalized.contains("weathernews")
                || normalized.contains("全国天气网");
    }

    private void logMcpPostprocessTrace(String channel, String rawOutput) {
        String source = classifyMcpSearchSource(channel);
        if (source.isBlank()) {
            return;
        }
        LOGGER.info(() -> "{\"event\":\"dispatcher.skill-postprocess.trace\",\"source\":\""
                + source
                + "\",\"channel\":\""
                + (channel == null ? "" : channel)
                + "\",\"sent\":true,\"outputChars\":"
                + (rawOutput == null ? 0 : rawOutput.length())
                + "}");
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
        String searchSource = classifyMcpSearchSource(!selectedSkill.isBlank() ? selectedSkill : finalChannel);
        String actualSearchSource = searchSource;
        boolean searchAttempted = !searchSource.isBlank();
        String searchStatus = resolveSearchStatus(searchAttempted, selectedSkill, finalChannel, trace, finalResultSuccess);
        boolean fallbackUsed = trace != null && trace.replanCount() > 0;
        LOGGER.info(() -> "{\"event\":\"dispatcher.final.trace\",\"userId\":\""
                + (userId == null ? "" : userId)
                + "\",\"searchSource\":\""
                + searchSource
                + "\",\"actualSearchSource\":\""
                + actualSearchSource
                + "\",\"searchAttempted\":"
                + searchAttempted
                + ",\"searchStatus\":\""
                + searchStatus
                + "\",\"selectedSkill\":\""
                + selectedSkill
                + "\",\"postprocessSent\":"
                + skillPostprocessSent
                + ",\"realtimeLookup\":"
                + realtimeLookup
                + ",\"memoryDirectBypassed\":"
                + memoryDirectBypassed
                + ",\"fallbackUsed\":"
                + fallbackUsed
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
        if (!selectedSkill.isBlank() && selectedSkill.equalsIgnoreCase(normalizeOptional(finalChannel))) {
            return "success";
        }
        if ("llm".equalsIgnoreCase(normalizeOptional(finalChannel))) {
            return "failed";
        }
        return "unknown";
    }

    private String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private String classifyMcpSearchSource(String channel) {
        String normalized = normalize(channel);
        if (normalized.startsWith("mcp.qwensearch.")) {
            return "qwen";
        }
        if (normalized.startsWith("mcp.bravesearch.") || normalized.startsWith("mcp.brave.")) {
            return "brave";
        }
        return "";
    }

    public void beginDrain() {
        acceptingRequests.set(false);
    }

    public void resumeAcceptingRequests() {
        acceptingRequests.set(true);
    }

    public boolean isAcceptingRequests() {
        return acceptingRequests.get();
    }

    public long getActiveDispatchCount() {
        return Math.max(0L, activeDispatchCount.get());
    }

    public boolean waitForActiveDispatches(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (activeDispatchCount.get() <= 0L) {
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return activeDispatchCount.get() <= 0L;
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

    private boolean isSkillPreExecuteGuardBlocked(String userId, String skillName, String userInput) {
        if (!preExecuteHeavySkillLoopGuardEnabled || !isPreExecuteHeavySkill(skillName)) {
            return false;
        }
        return isSkillLoopGuardBlocked(userId, skillName, userInput);
    }

    private boolean isPreExecuteHeavySkill(String skillName) {
        if (preExecuteHeavySkillLoopGuardSkills.isEmpty()) {
            return false;
        }
        return matchesConfiguredSkill(skillName, preExecuteHeavySkillLoopGuardSkills);
    }

    private boolean matchesConfiguredSkill(String skillName, Set<String> configuredSkills) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        if (configuredSkills == null || configuredSkills.isEmpty()) {
            return true;
        }
        String normalized = skillName.trim().toLowerCase(Locale.ROOT);
        for (String configured : configuredSkills) {
            if (configured == null || configured.isBlank()) {
                continue;
            }
            String candidate = configured.trim().toLowerCase(Locale.ROOT);
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

    private boolean isCodeGenerationIntent(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        boolean hasCodeCue = containsAny(normalized,
                "generate code",
                "code ",
                "写代码",
                "生成代码",
                "代码实现",
                "代码示例",
                "写个函数",
                "实现一个",
                "java代码",
                "python代码",
                "sql",
                "接口",
                "api",
                "bug",
                "debug",
                "修复");
        if (!hasCodeCue) {
            return false;
        }
        boolean looksLikeGeneralQuestion = containsAny(normalized,
                "是什么",
                "原理",
                "解释",
                "怎么理解",
                "什么意思",
                "why",
                "what is",
                "explain")
                && !containsAny(normalized, "代码", "函数", "class", "method", "api", "bug", "修复");
        return !looksLikeGeneralQuestion;
    }

    private SkillResult enrichMemoryHabitResult(SkillResult result,
                                                String routedSkill,
                                                Map<String, Object> profileContext) {
        if (!habitExplainHintEnabled) {
            return result;
        }
        if (result == null || result.output() == null || result.output().isBlank()) {
            return result;
        }
        StringBuilder hint = new StringBuilder("[自动调度] 已按历史习惯调用 skill: ")
                .append(routedSkill);
        if (preferenceReuseEnabled && profileContext != null && !profileContext.isEmpty()) {
            hint.append("，并复用用户偏好");
        }
        String output = hint + "\n" + result.output();
        return new SkillResult(result.skillName(), output, result.success());
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

    private boolean isTeachingPlanIntent(String normalized) {
        return containsAny(normalized,
                "教学规划",
                "学习计划",
                "复习计划",
                "课程规划",
                "学习路线",
                "study plan",
                "teaching plan");
    }

    private LlmDetectionResult detectSkillWithLlm(String userId,
                                                  String userInput,
                                                  String memoryContext,
                                                  SkillContext skillContext,
                                                  Map<String, Object> profileContext) {
        String normalizedInput = normalize(userInput);
        if (llmRoutingConversationalBypassEnabled && isConversationalBypassInput(normalizedInput)) {
            return LlmDetectionResult.empty();
        }
        String knownSkills = skillRoutingSupport.describeSkillRoutingCandidates(userId, userInput);
        if (knownSkills.isBlank()) {
            return LlmDetectionResult.empty();
        }
        String prompt = "You are a dispatcher. Decide whether a skill is needed. "
                + "Return ONLY JSON with schema {\"intent\":\"name\",\"target\":\"skill-or-tool\",\"params\":{},\"confidence\":0.0,\"requireClarify\":false} or NONE.\n"
                + "Only choose from these candidate skills: " + capText(knownSkills, 800) + ".\n"
                + "Context:\n" + capText(buildLlmDslMemoryContext(memoryContext, profileContext), llmDslMemoryContextMaxChars) + "\n"
                + "User input:\n" + capText(userInput, 400);
        prompt = capText(prompt, promptMaxChars);

        Map<String, Object> llmContext = new LinkedHashMap<>();
        llmContext.put("userId", userId);
        llmContext.put("memoryContext", buildLlmDslMemoryContext(memoryContext, profileContext));
        llmContext.put("input", userInput);
        llmContext.put("routeStage", "llm-dsl");
        applyStageLlmRoute("llm-dsl", profileContext, llmContext);
        List<Map<String, Object>> chatHistory = activeDispatcherMemoryFacade().buildChatHistory(userId);
        if (!chatHistory.isEmpty()) {
            llmContext.put("chatHistory", chatHistory);
        }
        String llmReply = callLlmWithLocalEscalation(prompt, Map.copyOf(llmContext));
        if (llmReply == null || llmReply.isBlank() || "NONE".equalsIgnoreCase(llmReply.trim())) {
            return LlmDetectionResult.empty();
        }
        Optional<Decision> decision = decisionParser.parse(llmReply);
        if (decision.isPresent()) {
            DecisionOrchestrator.OrchestrationOutcome outcome = decisionOrchestrator.orchestrate(
                    decision.get(),
                    new DecisionOrchestrator.OrchestrationRequest(userId, userInput, skillContext, profileContext)
            );
            if (outcome.hasClarification()) {
                return new LlmDetectionResult(Optional.empty(), Optional.empty(), Optional.of(outcome.clarification()), Optional.ofNullable(outcome.trace()), outcome.usedFallback());
            }
            if (outcome.hasResult()) {
                if (!outcome.result().success()) {
                    return new LlmDetectionResult(Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(outcome.trace()), outcome.usedFallback());
                }
                if (shouldRejectCodeGenerate(userInput, outcome.result().skillName())) {
                    return new LlmDetectionResult(Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(outcome.trace()), outcome.usedFallback());
                }
                return new LlmDetectionResult(Optional.of(outcome.result()), Optional.ofNullable(outcome.skillDsl()), Optional.empty(), Optional.ofNullable(outcome.trace()), outcome.usedFallback());
            }
            if (outcome.hasSkillDsl()) {
                if (shouldRejectCodeGenerate(userInput, outcome.skillDsl().skill())) {
                    return new LlmDetectionResult(Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(outcome.trace()), outcome.usedFallback());
                }
                return new LlmDetectionResult(Optional.empty(), Optional.of(outcome.skillDsl()), Optional.empty(), Optional.ofNullable(outcome.trace()), outcome.usedFallback());
            }
        }
        if (!llmReply.trim().startsWith("{")) {
            return LlmDetectionResult.empty();
        }
        return LlmDetectionResult.empty();
    }

    private boolean shouldRejectCodeGenerate(String userInput, String skillName) {
        return "code.generate".equals(skillName)
                && !isCodeGenerationIntent(userInput)
                && !behaviorRoutingSupport.isContinuationOnlyInput(userInput);
    }

    private Optional<SkillResult> answerMetaQuestion(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return Optional.empty();
        }

        String normalized = normalize(userInput);
        if (isLearnableSkillsQuestion(normalized)) {
            return Optional.of(SkillResult.success(SKILL_HELP_CHANNEL, buildLearnableSkillsReply()));
        }
        if (isAvailableSkillsQuestion(normalized)) {
            return Optional.of(SkillResult.success(SKILL_HELP_CHANNEL, buildAvailableSkillsReply()));
        }
        return Optional.empty();
    }

    private boolean isAvailableSkillsQuestion(String normalized) {
        return containsAny(normalized,
                "你有哪些技能",
                "你有什么技能",
                "你会什么",
                "你能做什么",
                "你可以做什么",
                "你有什么能力",
                "支持哪些技能",
                "有哪些技能",
                "skill list",
                "list skills",
                "show skills",
                "available skills",
                "what skills do you have",
                "what can you do");
    }

    private boolean isLearnableSkillsQuestion(String normalized) {
        return containsAny(normalized,
                "可以学习哪些技能",
                "能学习哪些技能",
                "还能学习什么技能",
                "还可以学习哪些技能",
                "你能学什么",
                "你可以学什么",
                "怎么学习新技能",
                "怎么添加新技能",
                "怎么扩展技能",
                "what skills can you learn",
                "can you learn new skills",
                "how can you learn new skills",
                "add new skills",
                "learn new skills");
    }

    private boolean containsAny(String normalized, String... phrases) {
        for (String phrase : phrases) {
            if (normalized.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private String buildAvailableSkillsReply() {
        List<String> skills = skillEngine.listAvailableSkillSummaries();
        if (skills.isEmpty()) {
            return "我现在还没有注册任何技能。你可以稍后让我重载自定义技能，或者接入 MCP / 外部 JAR 来扩展能力。";
        }

        StringBuilder reply = new StringBuilder("我当前可以直接使用这些技能：\n");
        for (String skill : skills) {
            reply.append("- ").append(skill).append('\n');
        }
        reply.append("你可以直接说：“现在几点了”“echo 你好”“帮我做一个六周数学学习计划”“帮我创建一个周五前完成的待办”。如果你想继续扩展能力，也可以问我“你还可以学习哪些技能？”。");
        return reply.toString();
    }

    private String buildLearnableSkillsReply() {
        return "我目前可以通过 3 种方式扩展/学习新技能：\n"
                + "1. 自定义 JSON 技能：把 .json 技能定义放到 mindos.skills.custom-dir，然后重载。\n"
                + "2. MCP 工具技能：配置 mindos.skills.mcp-servers，或运行时接入一个 MCP server。\n"
                + "3. 外部 JAR 技能：加载实现 Skill SPI 的外部 JAR。\n"
                + "如果你愿意，也可以先告诉我你想新增什么能力，我可以帮你判断更适合用哪一种方式。";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", " ");
    }


    private String buildFallbackPrompt(String memoryContext,
                                       PromptMemoryContextDto promptMemoryContext,
                                       String userInput,
                                       boolean realtimeIntentInput) {
        if (shouldApplyRealtimeMemoryShrink(realtimeIntentInput)) {
            return buildRealtimeFallbackPrompt(promptMemoryContext, userInput);
        }
        return capText(promptBuilder.build(promptMemoryContext, userInput), promptMaxChars);
    }

    private SkillResult buildMemoryDirectResult(PromptMemoryContextDto promptMemoryContext, String userInput) {
        List<String> items = promptMemoryContext == null || promptMemoryContext.debugTopItems() == null
                ? List.of()
                : promptMemoryContext.debugTopItems().stream()
                .filter(item -> item != null && item.type() != null && !"episodic".equalsIgnoreCase(item.type()))
                .sorted(Comparator.comparingDouble(com.zhongbo.mindos.assistant.common.dto.RetrievedMemoryItemDto::finalScore).reversed())
                .limit(3)
                .map(item -> item.text() == null ? "" : item.text().replace('\n', ' ').trim())
                .filter(text -> !text.isBlank())
                .toList();
        if (items.isEmpty()) {
            items = List.of("未找到可直接复用的高相关记忆，请补充更多背景。");
        }
        StringBuilder reply = new StringBuilder("根据已有记忆，我先直接回答：");
        for (int i = 0; i < items.size(); i++) {
            reply.append("\n").append(i + 1).append(". ").append(capText(items.get(i), 160));
        }
        if (userInput != null && !userInput.isBlank()) {
            reply.append("\n如需更深入分析，请明确说明你希望我详细推理的部分。");
        }
        return SkillResult.success("memory.direct", capText(reply.toString(), llmReplyMaxChars));
    }

    private String promptMemoryDebugSummary(PromptMemoryContextDto ctx) {
        if (ctx == null || ctx.debugTopItems() == null) {
            return "promptMemoryDebug=empty";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("promptMemory.items=").append(ctx.debugTopItems().size());
        int i = 0;
        for (com.zhongbo.mindos.assistant.common.dto.RetrievedMemoryItemDto item : ctx.debugTopItems()) {
            if (item == null) continue;
            i++;
            sb.append(" |").append(i).append(":type=").append(item.type() == null ? "" : item.type())
                    .append(",final=").append(String.format(Locale.ROOT, "%.3f", item.finalScore()))
                    .append(",recency=").append(String.format(Locale.ROOT, "%.3f", item.recencyScore()))
                    .append(",text=")
                    .append(item.text() == null ? "" : item.text().replace('\n', ' ').trim());
            if (i >= 5) break;
        }
        return sb.toString();
    }

    private QueryContext buildQueryContext(Map<String, Object> llmContext,
                                           String userInput,
                                           PromptMemoryContextDto promptMemoryContext) {
        String userId = llmContext == null ? "" : Objects.toString(llmContext.get("userId"), "");
        return new QueryContext(
                userId,
                userInput,
                promptMemoryContext,
                isExplicitLlmRequest(userInput),
                requiresComplexReasoning(userInput)
        );
    }

    private boolean isExplicitLlmRequest(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        String normalized = userInput.toLowerCase(Locale.ROOT);
        return normalized.contains("调用llm")
                || normalized.contains("调用大模型")
                || normalized.contains("step by step")
                || normalized.contains("请详细分析")
                || normalized.contains("请深入分析");
    }

    private boolean requiresComplexReasoning(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        String normalized = userInput.toLowerCase(Locale.ROOT);
        return normalized.contains("为什么")
                || normalized.contains("比较")
                || normalized.contains("权衡")
                || normalized.contains("tradeoff")
                || normalized.contains("设计方案")
                || normalized.contains("根因")
                || normalized.contains("如何设计");
    }

    private boolean shouldApplyRealtimeMemoryShrink(boolean realtimeIntentInput) {
        return realtimeIntentInput && realtimeIntentMemoryShrinkEnabled;
    }

    private String buildRealtimeFallbackPrompt(PromptMemoryContextDto promptMemoryContext, String userInput) {
        StringBuilder prompt = new StringBuilder("请使用中文简要回答，优先使用最新事实并避免陈旧假设。\n");
        if (realtimeIntentMemoryShrinkIncludePersona && promptMemoryContext != null
                && promptMemoryContext.personaSnapshot() != null
                && !promptMemoryContext.personaSnapshot().isEmpty()) {
            prompt.append("Persona:\n")
                    .append(capText(promptMemoryContext.personaSnapshot().toString(), realtimeIntentMemoryShrinkMaxChars / 2))
                    .append('\n');
        }
        prompt.append("User input: ")
                .append(capText(userInput, 400));
        return capText(prompt.toString(), Math.min(promptMaxChars, realtimeIntentMemoryShrinkMaxChars + 220));
    }

    private String buildStructuredMemoryPromptContext(PromptMemoryContextDto promptMemoryContext) {
        if (promptMemoryContext == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendContextSection(builder, "Structured recent conversation", promptMemoryContext.recentConversation(), memoryContextMaxChars / 3);
        appendContextSection(builder, "Structured semantic memory", promptMemoryContext.semanticContext(), memoryContextMaxChars / 3);
        appendContextSection(builder, "Structured procedural hints", promptMemoryContext.proceduralHints(), memoryContextMaxChars / 3);
        if (promptMemoryContext.personaSnapshot() != null && !promptMemoryContext.personaSnapshot().isEmpty()) {
            appendContextSection(builder,
                    "Structured persona",
                    promptMemoryContext.personaSnapshot().toString(),
                    Math.max(80, memoryContextMaxChars / 6));
        }
        return builder.toString();
    }

    private boolean isSkillLoopGuardBlocked(String userId, String skillName, String userInput) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        // Read-only search skills are expected to be repeated for fresh results, so do not
        // treat them as loop candidates. This keeps news / web search requests routable.
        if (normalize(skillName).contains("search")) {
            return false;
        }
        List<ProceduralMemoryEntry> history = activeDispatcherMemoryFacade().getSkillUsageHistory(userId);
        if (isConsecutiveSkillLoop(history, skillName)) {
            return true;
        }
        return isRepeatedInputLoop(history, skillName, userInput);
    }

    private boolean isConsecutiveSkillLoop(List<ProceduralMemoryEntry> history, String skillName) {
        int consecutive = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            ProceduralMemoryEntry entry = history.get(i);
            if (!entry.success() || !skillName.equals(entry.skillName())) {
                break;
            }
            consecutive++;
            if (consecutive > skillGuardMaxConsecutive) {
                return true;
            }
        }
        return false;
    }

    private boolean isRepeatedInputLoop(List<ProceduralMemoryEntry> history, String skillName, String userInput) {
        if (skillGuardCooldownSeconds <= 0L) {
            return false;
        }
        String fingerprint = loopGuardFingerprint(userInput);
        if (fingerprint.isBlank()) {
            return false;
        }
        Instant now = Instant.now();
        int scanned = 0;
        int repeatedWithinCooldown = 0;
        for (int i = history.size() - 1; i >= 0 && scanned < skillGuardRecentWindowSize; i--) {
            ProceduralMemoryEntry entry = history.get(i);
            scanned++;
            if (!entry.success() || !skillName.equals(entry.skillName())) {
                continue;
            }
            if (entry.createdAt() != null) {
                long ageSeconds = Math.max(0L, Duration.between(entry.createdAt(), now).getSeconds());
                if (ageSeconds > skillGuardCooldownSeconds) {
                    continue;
                }
            }
            if (fingerprint.equals(loopGuardFingerprint(entry.input()))) {
                repeatedWithinCooldown++;
                if (repeatedWithinCooldown >= skillGuardRepeatInputThreshold) {
                    return true;
                }
            }
        }
        return false;
    }

    private String loopGuardFingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return normalize(behaviorRoutingSupport.sanitizeContinuationPrefix(value));
    }

    private Optional<SkillResult> maybeBlockByCapability(String skillName) {
        if (skillName == null || skillName.isBlank() || skillCapabilityPolicy.isAllowed(skillName)) {
            return Optional.empty();
        }
        String message = "安全策略已阻止 skill 执行: " + skillName
                + "，缺少能力权限: " + skillCapabilityPolicy.missingCapabilities(skillName);
        LOGGER.warning("Dispatcher guard=capability-deny, skill=" + skillName
                + ", missing=" + skillCapabilityPolicy.missingCapabilities(skillName));
        return Optional.of(SkillResult.success("security.guard", message));
    }

    private boolean isPromptInjectionAttempt(String userInput) {
        if (!promptInjectionGuardEnabled || userInput == null || userInput.isBlank()) {
            return false;
        }
        String normalized = normalize(userInput).toLowerCase(Locale.ROOT);
        for (String term : promptInjectionRiskTerms) {
            if (normalized.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private List<String> parseRiskTerms(String rawTerms) {
        if (rawTerms == null || rawTerms.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rawTerms.split(","))
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .toList();
    }

    private String normalizeOptionalConfig(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void applyStageLlmRoute(String stage,
                                    Map<String, Object> profileContext,
                                    Map<String, Object> llmContext) {
        if (llmContext == null) {
            return;
        }
        String profileProvider = profileContext == null ? null : asString(profileContext.get("llmProvider"));
        String profilePreset = profileContext == null ? null : asString(profileContext.get("llmPreset"));
        String profileModel = profileContext == null ? null : asString(profileContext.get("llmModel"));

        String provider = profileProvider;
        String preset = profilePreset;
        String model = profileModel;
        if ("llm-dsl".equals(stage)) {
            if (provider == null) {
                provider = llmDslProvider;
            }
            if (preset == null) {
                preset = llmDslPreset;
            }
            if (model == null) {
                model = llmDslModel;
            }
        } else if ("llm-fallback".equals(stage)) {
            if (provider == null) {
                provider = llmFallbackProvider;
            }
            if (preset == null) {
                preset = llmFallbackPreset;
            }
            if (model == null) {
                model = llmFallbackModel;
            }
        } else if ("skill-postprocess".equals(stage) && model == null) {
            model = skillFinalizeWithLlmModel;
        }
        if (provider != null) {
            llmContext.put("llmProvider", provider);
        }
        if (preset != null) {
            llmContext.put("llmPreset", preset);
        }
        if (model != null) {
            llmContext.put("model", model);
        }
        if ("llm-dsl".equals(stage) && llmDslMaxTokens > 0) {
            llmContext.put("maxTokens", llmDslMaxTokens);
        }
        if ("llm-fallback".equals(stage) && llmFallbackMaxTokens > 0) {
            llmContext.put("maxTokens", llmFallbackMaxTokens);
        }
        if ("skill-postprocess".equals(stage) && skillFinalizeMaxTokens > 0) {
            llmContext.put("maxTokens", skillFinalizeMaxTokens);
        }
    }

    private String callLlmWithLocalEscalation(String prompt, Map<String, Object> llmContext) {
        boolean localPrimary = isLocalProviderContext(llmContext);
        if (localPrimary) {
            localEscalationAttemptCount.incrementAndGet();
        }
        String resourceGuardReason = detectResourceGuardEscalationReason(llmContext);
        if (resourceGuardReason != null) {
            fallbackChainAttemptCount.incrementAndGet();
            incrementEscalationReason(resourceGuardReason);
            Map<String, Object> escalatedContext = new LinkedHashMap<>(llmContext == null ? Map.of() : llmContext);
            String cloudProvider = resolveEscalationProvider(escalatedContext);
            if (cloudProvider == null) {
                return llmClient.generateResponse(prompt, llmContext);
            }
            escalatedContext.put("llmProvider", cloudProvider);
            if (localEscalationCloudPreset != null) {
                escalatedContext.put("llmPreset", localEscalationCloudPreset);
            }
            if (localEscalationCloudModel != null) {
                escalatedContext.put("model", localEscalationCloudModel);
            }
            escalatedContext.put("localEscalationReason", resourceGuardReason);
            LOGGER.info("Dispatcher route=llm-local-escalation, from=local, to=" + cloudProvider
                    + ", stage=" + asString(escalatedContext.get("routeStage"))
                    + ", reason=" + resourceGuardReason);
            String escalatedReply = llmClient.generateResponse(prompt, escalatedContext);
            if (isSuccessfulLlmReply(escalatedReply)) {
                fallbackChainHitCount.incrementAndGet();
            }
            return escalatedReply;
        }
        String primaryReply = llmClient.generateResponse(prompt, llmContext);
        String reason = detectEscalationReason(primaryReply, llmContext);
        if (reason == null) {
            if (localPrimary && isSuccessfulLlmReply(primaryReply)) {
                localEscalationHitCount.incrementAndGet();
            }
            return primaryReply;
        }
        fallbackChainAttemptCount.incrementAndGet();
        incrementEscalationReason(reason);
        Map<String, Object> escalatedContext = new LinkedHashMap<>(llmContext == null ? Map.of() : llmContext);
        String cloudProvider = resolveEscalationProvider(escalatedContext);
        if (cloudProvider == null) {
            return primaryReply;
        }
        escalatedContext.put("llmProvider", cloudProvider);
        if (localEscalationCloudPreset != null) {
            escalatedContext.put("llmPreset", localEscalationCloudPreset);
        }
        if (localEscalationCloudModel != null) {
            escalatedContext.put("model", localEscalationCloudModel);
        }
        escalatedContext.put("localEscalationReason", reason);
        LOGGER.info("Dispatcher route=llm-local-escalation, from=local, to=" + cloudProvider
                + ", stage=" + asString(escalatedContext.get("routeStage"))
                + ", reason=" + reason);
        String escalatedReply = llmClient.generateResponse(prompt, escalatedContext);
        if (isSuccessfulLlmReply(escalatedReply)) {
            fallbackChainHitCount.incrementAndGet();
        }
        return escalatedReply;
    }

    private String detectEscalationReason(String reply, Map<String, Object> llmContext) {
        if (!localEscalationEnabled || llmContext == null || llmContext.isEmpty()) {
            return null;
        }
        String provider = resolveEscalationSourceProvider(llmContext);
        if (!"local".equals(provider) && !"ollama".equals(provider) && !"gemma".equals(provider)) {
            return null;
        }
        if (isManualEscalationRequested(llmContext)) {
            return "manual";
        }
        String normalizedReply = normalize(reply);
        if (normalizedReply.isBlank()) {
            return "empty_response";
        }
        if (!normalizedReply.startsWith("[llm local]")) {
            return shouldEscalateForQuality(reply, llmContext) ? "quality" : null;
        }
        if (normalizedReply.contains("reason=timeout") || normalizedReply.contains(" timed out") || normalizedReply.contains(" timeout")) {
            return "timeout";
        }
        if (normalizedReply.contains("reason=upstream_5xx")
                || normalizedReply.contains("http_500")
                || normalizedReply.contains("http_502")
                || normalizedReply.contains("http_503")
                || normalizedReply.contains("http_504")) {
            return "upstream_5xx";
        }
        if (normalizedReply.contains("reason=empty_response")
                || normalizedReply.contains("empty_response_content")
                || normalizedReply.contains("empty response")) {
            return "empty_response";
        }
        return null;
    }

    private String detectResourceGuardEscalationReason(Map<String, Object> llmContext) {
        if (!localEscalationEnabled || !localEscalationResourceGuardEnabled || llmContext == null || llmContext.isEmpty()) {
            return null;
        }
        String provider = resolveEscalationSourceProvider(llmContext);
        if (!"local".equals(provider) && !"ollama".equals(provider) && !"gemma".equals(provider)) {
            return null;
        }
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long freeMemory = Math.max(0L, maxMemory - usedMemory);
        long freeMemoryMb = freeMemory / (1024 * 1024);
        double freeMemoryRatio = maxMemory <= 0 ? 1.0 : (double) freeMemory / (double) maxMemory;
        int availableProcessors = runtime.availableProcessors();
        if (freeMemoryMb >= localEscalationResourceGuardMinFreeMemoryMb
                && freeMemoryRatio >= localEscalationResourceGuardMinFreeMemoryRatio
                && availableProcessors >= localEscalationResourceGuardMinAvailableProcessors) {
            return null;
        }
        LOGGER.info("Dispatcher local resource guard triggered: freeMemoryMb=" + freeMemoryMb
                + ", freeMemoryRatio=" + String.format(Locale.ROOT, "%.4f", freeMemoryRatio)
                + ", availableProcessors=" + availableProcessors
                + ", minFreeMemoryMb=" + localEscalationResourceGuardMinFreeMemoryMb
                + ", minFreeMemoryRatio=" + String.format(Locale.ROOT, "%.4f", localEscalationResourceGuardMinFreeMemoryRatio)
                + ", minAvailableProcessors=" + localEscalationResourceGuardMinAvailableProcessors);
        return "resource_guard";
    }

    private boolean isManualEscalationRequested(Map<String, Object> llmContext) {
        String directReason = normalize(asString(llmContext.get("localEscalationReason")));
        if ("manual".equals(directReason)) {
            return true;
        }
        if (isTrue(llmContext.get("forceCloudRetry"))) {
            return true;
        }
        Map<String, Object> profile = asObjectMap(llmContext.get("profile"));
        String profileReason = normalize(asString(profile.get("localEscalationReason")));
        if ("manual".equals(profileReason)) {
            return true;
        }
        return isTrue(profile.get("forceCloudRetry"));
    }

    private boolean shouldEscalateForQuality(String reply, Map<String, Object> llmContext) {
        if (!localEscalationQualityEnabled) {
            return false;
        }
        if (!isSuccessfulLlmReply(reply)) {
            return false;
        }
        String input = asString(llmContext.get("originalInput"));
        if (input == null) {
            input = asString(llmContext.get("input"));
        }
        if (input == null) {
            return false;
        }
        String normalizedInput = normalize(input);
        if (!matchesAnyTerm(normalizedInput, localEscalationQualityInputTerms)) {
            return false;
        }
        String normalizedReply = normalize(reply);
        if (normalizedReply.length() > localEscalationQualityMaxReplyChars) {
            return false;
        }
        return matchesAnyTerm(normalizedReply, localEscalationQualityReplyTerms);
    }

    private boolean matchesAnyTerm(String normalizedText, Set<String> terms) {
        if (normalizedText == null || normalizedText.isBlank() || terms == null || terms.isEmpty()) {
            return false;
        }
        for (String term : terms) {
            if (term != null && !term.isBlank() && normalizedText.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTrue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            String normalized = normalize(text);
            return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized);
        }
        return false;
    }

    private Map<String, Object> asObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            mapped.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return mapped.isEmpty() ? Map.of() : Map.copyOf(mapped);
    }

    private boolean isLocalProviderContext(Map<String, Object> llmContext) {
        if (llmContext == null || llmContext.isEmpty()) {
            return false;
        }
        String provider = resolveEscalationSourceProvider(llmContext);
        return "local".equals(provider) || "ollama".equals(provider) || "gemma".equals(provider);
    }

    private String resolveEscalationSourceProvider(Map<String, Object> llmContext) {
        String provider = normalize(asString(llmContext.get("llmProvider")));
        if (!provider.isBlank()) {
            return provider;
        }
        Map<String, Object> profile = asObjectMap(llmContext.get("profile"));
        String profileProvider = normalize(asString(profile.get("llmProvider")));
        if (!profileProvider.isBlank() && !"auto".equals(profileProvider)) {
            return profileProvider;
        }
        String routeStage = normalize(asString(llmContext.get("routeStage")));
        if ("llm-fallback".equals(routeStage)) {
            return normalize(llmFallbackProvider);
        }
        if ("llm-dsl".equals(routeStage)) {
            return normalize(llmDslProvider);
        }
        return provider;
    }

    private boolean isSuccessfulLlmReply(String reply) {
        if (reply == null || reply.isBlank()) {
            return false;
        }
        return !normalize(reply).startsWith("[llm ");
    }

    private void incrementEscalationReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return;
        }
        escalationReasonCounters.computeIfAbsent(reason, ignored -> new AtomicLong()).incrementAndGet();
    }

    private String buildLlmDslMemoryContext(String memoryContext, Map<String, Object> profileContext) {
        StringBuilder builder = new StringBuilder();
        String semanticIntent = asString(profileContext == null ? null : profileContext.get("semanticIntent"));
        String semanticRewritten = asString(profileContext == null ? null : profileContext.get("semanticRewrittenInput"));
        if (semanticIntent != null || semanticRewritten != null) {
            builder.append("Semantic hint:\n");
            if (semanticIntent != null) {
                builder.append("- intent: ").append(semanticIntent).append('\n');
            }
            if (semanticRewritten != null) {
                builder.append("- rewrittenInput: ").append(semanticRewritten).append('\n');
            }
        }
        List<Map<String, Object>> history = extractRecentChatHistory(profileContext, 2);
        if (!history.isEmpty()) {
            builder.append("Recent chat turns:\n");
            for (Map<String, Object> turn : history) {
                String role = asString(turn.get("role"));
                String content = asString(turn.get("content"));
                if (content == null) {
                    continue;
                }
                builder.append("- ").append(role == null ? "assistant" : role).append(": ")
                        .append(capText(content, 140)).append('\n');
            }
        }
        if (builder.length() == 0) {
            return capText(memoryContext == null ? "" : memoryContext, llmDslMemoryContextMaxChars);
        }
        if (memoryContext != null && !memoryContext.isBlank()) {
            builder.append("Memory summary:\n")
                    .append(capText(memoryContext, Math.max(120, llmDslMemoryContextMaxChars / 2)));
        }
        return capText(builder.toString(), llmDslMemoryContextMaxChars);
    }

    private List<Map<String, Object>> extractRecentChatHistory(Map<String, Object> profileContext, int keepLast) {
        if (profileContext == null || keepLast <= 0) {
            return List.of();
        }
        Object raw = profileContext.get("chatHistory");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, list.size() - keepLast);
        List<Map<String, Object>> turns = new ArrayList<>();
        for (int i = from; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                turns.add(Map.copyOf(normalized));
            }
        }
        return turns.isEmpty() ? List.of() : List.copyOf(turns);
    }

    private String resolveEscalationProvider(Map<String, Object> llmContext) {
        String configured = localEscalationCloudProvider;
        if (configured == null) {
            configured = llmFallbackProvider;
        }
        String normalized = normalize(configured);
        if (normalized.isBlank()
                || "local".equals(normalized)
                || "ollama".equals(normalized)
                || "gemma".equals(normalized)) {
            return null;
        }
        String currentProvider = normalize(asString(llmContext.get("llmProvider")));
        if (normalized.equals(currentProvider)) {
            return null;
        }
        return configured.trim();
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

    private void appendContextSection(StringBuilder builder, String title, String content, int budget) {
        builder.append(title).append(":\n");
        builder.append(capText(content == null || content.isBlank() ? "- none\n" : content, budget));
        if (builder.length() == 0 || builder.charAt(builder.length() - 1) != '\n') {
            builder.append('\n');
        }
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

    private boolean shouldRunSkillPreAnalyze(String userId, String userInput) {
        if ("never".equals(skillPreAnalyzeMode)) {
            return false;
        }
        if ("always".equals(skillPreAnalyzeMode)) {
            return true;
        }
        int confidence = skillRoutingSupport.bestSkillRoutingScore(userId, userInput);
        return confidence >= skillPreAnalyzeConfidenceThreshold;
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

    private boolean isConversationalBypassInput(String normalizedInput) {
        if (normalizedInput == null || normalizedInput.isBlank()) {
            return true;
        }
        if (SMALL_TALK_INPUTS.contains(normalizedInput)) {
            return true;
        }
        return normalizedInput.length() <= 12
                && (normalizedInput.startsWith("谢谢")
                || normalizedInput.startsWith("收到")
                || normalizedInput.startsWith("好的")
                || normalizedInput.startsWith("hello")
                || normalizedInput.startsWith("thanks"));
    }

    private boolean shouldSkipSemanticAnalysis(String userInput) {
        if (!semanticAnalysisSkipShortSimpleEnabled) {
            return false;
        }
        return isConversationalBypassInput(normalize(userInput));
    }

    private DispatchResult handleConversationalBypass(String userId, String normalizedInput) {
        String reply = "";
        if (normalizedInput == null || normalizedInput.isBlank()) {
            reply = "";
        } else if (normalizedInput.startsWith("谢谢") || normalizedInput.startsWith("多谢") || normalizedInput.startsWith("thanks")) {
            reply = "不客气";
        } else if (normalizedInput.startsWith("收到")) {
            reply = "已收到";
        } else if (normalizedInput.startsWith("好的") || normalizedInput.equals("好") || normalizedInput.startsWith("ok") || normalizedInput.startsWith("okay")) {
            reply = "好的";
        } else if (normalizedInput.startsWith("hi") || normalizedInput.startsWith("hello") || normalizedInput.startsWith("你好") || normalizedInput.startsWith("嗨") || normalizedInput.startsWith("您好")) {
            reply = "你好！有什么我可以帮你的吗？";
        }
        // Persist assistant reply to conversation history for consistency
        dispatchMemoryLifecycle.recordAssistantReply(userId, reply);
        RoutingDecisionDto decision = new RoutingDecisionDto(
                "conversational-bypass",
                "conversational-bypass",
                1.0,
                List.of("short conversational input bypassed routing"),
                List.of()
        );
        ExecutionTraceDto trace = new ExecutionTraceDto("single-pass", 0, null, List.of(), decision);
        return new DispatchResult(reply == null ? "" : reply, "conversational-bypass", trace);
    }

    private boolean isRealtimeIntent(String userInput) {
        return isRealtimeIntent(userInput, SemanticAnalysisResult.empty());
    }

    private boolean isRealtimeIntent(String userInput, SemanticAnalysisResult semanticAnalysis) {
        return realtimeIntentBypassEnabled && RealtimeIntentHeuristics.isRealtimeIntent(userInput, realtimeIntentTerms, semanticAnalysis);
    }

    /**
     * Realtime-like intent detector that is not gated by realtimeIntentBypassEnabled.
     * Used for safe fallback guards to avoid memory.direct responses for weather/news lookups.
     */
    private boolean isRealtimeLikeInput(String userInput) {
        return isRealtimeLikeInput(userInput, SemanticAnalysisResult.empty());
    }

    private boolean isRealtimeLikeInput(String userInput, SemanticAnalysisResult semanticAnalysis) {
        return RealtimeIntentHeuristics.isRealtimeLikeInput(userInput, realtimeIntentTerms, semanticAnalysis);
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

    private record SkillFinalizeOutcome(SkillResult result, boolean applied) {
        private static SkillFinalizeOutcome notApplied(SkillResult result) {
            return new SkillFinalizeOutcome(result, false);
        }

        private static SkillFinalizeOutcome applied(SkillResult result) {
            return new SkillFinalizeOutcome(result, true);
        }
    }

    private boolean isKnownSkillName(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        return skillEngine.listAvailableSkillSummaries().stream()
                .map(summary -> {
                    int separator = summary.indexOf(" - ");
                    return separator >= 0 ? summary.substring(0, separator).trim() : summary.trim();
                })
                .anyMatch(skillName::equals);
    }

    private int scoreNewsDomainRelevance(String line) {
        String normalized = normalize(line);
        if (normalized.isBlank()) {
            return 0;
        }
        int score = 0;
        for (String keyword : NEWS_DOMAIN_WHITELIST_TERMS) {
            if (normalized.contains(keyword)) {
                score += 2;
            }
        }
        for (String keyword : WEATHER_DOMAIN_PENALTY_TERMS) {
            if (normalized.contains(keyword)) {
                score -= 3;
            }
        }
        return score;
    }

    private record RankedHeadlineCandidate(String text, int score, int sourceOrder) {
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

    private String clip(String value) {
        if (value == null) {
            return "null";
        }
        int max = 240;
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
