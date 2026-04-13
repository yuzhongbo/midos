package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.RoutingDecisionDto;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionPlanner;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import com.zhongbo.mindos.assistant.skill.SkillCandidate;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;
import java.util.regex.Pattern;

final class DispatchRoutingPipeline {

    private static final Logger LOGGER = Logger.getLogger(DispatchRoutingPipeline.class.getName());
    private static final Pattern ROUTING_TOKEN_SPLIT_PATTERN = Pattern.compile("[^\\p{L}\\p{N}.#_-]+");
    private static final List<String> DEFAULT_SEARCH_PRIORITY_ORDER = List.of(
            "mcp.qwensearch.websearch",
            "mcp.serper.websearch",
            "mcp.serpapi.websearch",
            "mcp.bravesearch.websearch"
    );
    private static final List<String> DEFAULT_BRAVE_FIRST_PRIORITY_ORDER = List.of(
            "mcp.bravesearch.websearch",
            "mcp.qwensearch.websearch",
            "mcp.serper.websearch",
            "mcp.serpapi.websearch"
    );

    private final SkillCatalogFacade skillEngine;
    private final SkillDslParser skillDslParser;
    private final BehaviorRoutingSupport behaviorRoutingSupport;
    private final DispatchRuleCatalog dispatchRuleCatalog;
    private final SemanticRoutingSupport semanticRoutingSupport;
    private final DecisionPlanner decisionPlanner;
    private final DecisionOrchestrator decisionOrchestrator;
    private final DecisionParamAssembler decisionParamAssembler;
    private final RoutingBridge bridge;
    private final boolean braveFirstSearchRoutingEnabled;
    private final boolean parallelDetectedSkillRoutingEnabled;
    private final int parallelDetectedSkillRoutingMaxCandidates;
    private final long parallelDetectedSkillRoutingTimeoutMs;
    private final List<String> parallelSearchPriorityOrder;
    private final double semanticAnalysisRouteMinConfidence;
    private final Set<String> skillPreAnalyzeSkipSkills;
    private final AtomicLong skillPreAnalyzeRequestCount;
    private final AtomicLong skillPreAnalyzeExecutedCount;
    private final AtomicLong skillPreAnalyzeAcceptedCount;
    private final AtomicLong skillPreAnalyzeSkippedByGateCount;
    private final AtomicLong skillPreAnalyzeSkippedBySkillCount;
    private final AtomicLong detectedSkillLoopSkipBlockedCount;
    private final List<RoutingStage> routingStages;

    DispatchRoutingPipeline(SkillCatalogFacade skillEngine,
                            SkillDslParser skillDslParser,
                             BehaviorRoutingSupport behaviorRoutingSupport,
                             DispatchRuleCatalog dispatchRuleCatalog,
                             SemanticRoutingSupport semanticRoutingSupport,
                             DecisionPlanner decisionPlanner,
                             DecisionOrchestrator decisionOrchestrator,
                             DecisionParamAssembler decisionParamAssembler,
                            RoutingBridge bridge,
                             boolean braveFirstSearchRoutingEnabled,
                             boolean parallelDetectedSkillRoutingEnabled,
                             int parallelDetectedSkillRoutingMaxCandidates,
                             long parallelDetectedSkillRoutingTimeoutMs,
                             List<String> parallelSearchPriorityOrder,
                             double semanticAnalysisRouteMinConfidence,
                             Set<String> skillPreAnalyzeSkipSkills,
                            AtomicLong skillPreAnalyzeRequestCount,
                            AtomicLong skillPreAnalyzeExecutedCount,
                            AtomicLong skillPreAnalyzeAcceptedCount,
                            AtomicLong skillPreAnalyzeSkippedByGateCount,
                            AtomicLong skillPreAnalyzeSkippedBySkillCount,
                            AtomicLong detectedSkillLoopSkipBlockedCount) {
        this.skillEngine = skillEngine;
        this.skillDslParser = skillDslParser;
        this.behaviorRoutingSupport = behaviorRoutingSupport;
        this.dispatchRuleCatalog = dispatchRuleCatalog;
        this.semanticRoutingSupport = semanticRoutingSupport;
        this.decisionPlanner = decisionPlanner;
        this.decisionOrchestrator = decisionOrchestrator;
        this.decisionParamAssembler = decisionParamAssembler;
        this.bridge = bridge;
        this.braveFirstSearchRoutingEnabled = braveFirstSearchRoutingEnabled;
        this.parallelDetectedSkillRoutingEnabled = parallelDetectedSkillRoutingEnabled;
        this.parallelDetectedSkillRoutingMaxCandidates = parallelDetectedSkillRoutingMaxCandidates;
        this.parallelDetectedSkillRoutingTimeoutMs = parallelDetectedSkillRoutingTimeoutMs;
        this.parallelSearchPriorityOrder = resolveSearchPriorityOrder(
                parallelSearchPriorityOrder,
                braveFirstSearchRoutingEnabled || parallelDetectedSkillRoutingEnabled
        );
        this.semanticAnalysisRouteMinConfidence = semanticAnalysisRouteMinConfidence;
        this.skillPreAnalyzeSkipSkills = skillPreAnalyzeSkipSkills == null ? Set.of() : Set.copyOf(skillPreAnalyzeSkipSkills);
        this.skillPreAnalyzeRequestCount = skillPreAnalyzeRequestCount;
        this.skillPreAnalyzeExecutedCount = skillPreAnalyzeExecutedCount;
        this.skillPreAnalyzeAcceptedCount = skillPreAnalyzeAcceptedCount;
        this.skillPreAnalyzeSkippedByGateCount = skillPreAnalyzeSkippedByGateCount;
        this.skillPreAnalyzeSkippedBySkillCount = skillPreAnalyzeSkippedBySkillCount;
        this.detectedSkillLoopSkipBlockedCount = detectedSkillLoopSkipBlockedCount;
        this.routingStages = List.of(
                new ExplicitRoutingStage(),
                new SemanticRoutingStage(),
                new RuleRoutingStage(),
                new MetaHelpRoutingStage(),
                new RealtimeSearchRoutingStage(),
                new DetectedOrHabitRoutingStage(),
                new LlmPreAnalyzeRoutingStage()
        );
    }

