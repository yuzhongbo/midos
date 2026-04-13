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
        this.parallelSearchPriorityOrder = parallelSearchPriorityOrder == null
                ? List.of()
                : parallelSearchPriorityOrder.stream()
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .toList();
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

    CompletableFuture<RoutingOutcome> routeToSkillAsync(String userId,
                                                        String userInput,
                                                        SkillContext context,
                                                        String memoryContext,
                                                        SemanticAnalysisResult semanticAnalysis,
                                                        RoutingReplayProbe replayProbe) {
        List<String> rejectedReasons = new ArrayList<>();
        CandidateCollector candidates = new CandidateCollector();
        RoutingStageRequest request = new RoutingStageRequest(
                userId,
                userInput,
                context,
                memoryContext,
                semanticAnalysis,
                replayProbe
        );
        for (RoutingStage stage : routingStages) {
            Optional<CompletableFuture<RoutingOutcome>> routed = stage.route(request, rejectedReasons, candidates);
            if (routed.isPresent()) {
                return routed.get();
            }
        }
        Optional<CandidateProposal> selected = selectCandidateProposal(userInput, context, candidates);
        if (selected.isPresent()) {
            CandidateProposal proposal = selected.get();
            LOGGER.info("Dispatcher route=" + proposal.routeName() + ", userId=" + userId + ", skill=" + proposal.candidate().target());
            return executeDecisionRoute(
                    proposal.decision(),
                    userId,
                    userInput,
                    context,
                    proposal.routeName(),
                    proposal.candidate().score(),
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
                                                          CandidateCollector candidates);
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
                                                                 CandidateCollector candidates) {
            return routeExplicitStage(request.userId(), request.userInput(), request.context(), rejectedReasons, candidates);
        }
    }

    private final class SemanticRoutingStage implements RoutingStage {
        @Override
        public Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request,
                                                                 List<String> rejectedReasons,
                                                                 CandidateCollector candidates) {
            return routeSemanticStage(
                    request.userId(),
                    request.userInput(),
                    request.context(),
                    request.semanticAnalysis(),
                    rejectedReasons,
                    candidates
            );
        }
    }

    private final class RuleRoutingStage implements RoutingStage {
        @Override
        public Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request,
                                                                 List<String> rejectedReasons,
                                                                 CandidateCollector candidates) {
            return routeRuleStage(
                    request.userId(),
                    request.userInput(),
                    request.context(),
                    request.replayProbe(),
                    rejectedReasons,
                    candidates
            );
        }
    }

    private final class MetaHelpRoutingStage implements RoutingStage {
        @Override
        public Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request,
                                                                 List<String> rejectedReasons,
                                                                 CandidateCollector candidates) {
            return routeMetaHelpStage(request.userId(), request.userInput(), rejectedReasons);
        }
    }

    private final class RealtimeSearchRoutingStage implements RoutingStage {
        @Override
        public Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request,
                                                                 List<String> rejectedReasons,
                                                                 CandidateCollector candidates) {
            return routeRealtimeSearchStage(
                    request.userId(),
                    request.userInput(),
                    request.context(),
                    request.semanticAnalysis(),
                    rejectedReasons,
                    candidates
            );
        }
    }

    private final class DetectedOrHabitRoutingStage implements RoutingStage {
        @Override
        public Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request,
                                                                 List<String> rejectedReasons,
                                                                 CandidateCollector candidates) {
            return routeDetectedOrHabitStage(
                    request.userId(),
                    request.userInput(),
                    request.context(),
                    request.semanticAnalysis(),
                    rejectedReasons,
                    candidates
            );
        }
    }

    private final class LlmPreAnalyzeRoutingStage implements RoutingStage {
        @Override
        public Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request,
                                                                 List<String> rejectedReasons,
                                                                 CandidateCollector candidates) {
            if (!candidates.isEmpty()) {
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
                    candidates
            );
        }
    }

    private Optional<CompletableFuture<RoutingOutcome>> routeExplicitStage(String userId,
                                                                           String userInput,
                                                                           SkillContext context,
                                                                           List<String> rejectedReasons,
                                                                           CandidateCollector candidates) {
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
        candidates.add(
                new Candidate(explicitDsl.get().skill(), 1.0, "rule"),
                decisionParamAssembler.toDecision(explicitDsl.get(), context, 1.0),
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
                                                                           CandidateCollector candidates) {
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
            candidates.add(
                    new Candidate(semanticDsl.get().skill(), confidence, "heuristic"),
                    decisionParamAssembler.toDecision(semanticDsl.get(), context, confidence),
                    "semantic-analysis",
                    List.of("semantic analysis suggested a confident local skill route")
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
                                                                       CandidateCollector candidates) {
        Decision plannerDecision = decisionPlanner == null
                ? null
                : decisionPlanner.plan(
                userInput,
                "",
                context == null || context.attributes() == null ? Map.of() : context.attributes(),
                context
        );
        List<Candidate> ruleCandidates = dispatchRuleCatalog.lowConfidenceFallbackCandidates(plannerDecision);
        if (!ruleCandidates.isEmpty()
                && "code.generate".equals(ruleCandidates.get(0).target())
                && !dispatchRuleCatalog.isCodeGenerationIntent(userInput)
                && !behaviorRoutingSupport.isContinuationOnlyInput(userInput)) {
            rejectedReasons.add("planner fallback code.generate rejected because input does not look like a code task");
            ruleCandidates = List.of();
        }
        if (ruleCandidates.isEmpty()) {
            replayProbe.setRuleCandidate("NONE");
            rejectedReasons.add("planner did not produce a low-confidence fallback target");
            return Optional.empty();
        }
        Candidate ruleCandidate = ruleCandidates.get(0);
        if (bridge.isSkillPreExecuteGuardBlocked(userId, ruleCandidate.target(), userInput)) {
            return Optional.of(loopBlockedRoute(userId, ruleCandidate.target(), "planner fallback skill blocked by loop guard before execution", rejectedReasons));
        }
        replayProbe.setRuleCandidate(ruleCandidate.target());
        Optional<RoutingOutcome> blocked = capabilityBlockedRoute(
                ruleCandidate.target(),
                ruleCandidate.score(),
                "planner fallback route matched but capability guard blocked execution",
                rejectedReasons
        );
        if (blocked.isPresent()) {
            return Optional.of(CompletableFuture.completedFuture(blocked.get()));
        }
        candidates.add(
                ruleCandidate,
                plannerDecision,
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
                                                                                 CandidateCollector candidates) {
        rejectedReasons.add("brave-first routing is disabled; using normal detected-skill routing");
        if (!braveFirstSearchRoutingEnabled || !bridge.isRealtimeIntent(userInput, semanticAnalysis)) {
            return Optional.empty();
        }
        return routeToBraveSearchFirst(userId, userInput, context, semanticAnalysis, rejectedReasons, candidates);
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
                                                                                  CandidateCollector candidates) {
        List<SkillCandidate> detectedSkillCandidates = parallelDetectedSkillRoutingEnabled
                ? skillEngine.detectSkillCandidates(context.input(), parallelDetectedSkillRoutingMaxCandidates)
                : skillEngine.detectSkillName(context.input())
                .map(name -> List.of(new SkillCandidate(name, 1)))
                .orElse(List.of());
        if (!detectedSkillCandidates.isEmpty()) {
            proposeDetectedCandidates(userId, userInput, context, detectedSkillCandidates, rejectedReasons, candidates);
            return Optional.empty();
        }

        rejectedReasons.add("no registered skill keyword match");
        boolean realtimeLikeInput = bridge.isRealtimeLikeInput(userInput, semanticAnalysis);
        List<Candidate> habitCandidates = realtimeLikeInput
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
        for (Candidate habitCandidate : habitCandidates) {
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
            candidates.add(
                    new Candidate(habitSkillName, habitCandidate.score(), "memory"),
                    decisionParamAssembler.toDecision(habitDsl.get(), context, habitCandidate.score()),
                    "memory-habit",
                    List.of("recent successful skill history matched continuation intent"),
                    result -> bridge.enrichMemoryHabitResult(result, habitSkillName, context.attributes())
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
                                                                              CandidateCollector candidates) {
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
        if (llmDetection.result().isPresent()) {
            SkillResult orchestrated = llmDetection.result().get();
            if (bridge.isSkillLoopGuardBlocked(userId, orchestrated.skillName(), userInput)) {
                return Optional.of(loopBlockedRoute(userId, orchestrated.skillName(), "LLM decision-orchestrated skill blocked by loop guard", rejectedReasons));
            }
            replayProbe.setPreAnalyzeCandidate(orchestrated.skillName());
            skillPreAnalyzeAcceptedCount.incrementAndGet();
            return Optional.of(completedRoutingFuture(
                    Optional.of(orchestrated),
                    "llm-dsl",
                    orchestrated.skillName(),
                    0.76,
                    List.of("LLM router executed via decision orchestrator"),
                    rejectedReasons
            ));
        }
        Optional<SkillDsl> llmDsl = llmDetection.skillDsl();
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
            candidates.add(
                    new Candidate(llmDsl.get().skill(), 0.76, "heuristic"),
                    decisionParamAssembler.toDecision(llmDsl.get(), context, 0.76),
                    "llm-dsl",
                    List.of("LLM router selected one of the shortlisted candidate skills")
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
                                           CandidateCollector candidates) {
        String routeName = detectedCandidates.size() > 1 ? "detected-skill-parallel" : "detected-skill";
        for (SkillCandidate candidate : detectedCandidates) {
            proposeDetectedCandidate(userId, userInput, context, candidate, rejectedReasons, candidates, routeName);
        }
    }

    private void proposeDetectedCandidate(String userId,
                                          String userInput,
                                          SkillContext context,
                                          SkillCandidate candidate,
                                          List<String> rejectedReasons,
                                          CandidateCollector candidates,
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
        candidates.add(
                new Candidate(skillName, detectedCandidateScore(candidate), "heuristic"),
                decisionParamAssembler.toDecision(skillName, context, 0.92),
                routeName,
                List.of("registered skill keywords matched the input")
        );
    }

    private Optional<CompletableFuture<RoutingOutcome>> routeToBraveSearchFirst(String userId,
                                                                                String userInput,
                                                                                SkillContext context,
                                                                                SemanticAnalysisResult semanticAnalysis,
                                                                                List<String> rejectedReasons,
                                                                                CandidateCollector candidates) {
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
        proposeDetectedCandidates(userId, userInput, context, detectedCandidates, rejectedReasons, candidates);
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

    private Optional<CandidateProposal> selectCandidateProposal(String userInput,
                                                                SkillContext context,
                                                                CandidateCollector candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        List<Candidate> candidateList = candidates.candidates();
        Optional<Candidate> selected = decisionPlanner == null
                ? candidateList.stream().max(java.util.Comparator.comparingDouble(Candidate::score))
                : decisionPlanner.selectCandidate(userInput, context, candidateList);
        return selected.flatMap(candidates::find);
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

    private record CandidateProposal(Candidate candidate,
                                     Decision decision,
                                     String routeName,
                                     List<String> acceptedReasons,
                                     UnaryOperator<SkillResult> resultTransformer) {
    }

    private static final class CandidateCollector {
        private final List<CandidateProposal> proposals = new ArrayList<>();

        void add(Candidate candidate,
                 Decision decision,
                 String routeName,
                 List<String> acceptedReasons) {
            add(candidate, decision, routeName, acceptedReasons, UnaryOperator.identity());
        }

        void add(Candidate candidate,
                 Decision decision,
                 String routeName,
                 List<String> acceptedReasons,
                 UnaryOperator<SkillResult> resultTransformer) {
            if (candidate == null || decision == null || decision.target() == null || decision.target().isBlank()) {
                return;
            }
            proposals.add(new CandidateProposal(
                    candidate,
                    decision,
                    routeName == null ? "" : routeName,
                    acceptedReasons == null ? List.of() : List.copyOf(acceptedReasons),
                    resultTransformer == null ? UnaryOperator.identity() : resultTransformer
            ));
        }

        boolean isEmpty() {
            return proposals.isEmpty();
        }

        List<Candidate> candidates() {
            return proposals.stream().map(CandidateProposal::candidate).toList();
        }

        Optional<CandidateProposal> find(Candidate candidate) {
            if (candidate == null) {
                return Optional.empty();
            }
            return proposals.stream()
                    .filter(proposal -> proposal.candidate() == candidate)
                    .findFirst()
                    .or(() -> proposals.stream().filter(proposal -> proposal.candidate().matches(candidate)).findFirst());
        }
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