    private List<String> resolveSearchPriorityOrder(List<String> configuredOrder,
                                                    boolean preferBraveSearch) {
        List<String> configured = configuredOrder == null
                ? List.of()
                : configuredOrder.stream()
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .toList();
        if (!configured.isEmpty()) {
            return configured;
        }
        return preferBraveSearch
                ? DEFAULT_BRAVE_FIRST_PRIORITY_ORDER
                : DEFAULT_SEARCH_PRIORITY_ORDER;
    }

    CompletableFuture<RoutingOutcome> routeToSkillAsync(String userId,
                                                        String userInput,
                                                        SkillContext context,
                                                        String memoryContext,
                                                        SemanticAnalysisResult semanticAnalysis,
                                                        RoutingReplayProbe replayProbe) {
        List<String> rejectedReasons = new ArrayList<>();
        SignalCollector signals = new SignalCollector();
        RoutingStageRequest request = new RoutingStageRequest(
                userId,
                userInput,
                context,
                memoryContext,
                semanticAnalysis,
                replayProbe
        );
        for (RoutingStage stage : routingStages) {
            Optional<CompletableFuture<RoutingOutcome>> routed = stage.route(request, rejectedReasons, signals);
            if (routed.isPresent()) {
                return routed.get();
            }
        }
        Optional<PlannedSignalRoute> selected = selectSignalProposal(userId, userInput, context, signals);
        if (selected.isPresent()) {
            PlannedSignalRoute proposal = selected.get();
            LOGGER.info("Dispatcher route=" + proposal.routeName() + ", userId=" + userId + ", skill=" + proposal.signal().target());
            return executeDecisionRoute(
                    proposal.decision(),
                    userId,
                    userInput,
                    context,
                    proposal.routeName(),
                    proposal.signal().score(),
                    proposal.acceptedReasons(),
                    rejectedReasons,
                    proposal.resultTransformer()
            );
        }
        return CompletableFuture.completedFuture(new RoutingOutcome(Optional.empty(), fallbackRoutingDecision(rejectedReasons)));
    }

    private interface RoutingStage {
        Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request,
                                                          List<String> rejectedReasons,
                                                          SignalCollector signals);
    }

    private record RoutingStageRequest(String userId,
                                       String userInput,
                                       SkillContext context,
                                       String memoryContext,
                                       SemanticAnalysisResult semanticAnalysis,
                                       RoutingReplayProbe replayProbe) {
    }

    private final class ExplicitRoutingStage implements RoutingStage {
        @Override
        public Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request,
                                                                 List<String> rejectedReasons,
                                                                 SignalCollector signals) {
            return routeExplicitStage(request.userId(), request.userInput(), request.context(), rejectedReasons, signals);
        }
    }

    private final class SemanticRoutingStage implements RoutingStage {
        @Override
        public Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request,
                                                                 List<String> rejectedReasons,
                                                                 SignalCollector signals) {
            return routeSemanticStage(
                    request.userId(),
                    request.userInput(),
                    request.context(),
                    request.semanticAnalysis(),
                    rejectedReasons,
                    signals
            );
        }
    }

    private final class RuleRoutingStage implements RoutingStage {
        @Override
        public Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request,
                                                                 List<String> rejectedReasons,
                                                                 SignalCollector signals) {
            return routeRuleStage(
                    request.userId(),
                    request.userInput(),
                    request.context(),
                    request.replayProbe(),
                    rejectedReasons,
                    signals
            );
        }
    }

    private final class MetaHelpRoutingStage implements RoutingStage {
        @Override
        public Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request,
                                                                 List<String> rejectedReasons,
                                                                 SignalCollector signals) {
            return routeMetaHelpStage(request.userId(), request.userInput(), rejectedReasons);
        }
    }

    private final class RealtimeSearchRoutingStage implements RoutingStage {
        @Override
        public Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request,
                                                                 List<String> rejectedReasons,
                                                                 SignalCollector signals) {
            return routeRealtimeSearchStage(
                    request.userId(),
                    request.userInput(),
                    request.context(),
                    request.semanticAnalysis(),
                    rejectedReasons,
                    signals
            );
        }
    }

    private final class DetectedOrHabitRoutingStage implements RoutingStage {
        @Override
        public Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request,
                                                                 List<String> rejectedReasons,
                                                                 SignalCollector signals) {
            return routeDetectedOrHabitStage(
                    request.userId(),
                    request.userInput(),
                    request.context(),
                    request.semanticAnalysis(),
                    rejectedReasons,
                    signals
            );
        }
    }

    private final class LlmPreAnalyzeRoutingStage implements RoutingStage {
        @Override
        public Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request,
                                                                 List<String> rejectedReasons,
                                                                 SignalCollector signals) {
            if (!signals.isEmpty()) {
                return Optional.empty();
            }
            return routeViaLlmPreAnalyze(
                    request.userId(),
                    request.userInput(),
                    request.context(),
                    request.memoryContext(),
                    request.semanticAnalysis(),
                    request.replayProbe(),
                    rejectedReasons,
                    signals
            );
        }
    }

    private Optional<CompletableFuture<RoutingOutcome>> routeExplicitStage(String userId,
                                                                           String userInput,
                                                                           SkillContext context,
                                                                           List<String> rejectedReasons,
                                                                           SignalCollector signals) {
        Optional<SkillDsl> explicitDsl = skillDslParser.parse(userInput);
        if (explicitDsl.isEmpty()) {
            rejectedReasons.add("no explicit SkillDSL detected");
            return Optional.empty();
        }
        if (bridge.isSkillPreExecuteGuardBlocked(userId, explicitDsl.get().skill(), userInput)) {
            return Optional.of(loopBlockedRoute(userId, explicitDsl.get().skill(), "explicit skill blocked by loop guard before execution", rejectedReasons));
        }
        Optional<RoutingOutcome> blocked = capabilityBlockedRoute(
                explicitDsl.get().skill(),
                1.0,
                "explicit skill DSL requested but capability guard blocked execution",
                rejectedReasons
        );
        if (blocked.isPresent()) {
            return Optional.of(CompletableFuture.completedFuture(blocked.get()));
        }
        signals.add(
                new DecisionSignal(explicitDsl.get().skill(), 1.0, "explicit"),
                "explicit-dsl",
                List.of("input parsed as explicit SkillDSL")
        );
        return Optional.empty();
    }

    private Optional<CompletableFuture<RoutingOutcome>> routeSemanticStage(String userId,
                                                                           String userInput,
                                                                           SkillContext context,
                                                                           SemanticAnalysisResult semanticAnalysis,
                                                                           List<String> rejectedReasons,
                                                                           SignalCollector signals) {
        List<SemanticRoutingSupport.SemanticRoutingPlan> semanticPlans =
                semanticRoutingSupport.recommendSemanticRoutingPlans(userId, semanticAnalysis, userInput);
        SemanticRoutingSupport.SemanticRoutingPlan primarySemanticPlan = semanticPlans.stream()
                .findFirst()
                .orElse(SemanticRoutingSupport.SemanticRoutingPlan.empty());
        boolean continuationIntent = behaviorRoutingSupport.isContinuationIntent(normalize(context.input()));
        if (semanticRoutingSupport.shouldAskSemanticClarification(semanticAnalysis, context.input(), primarySemanticPlan)) {
            String clarifyReply = semanticRoutingSupport.buildSemanticClarifyReply(semanticAnalysis, primarySemanticPlan);
            LOGGER.info("Dispatcher route=semantic-clarify, userId=" + userId
                    + ", skill=" + primarySemanticPlan.skillName()
                    + ", confidence=" + primarySemanticPlan.confidence());
            return Optional.of(CompletableFuture.completedFuture(new RoutingOutcome(
                    Optional.of(SkillResult.success("semantic.clarify", clarifyReply)),
                    new RoutingDecisionDto(
                        "semantic-clarify",
                            primarySemanticPlan.skillName(),
                            primarySemanticPlan.confidence(),
                            List.of("semantic analysis confidence is low or required parameters are missing, ask for clarification before execution"),
                            List.copyOf(rejectedReasons)
                    )
            )));
        }
        if (continuationIntent) {
            rejectedReasons.add("semantic analysis deferred to continuation or habit routing");
            return Optional.empty();
        }
        RoutingOutcome blockedOutcome = null;
        boolean accepted = false;
        for (SemanticRoutingSupport.SemanticRoutingPlan semanticPlan : semanticPlans) {
            if (semanticPlan == null || !semanticPlan.routable()) {
                continue;
            }
            Optional<SkillDsl> semanticDsl = semanticRoutingSupport.toSemanticSkillDsl(semanticPlan);
            if (semanticDsl.isEmpty()) {
                continue;
            }
            Optional<RoutingOutcome> blocked = capabilityBlockedRoute(
                    semanticDsl.get().skill(),
                    0.95,
                    "semantic analysis selected a local skill but capability guard blocked execution",
                    rejectedReasons
            );
            if (blocked.isPresent()) {
                if (blockedOutcome == null) {
                    blockedOutcome = blocked.get();
                }
                continue;
            }
            if (bridge.isSemanticRouteLoopGuardBlocked(userId, semanticDsl.get().skill(), context.input())) {
                rejectedReasons.add("semantic analysis route blocked by loop guard: " + semanticDsl.get().skill());
                continue;
            }
            double confidence = Math.max(semanticAnalysisRouteMinConfidence, semanticPlan.confidence());
            signals.add(
                    new DecisionSignal(semanticDsl.get().skill(), confidence, "semantic"),
                    "semantic-analysis",
                    List.of("semantic analysis suggested a confident local skill route"),
                    UnaryOperator.identity(),
                    semanticPlan.effectivePayload()
            );
            accepted = true;
        }
        if (accepted) {
            return Optional.empty();
        }
        if (blockedOutcome != null) {
            return Optional.of(CompletableFuture.completedFuture(blockedOutcome));
        }
        rejectedReasons.add("semantic analysis did not select a confident local skill");
        return Optional.empty();
    }

    private Optional<CompletableFuture<RoutingOutcome>> routeRuleStage(String userId,
                                                                       String userInput,
                                                                       SkillContext context,
                                                                       RoutingReplayProbe replayProbe,
                                                                       List<String> rejectedReasons,
                                                                       SignalCollector signals) {
        List<DecisionSignal> ruleSignals = dispatchRuleCatalog.recommendFallbackSignals(userInput);
        if (!ruleSignals.isEmpty()
                && "code.generate".equals(ruleSignals.get(0).target())
                && !dispatchRuleCatalog.isCodeGenerationIntent(userInput)
                && !behaviorRoutingSupport.isContinuationOnlyInput(userInput)) {
            rejectedReasons.add("planner fallback code.generate rejected because input does not look like a code task");
            ruleSignals = List.of();
        }
        if (ruleSignals.isEmpty()) {
            replayProbe.setRuleCandidate("NONE");
            rejectedReasons.add("planner did not produce a low-confidence fallback target");
            return Optional.empty();
        }
        DecisionSignal ruleSignal = ruleSignals.get(0);
        if (bridge.isSkillPreExecuteGuardBlocked(userId, ruleSignal.target(), userInput)) {
            return Optional.of(loopBlockedRoute(userId, ruleSignal.target(), "planner fallback skill blocked by loop guard before execution", rejectedReasons));
        }
        replayProbe.setRuleCandidate(ruleSignal.target());
        Optional<RoutingOutcome> blocked = capabilityBlockedRoute(
                ruleSignal.target(),
                ruleSignal.score(),
                "planner fallback route matched but capability guard blocked execution",
                rejectedReasons
        );
        if (blocked.isPresent()) {
            return Optional.of(CompletableFuture.completedFuture(blocked.get()));
        }
        signals.add(
                ruleSignal,
                "rule-fallback",
                List.of("planner produced low-confidence fallback target")
        );
        return Optional.empty();
    }

    private Optional<CompletableFuture<RoutingOutcome>> routeMetaHelpStage(String userId,
                                                                           String userInput,
                                                                           List<String> rejectedReasons) {
        Optional<SkillResult> metaReply = dispatchRuleCatalog.answerMetaQuestion(userInput);
        if (metaReply.isEmpty()) {
            rejectedReasons.add("input is not a meta help question");
            return Optional.empty();
        }
        String channel = metaReply.get().skillName();
        LOGGER.info("Dispatcher route=meta-help, userId=" + userId + ", channel=" + channel);
        return Optional.of(CompletableFuture.completedFuture(new RoutingOutcome(metaReply, new RoutingDecisionDto(
                "meta-help",
                channel,
                0.97,
                List.of("input matched a built-in meta help question"),
                List.copyOf(rejectedReasons)
        ))));
    }

    private Optional<CompletableFuture<RoutingOutcome>> routeRealtimeSearchStage(String userId,
                                                                                 String userInput,
                                                                                 SkillContext context,
                                                                                 SemanticAnalysisResult semanticAnalysis,
                                                                                 List<String> rejectedReasons,
                                                                                 SignalCollector signals) {
        rejectedReasons.add("brave-first routing is disabled; using normal detected-skill routing");
        if (!braveFirstSearchRoutingEnabled || !bridge.isRealtimeIntent(userInput, semanticAnalysis)) {
            return Optional.empty();
        }
        return routeToBraveSearchFirst(userId, userInput, context, semanticAnalysis, rejectedReasons, signals);
    }

    private RoutingOutcome capabilityBlockedOutcome(SkillResult blocked,
                                                    String skillName,
                                                    double confidence,
                                                    String acceptedReason,
                                                    List<String> rejectedReasons) {
        return new RoutingOutcome(
                Optional.ofNullable(blocked),
                new RoutingDecisionDto(
                        "security.guard",
                        skillName,
                        confidence,
                        List.of(acceptedReason),
                        List.copyOf(rejectedReasons)
                )
        );
    }

    private Optional<RoutingOutcome> capabilityBlockedRoute(String skillName,
                                                            double confidence,
                                                            String acceptedReason,
                                                            List<String> rejectedReasons) {
        return bridge.maybeBlockByCapability(skillName)
                .map(blocked -> capabilityBlockedOutcome(blocked, skillName, confidence, acceptedReason, rejectedReasons));
    }

    private CompletableFuture<RoutingOutcome> loopBlockedRoute(String userId,
                                                               String skillName,
                                                               String rejectedReason,
                                                               List<String> rejectedReasons) {
        return loopBlockedRoute(userId, skillName, rejectedReason, rejectedReasons, false);
    }

    private CompletableFuture<RoutingOutcome> loopBlockedRoute(String userId,
                                                               String skillName,
                                                               String rejectedReason,
                                                               List<String> rejectedReasons,
                                                               boolean incrementDetectedLoopMetric) {
        LOGGER.info("Dispatcher guard=loop-skip, userId=" + userId + ", skill=" + skillName);
        if (incrementDetectedLoopMetric) {
            detectedSkillLoopSkipBlockedCount.incrementAndGet();
        }
        rejectedReasons.add(rejectedReason);
        return CompletableFuture.completedFuture(new RoutingOutcome(Optional.empty(), fallbackRoutingDecision(rejectedReasons)));
    }

    private CompletableFuture<RoutingOutcome> executeDecisionRoute(Decision decision,
                                                                   String userId,
                                                                   String userInput,
                                                                   SkillContext context,
                                                                   String routeName,
                                                                   double confidence,
                                                                   List<String> acceptedReasons,
                                                                   List<String> rejectedReasons) {
        return executeDecisionRoute(
                decision,
                userId,
                userInput,
                context,
                routeName,
                confidence,
                acceptedReasons,
                rejectedReasons,
                UnaryOperator.identity()
        );
    }

    private CompletableFuture<RoutingOutcome> executeDecisionRoute(Decision decision,
                                                                   String userId,
                                                                   String userInput,
                                                                   SkillContext context,
                                                                   String routeName,
                                                                   double confidence,
                                                                   List<String> acceptedReasons,
                                                                   List<String> rejectedReasons,
                                                                   UnaryOperator<SkillResult> resultTransformer) {
        if (decision == null || decision.target() == null || decision.target().isBlank()) {
            return CompletableFuture.completedFuture(new RoutingOutcome(Optional.empty(), fallbackRoutingDecision(rejectedReasons)));
        }
        UnaryOperator<SkillResult> transformer = resultTransformer == null ? UnaryOperator.identity() : resultTransformer;
        return CompletableFuture.supplyAsync(() -> {
            DecisionOrchestrator.OrchestrationOutcome outcome = decisionOrchestrator.orchestrate(
                    decision,
                    new DecisionOrchestrator.OrchestrationRequest(
                            userId,
                            userInput,
                            context,
                            decisionParamAssembler.orchestratorProfileContext(context)
                    )
            );
            Optional<SkillResult> routedResult = Optional.empty();
            if (outcome.hasClarification()) {
                routedResult = Optional.of(outcome.clarification());
            } else if (outcome.hasResult()) {
                routedResult = Optional.ofNullable(transformer.apply(outcome.result()));
            }
            String selectedTarget = decisionParamAssembler.resolveSelectedTarget(decision, outcome, routedResult);
            return new RoutingOutcome(
                    routedResult,
                    new RoutingDecisionDto(
                            routeName,
                            selectedTarget,
                            confidence,
                            List.copyOf(acceptedReasons),
                            List.copyOf(rejectedReasons)
                    )
            );
        });
    }

    private Optional<CompletableFuture<RoutingOutcome>> routeDetectedOrHabitStage(String userId,
                                                                                  String userInput,
                                                                                  SkillContext context,
                                                                                  SemanticAnalysisResult semanticAnalysis,
                                                                                  List<String> rejectedReasons,
                                                                                  SignalCollector signals) {
        List<SkillCandidate> detectedSkillCandidates = parallelDetectedSkillRoutingEnabled
                ? skillEngine.detectSkillCandidates(context.input(), parallelDetectedSkillRoutingMaxCandidates)
                : skillEngine.detectSkillName(context.input())
                .map(name -> List.of(new SkillCandidate(name, 1)))
                .orElse(List.of());
        if (!detectedSkillCandidates.isEmpty()) {
            proposeDetectedCandidates(userId, userInput, context, detectedSkillCandidates, rejectedReasons, signals);
            return Optional.empty();
        }

        rejectedReasons.add("no registered skill keyword match");
        boolean realtimeLikeInput = bridge.isRealtimeLikeInput(userInput, semanticAnalysis);
        List<DecisionSignal> habitCandidates = realtimeLikeInput
                ? List.of()
                : behaviorRoutingSupport.recommendSkillsWithMemoryHabits(
                userId,
                userInput,
                context.attributes(),
                skill -> bridge.isSkillLoopGuardBlocked(userId, skill, userInput)
        );
        if (realtimeLikeInput) {
            rejectedReasons.add("realtime-like input skipped memory-habit routing");
        }
        RoutingOutcome blockedOutcome = null;
        boolean accepted = false;
        for (DecisionSignal habitCandidate : habitCandidates) {
            if ("code.generate".equals(habitCandidate.target())
                    && !dispatchRuleCatalog.isCodeGenerationIntent(userInput)
                    && !behaviorRoutingSupport.isContinuationOnlyInput(userInput)) {
                rejectedReasons.add("habit-based code.generate rejected because input does not look like a code task");
                continue;
            }
            if (bridge.isSkillPreExecuteGuardBlocked(userId, habitCandidate.target(), userInput)) {
                rejectedReasons.add("habit-based skill blocked by loop guard before execution: " + habitCandidate.target());
                continue;
            }
            Optional<SkillDsl> habitDsl = behaviorRoutingSupport.buildHabitSkillDsl(
                    userId,
                    userInput,
                    context.attributes(),
                    habitCandidate
            );
            if (habitDsl.isEmpty()) {
                continue;
            }
            Optional<RoutingOutcome> blocked = capabilityBlockedRoute(
                    habitDsl.get().skill(),
                    habitCandidate.score(),
                    "habit route selected but capability guard blocked execution",
                    rejectedReasons
            );
            if (blocked.isPresent()) {
                if (blockedOutcome == null) {
                    blockedOutcome = blocked.get();
                }
                continue;
            }
            String habitSkillName = habitDsl.get().skill();
            signals.add(
                    new DecisionSignal(habitSkillName, habitCandidate.score(), "memory"),
                    "memory-habit",
                    List.of("recent successful skill history matched continuation intent"),
                    result -> bridge.enrichMemoryHabitResult(result, habitSkillName, context.attributes()),
                    habitDsl.get().input() == null ? Map.of() : habitDsl.get().input()
            );
            accepted = true;
        }
        if (accepted) {
            return Optional.empty();
        }
        if (blockedOutcome != null) {
            return Optional.of(CompletableFuture.completedFuture(blockedOutcome));
        }
        rejectedReasons.add("habit route confidence gate not satisfied");
        return Optional.empty();
    }

    private Optional<CompletableFuture<RoutingOutcome>> routeViaLlmPreAnalyze(String userId,
                                                                              String userInput,
                                                                              SkillContext context,
                                                                              String memoryContext,
                                                                              SemanticAnalysisResult semanticAnalysis,
                                                                              RoutingReplayProbe replayProbe,
                                                                              List<String> rejectedReasons,
                                                                              SignalCollector signals) {
        if (behaviorRoutingSupport.isContinuationOnlyInput(userInput)) {
            replayProbe.setPreAnalyzeCandidate("SKIPPED_CONTINUATION");
            return Optional.of(llmFallbackRoute(
                    userId,
                    rejectedReasons,
                    "continuation-only input skipped skill pre-analyze and used llm fallback",
                    "continuation-only"
            ));
        }

        skillPreAnalyzeRequestCount.incrementAndGet();
        if (bridge.isRealtimeIntent(userInput, semanticAnalysis)) {
            replayProbe.setPreAnalyzeCandidate("SKIPPED_REALTIME");
            skillPreAnalyzeSkippedByGateCount.incrementAndGet();
            return Optional.of(llmFallbackRoute(
                    userId,
                    rejectedReasons,
                    "realtime intent bypassed skill pre-analyze after no registered skill matched",
                    "realtime-intent-bypass"
            ));
        }

        if (!bridge.shouldRunSkillPreAnalyze(userId, userInput)) {
            replayProbe.setPreAnalyzeCandidate("SKIPPED_BY_GATE");
            skillPreAnalyzeSkippedByGateCount.incrementAndGet();
            return Optional.of(llmFallbackRoute(
                    userId,
                    rejectedReasons,
                    "skill pre-analyze skipped by mode/threshold gate",
                    "skill-pre-analyze-gate"
            ));
        }

        skillPreAnalyzeExecutedCount.incrementAndGet();
        LlmDetectionResult llmDetection = bridge.detectSkillWithLlm(userId, userInput, memoryContext, context, context.attributes());
        if (llmDetection.directResult().isPresent()) {
            return Optional.of(completedRoutingFuture(
                    Optional.of(llmDetection.directResult().get()),
                    "llm-dsl-clarify",
                    "semantic.clarify",
                    0.4,
                    List.of("params_missing"),
                    rejectedReasons
            ));
        }
        Optional<SkillDsl> llmDsl = llmDetection.skillDsl();
        if (llmDsl.isEmpty() && llmDetection.result().isPresent()) {
            SkillResult orchestrated = llmDetection.result().get();
            if (orchestrated != null && orchestrated.skillName() != null && !orchestrated.skillName().isBlank()) {
                llmDsl = Optional.of(new SkillDsl(orchestrated.skillName(),
                        decisionParamAssembler.decisionParamsFromContext(orchestrated.skillName(), context)));
            }
        }
        if (llmDsl.isPresent()
                && "code.generate".equals(llmDsl.get().skill())
                && !dispatchRuleCatalog.isCodeGenerationIntent(userInput)
                && !behaviorRoutingSupport.isContinuationOnlyInput(userInput)) {
            rejectedReasons.add("LLM-routed code.generate rejected because input does not look like a code task");
            llmDsl = Optional.empty();
        }
        if (llmDsl.isPresent() && skillPreAnalyzeSkipSkills.contains(llmDsl.get().skill())) {
            replayProbe.setPreAnalyzeCandidate("SKIPPED_BY_SKILL");
            skillPreAnalyzeSkippedBySkillCount.incrementAndGet();
            rejectedReasons.add("LLM-routed skill '" + llmDsl.get().skill() + "' is configured to skip pre-analyze routing");
            llmDsl = Optional.empty();
        }
        if (llmDsl.isPresent()) {
            replayProbe.setPreAnalyzeCandidate(llmDsl.get().skill());
            skillPreAnalyzeAcceptedCount.incrementAndGet();
            if (bridge.isSkillPreExecuteGuardBlocked(userId, llmDsl.get().skill(), userInput)) {
                return Optional.of(loopBlockedRoute(userId, llmDsl.get().skill(), "LLM-routed skill blocked by loop guard before execution", rejectedReasons));
            }
            Optional<RoutingOutcome> blocked = capabilityBlockedRoute(
                    llmDsl.get().skill(),
                    0.80,
                    "LLM routing selected a skill but capability guard blocked execution",
                    rejectedReasons
            );
            if (blocked.isPresent()) {
                return Optional.of(CompletableFuture.completedFuture(blocked.get()));
            }
            if (bridge.isSkillLoopGuardBlocked(userId, llmDsl.get().skill(), userInput)) {
                return Optional.of(loopBlockedRoute(userId, llmDsl.get().skill(), "LLM-routed skill blocked by loop guard", rejectedReasons));
            }
            signals.add(
                    new DecisionSignal(llmDsl.get().skill(), 0.76, "llm"),
                    "llm-dsl",
                    List.of("LLM router selected one of the shortlisted candidate skills"),
                    UnaryOperator.identity(),
                    llmDsl.get().input() == null ? Map.of() : llmDsl.get().input()
            );
            return Optional.empty();
        }

        if ("NOT_RUN".equals(replayProbe.preAnalyzeCandidate())) {
            replayProbe.setPreAnalyzeCandidate("NONE");
        }

        return Optional.of(llmFallbackRoute(
                userId,
                rejectedReasons,
                "LLM router returned NONE or no shortlist candidate was selected",
                null
        ));
    }

    private CompletableFuture<RoutingOutcome> llmFallbackRoute(String userId,
                                                               List<String> rejectedReasons,
                                                               String rejectedReason,
                                                               String logReason) {
        rejectedReasons.add(rejectedReason);
        if (logReason == null || logReason.isBlank()) {
            LOGGER.info("Dispatcher route=llm-fallback, userId=" + userId);
        } else {
            LOGGER.info("Dispatcher route=llm-fallback, userId=" + userId + ", reason=" + logReason);
        }
        return completedRoutingFuture(Optional.empty(), fallbackRoutingDecision(rejectedReasons));
    }

    private CompletableFuture<RoutingOutcome> completedRoutingFuture(Optional<SkillResult> result,
                                                                     RoutingDecisionDto decision) {
        return CompletableFuture.completedFuture(new RoutingOutcome(result, decision));
    }

    private CompletableFuture<RoutingOutcome> completedRoutingFuture(Optional<SkillResult> result,
                                                                     String routeName,
                                                                     String selectedSkill,
                                                                     double confidence,
                                                                     List<String> acceptedReasons,
                                                                     List<String> rejectedReasons) {
        return completedRoutingFuture(result, new RoutingDecisionDto(
                routeName,
                selectedSkill,
                confidence,
                List.copyOf(acceptedReasons),
                List.copyOf(rejectedReasons)
        ));
    }

    private void proposeDetectedCandidates(String userId,
                                           String userInput,
                                           SkillContext context,
                                           List<SkillCandidate> detectedCandidates,
                                           List<String> rejectedReasons,
                                           SignalCollector signals) {
        String routeName = detectedCandidates.size() > 1 ? "detected-skill-parallel" : "detected-skill";
        for (SkillCandidate candidate : detectedCandidates) {
            proposeDetectedCandidate(userId, userInput, context, candidate, rejectedReasons, signals, routeName);
        }
    }

    private void proposeDetectedCandidate(String userId,
                                          String userInput,
                                          SkillContext context,
                                          SkillCandidate candidate,
                                          List<String> rejectedReasons,
                                          SignalCollector signals,
                                          String routeName) {
        String skillName = candidate.skillName();
        Optional<SkillResult> blocked = bridge.maybeBlockByCapability(skillName);
        if (blocked.isPresent()) {
            rejectedReasons.add("parallel candidate blocked by capability guard: " + skillName);
            return;
        }
        if (bridge.isSkillLoopGuardBlocked(userId, skillName, userInput)) {
            detectedSkillLoopSkipBlockedCount.incrementAndGet();
            rejectedReasons.add("parallel candidate blocked by loop guard: " + skillName);
            return;
        }
        signals.add(
                new DecisionSignal(skillName, detectedCandidateScore(candidate), "heuristic"),
                routeName,
                List.of("registered skill keywords matched the input")
        );
    }

    private Optional<CompletableFuture<RoutingOutcome>> routeToBraveSearchFirst(String userId,
                                                                                String userInput,
                                                                                SkillContext context,
                                                                                SemanticAnalysisResult semanticAnalysis,
                                                                                List<String> rejectedReasons,
                                                                                SignalCollector signals) {
        if (!braveFirstSearchRoutingEnabled) {
            return Optional.empty();
        }
        if (!bridge.isRealtimeIntent(userInput, semanticAnalysis)) {
            rejectedReasons.add("brave-first routing skipped because input is not realtime intent");
            return Optional.empty();
        }
        List<SkillCandidate> detectedCandidates = skillEngine.detectSkillCandidates(context.input(), Math.max(2, parallelDetectedSkillRoutingMaxCandidates));
        if (detectedCandidates.isEmpty()) {
            rejectedReasons.add("brave-first routing enabled but no realtime search candidates were detected");
            return Optional.empty();
        }
        proposeDetectedCandidates(userId, userInput, context, detectedCandidates, rejectedReasons, signals);
        return Optional.empty();
    }

    private double detectedCandidateScore(SkillCandidate candidate) {
        if (candidate == null) {
            return 0.92;
        }
        int priorityRank = priorityRank(candidate.skillName());
        double priorityBoost = priorityRank == Integer.MAX_VALUE
                ? 0.0
                : Math.max(0, parallelSearchPriorityOrder.size() - priorityRank) * 0.01;
        return 0.92 + priorityBoost + Math.min(Math.max(candidate.score(), 0), 5) * 0.001;
    }

    private int priorityRank(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return Integer.MAX_VALUE;
        }
        String normalized = normalize(skillName);
        for (int i = 0; i < parallelSearchPriorityOrder.size(); i++) {
            if (parallelSearchPriorityOrder.get(i).equals(normalized)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private Optional<PlannedSignalRoute> selectSignalProposal(String userId,
                                                              String userInput,
                                                              SkillContext context,
                                                              SignalCollector signals) {
        if (signals == null || signals.isEmpty()) {
            return Optional.empty();
        }
        if (decisionPlanner == null) {
            return Optional.empty();
        }
        Decision plannedDecision = decisionPlanner.plan(
                new DecisionOrchestrator.UserInput(
                        userId,
                        userInput,
                        context,
                        decisionParamAssembler.orchestratorProfileContext(context)
                ),
                signals.signals()
        );
        if (plannedDecision == null || plannedDecision.target() == null || plannedDecision.target().isBlank()) {
            return Optional.empty();
        }
        return signals.find(plannedDecision.target())
                .map(proposal -> new PlannedSignalRoute(
                        proposal.signal(),
                        mergeDecisionParams(plannedDecision, proposal.paramPatch()),
                        proposal.routeName(),
                        proposal.acceptedReasons(),
                        proposal.resultTransformer()
                ));
    }

    private Decision mergeDecisionParams(Decision decision, Map<String, Object> paramPatch) {
        if (decision == null || paramPatch == null || paramPatch.isEmpty()) {
            return decision;
        }
        Map<String, Object> merged = new LinkedHashMap<>(decision.params() == null ? Map.of() : decision.params());
        merged.putAll(paramPatch);
        return new Decision(
                decision.intent(),
                decision.target(),
                Map.copyOf(merged),
                decision.confidence(),
                decision.requireClarify()
        );
    }

    private RoutingDecisionDto fallbackRoutingDecision(List<String> rejectedReasons) {
        return new RoutingDecisionDto(
                "llm-fallback",
                "llm",
                0.0,
                List.of("no safe skill route satisfied the current request"),
                rejectedReasons == null ? List.of() : List.copyOf(rejectedReasons)
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String value, String... needles) {
        String normalized = normalize(value);
        if (normalized.isBlank() || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && normalized.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private record PlannedSignalRoute(DecisionSignal signal,
                                      Decision decision,
                                      String routeName,
                                      List<String> acceptedReasons,
                                      UnaryOperator<SkillResult> resultTransformer) {
    }

    private static final class SignalCollector {
        private final List<SignalProposal> proposals = new ArrayList<>();

        void add(DecisionSignal signal,
                 String routeName,
                 List<String> acceptedReasons) {
            add(signal, routeName, acceptedReasons, UnaryOperator.identity(), Map.of());
        }

        void add(DecisionSignal signal,
                 String routeName,
                 List<String> acceptedReasons,
                 UnaryOperator<SkillResult> resultTransformer) {
            add(signal, routeName, acceptedReasons, resultTransformer, Map.of());
        }

        void add(DecisionSignal signal,
                 String routeName,
                 List<String> acceptedReasons,
                 UnaryOperator<SkillResult> resultTransformer,
                 Map<String, Object> paramPatch) {
            if (signal == null || signal.target() == null || signal.target().isBlank()) {
                return;
            }
            proposals.add(new SignalProposal(
                    signal,
                    routeName == null ? "" : routeName,
                    acceptedReasons == null ? List.of() : List.copyOf(acceptedReasons),
                    resultTransformer == null ? UnaryOperator.identity() : resultTransformer,
                    paramPatch == null || paramPatch.isEmpty() ? Map.of() : Map.copyOf(paramPatch)
            ));
        }

        boolean isEmpty() {
            return proposals.isEmpty();
        }

        List<DecisionSignal> signals() {
            return proposals.stream().map(SignalProposal::signal).toList();
        }

        Optional<SignalProposal> find(String target) {
            if (target == null || target.isBlank()) {
                return Optional.empty();
            }
            com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionTargetResolver resolver =
                    new com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionTargetResolver();
            String canonicalTarget = resolver.canonicalize(target);
            return proposals.stream()
                    .filter(proposal -> {
                        String proposalTarget = proposal.signal().target();
                        if (target.equals(proposalTarget)) {
                            return true;
                        }
                        String canonicalProposalTarget = resolver.canonicalize(proposalTarget);
                        return !canonicalTarget.isBlank() && canonicalTarget.equals(canonicalProposalTarget);
                    })
                    .sorted(java.util.Comparator.comparingDouble((SignalProposal proposal) -> proposal.signal().score()).reversed())
                    .findFirst();
        }
    }

    private record SignalProposal(DecisionSignal signal,
                                  String routeName,
                                  List<String> acceptedReasons,
                                  UnaryOperator<SkillResult> resultTransformer,
                                  Map<String, Object> paramPatch) {
    }

    interface RoutingBridge {
        Optional<SkillResult> maybeBlockByCapability(String skillName);

        boolean isSkillPreExecuteGuardBlocked(String userId, String skillName, String userInput);

        boolean isSkillLoopGuardBlocked(String userId, String skillName, String userInput);

        boolean isSemanticRouteLoopGuardBlocked(String userId, String skillName, String userInput);

        boolean isRealtimeIntent(String userInput, SemanticAnalysisResult semanticAnalysis);

        boolean isRealtimeLikeInput(String userInput, SemanticAnalysisResult semanticAnalysis);

        boolean shouldRunSkillPreAnalyze(String userId, String userInput);

        LlmDetectionResult detectSkillWithLlm(String userId,
                                              String userInput,
                                              String memoryContext,
                                              SkillContext context,
                                              Map<String, Object> profileContext);

        SkillResult enrichMemoryHabitResult(SkillResult result, String routedSkill, Map<String, Object> profileContext);
    }
}
