package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.RoutingDecisionDto;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import com.zhongbo.mindos.assistant.skill.SkillCandidate;
import com.zhongbo.mindos.assistant.skill.SkillEngineFacade;
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

    private final SkillEngineFacade skillEngine;
    private final SkillDslParser skillDslParser;
    private final BehaviorRoutingSupport behaviorRoutingSupport;
    private final DispatchRuleCatalog dispatchRuleCatalog;
    private final SemanticRoutingSupport semanticRoutingSupport;
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

    DispatchRoutingPipeline(SkillEngineFacade skillEngine,
                            SkillDslParser skillDslParser,
                            BehaviorRoutingSupport behaviorRoutingSupport,
                            DispatchRuleCatalog dispatchRuleCatalog,
                            SemanticRoutingSupport semanticRoutingSupport,
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
        RoutingStageRequest request = new RoutingStageRequest(
                userId,
                userInput,
                context,
                memoryContext,
                semanticAnalysis,
                replayProbe
        );
        for (RoutingStage stage : routingStages) {
            Optional<CompletableFuture<RoutingOutcome>> routed = stage.route(request, rejectedReasons);
            if (routed.isPresent()) {
                return routed.get();
            }
        }
        return CompletableFuture.completedFuture(new RoutingOutcome(Optional.empty(), fallbackRoutingDecision(rejectedReasons)));
    }

    private interface RoutingStage {
        Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request, List<String> rejectedReasons);
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
                                                                 List<String> rejectedReasons) {
            return routeExplicitStage(request.userId(), request.userInput(), request.context(), rejectedReasons);
        }
    }

    private final class SemanticRoutingStage implements RoutingStage {
        @Override
        public Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request,
                                                                 List<String> rejectedReasons) {
            return routeSemanticStage(
                    request.userId(),
                    request.userInput(),
                    request.context(),
                    request.semanticAnalysis(),
                    rejectedReasons
            );
        }
    }

    private final class RuleRoutingStage implements RoutingStage {
        @Override
        public Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request,
                                                                 List<String> rejectedReasons) {
            return routeRuleStage(
                    request.userId(),
                    request.userInput(),
                    request.context(),
                    request.replayProbe(),
                    rejectedReasons
            );
        }
    }

    private final class MetaHelpRoutingStage implements RoutingStage {
        @Override
        public Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request,
                                                                 List<String> rejectedReasons) {
            return routeMetaHelpStage(request.userId(), request.userInput(), rejectedReasons);
        }
    }

    private final class RealtimeSearchRoutingStage implements RoutingStage {
        @Override
        public Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request,
                                                                 List<String> rejectedReasons) {
            return routeRealtimeSearchStage(
                    request.userId(),
                    request.userInput(),
                    request.context(),
                    request.semanticAnalysis(),
                    rejectedReasons
            );
        }
    }

    private final class DetectedOrHabitRoutingStage implements RoutingStage {
        @Override
        public Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request,
                                                                 List<String> rejectedReasons) {
            return routeDetectedOrHabitStage(
                    request.userId(),
                    request.userInput(),
                    request.context(),
                    request.semanticAnalysis(),
                    rejectedReasons
            );
        }
    }

    private final class LlmPreAnalyzeRoutingStage implements RoutingStage {
        @Override
        public Optional<CompletableFuture<RoutingOutcome>> route(RoutingStageRequest request,
                                                                 List<String> rejectedReasons) {
            return Optional.of(routeViaLlmPreAnalyze(
                    request.userId(),
                    request.userInput(),
                    request.context(),
                    request.memoryContext(),
                    request.semanticAnalysis(),
                    request.replayProbe(),
                    rejectedReasons
            ));
        }
    }

    private Optional<CompletableFuture<RoutingOutcome>> routeExplicitStage(String userId,
                                                                           String userInput,
                                                                           SkillContext context,
                                                                           List<String> rejectedReasons) {
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
        LOGGER.info("Dispatcher route=explicit-dsl, userId=" + userId + ", skill=" + explicitDsl.get().skill());
        return Optional.of(executeDecisionRoute(
                decisionParamAssembler.toDecision(explicitDsl.get(), context, 1.0),
                userId,
                userInput,
                context,
                "explicit-dsl",
                1.0,
                List.of("input parsed as explicit SkillDSL"),
                rejectedReasons
        ));
    }

    private Optional<CompletableFuture<RoutingOutcome>> routeSemanticStage(String userId,
                                                                           String userInput,
                                                                           SkillContext context,
                                                                           SemanticAnalysisResult semanticAnalysis,
                                                                           List<String> rejectedReasons) {
        SemanticRoutingSupport.SemanticRoutingPlan semanticPlan = semanticRoutingSupport.buildSemanticRoutingPlan(userId, semanticAnalysis, userInput);
        Optional<SkillDsl> semanticDsl = behaviorRoutingSupport.isContinuationIntent(normalize(context.input()))
                ? Optional.empty()
                : semanticRoutingSupport.toSemanticSkillDsl(semanticPlan);
        if (semanticRoutingSupport.shouldAskSemanticClarification(semanticAnalysis, context.input(), semanticPlan)) {
            String clarifyReply = semanticRoutingSupport.buildSemanticClarifyReply(semanticAnalysis, semanticPlan);
            LOGGER.info("Dispatcher route=semantic-clarify, userId=" + userId
                    + ", skill=" + semanticPlan.skillName()
                    + ", confidence=" + semanticPlan.confidence());
            return Optional.of(CompletableFuture.completedFuture(new RoutingOutcome(
                    Optional.of(SkillResult.success("semantic.clarify", clarifyReply)),
                    new RoutingDecisionDto(
                            "semantic-clarify",
                            semanticPlan.skillName(),
                            semanticPlan.confidence(),
                            List.of("semantic analysis confidence is low or required parameters are missing, ask for clarification before execution"),
                            List.copyOf(rejectedReasons)
                    )
            )));
        }
        if (semanticDsl.isPresent()) {
            Optional<RoutingOutcome> blocked = capabilityBlockedRoute(
                    semanticDsl.get().skill(),
                    0.95,
                    "semantic analysis selected a local skill but capability guard blocked execution",
                    rejectedReasons
            );
            if (blocked.isPresent()) {
                return Optional.of(CompletableFuture.completedFuture(blocked.get()));
            }
            if (bridge.isSkillLoopGuardBlocked(userId, semanticDsl.get().skill(), context.input())) {
                return Optional.of(loopBlockedRoute(userId, semanticDsl.get().skill(), "semantic analysis route blocked by loop guard", rejectedReasons));
            }
            double confidence = Math.max(semanticAnalysisRouteMinConfidence, semanticPlan.confidence());
            LOGGER.info("Dispatcher route=semantic-analysis, userId=" + userId + ", skill=" + semanticDsl.get().skill());
            return Optional.of(executeDecisionRoute(
                    decisionParamAssembler.toDecision(semanticDsl.get(), context, confidence),
                    userId,
                    userInput,
                    context,
                    "semantic-analysis",
                    confidence,
                    List.of("semantic analysis suggested a confident local skill route"),
                    rejectedReasons
            ));
        }
        rejectedReasons.add(behaviorRoutingSupport.isContinuationIntent(normalize(context.input()))
                ? "semantic analysis deferred to continuation or habit routing"
                : "semantic analysis did not select a confident local skill");
        return Optional.empty();
    }

    private Optional<CompletableFuture<RoutingOutcome>> routeRuleStage(String userId,
                                                                       String userInput,
                                                                       SkillContext context,
                                                                       RoutingReplayProbe replayProbe,
                                                                       List<String> rejectedReasons) {
        Optional<SkillDsl> ruleDsl = detectSkillWithRules(userInput);
        if (ruleDsl.isPresent()
                && "code.generate".equals(ruleDsl.get().skill())
                && !dispatchRuleCatalog.isCodeGenerationIntent(userInput)
                && !behaviorRoutingSupport.isContinuationOnlyInput(userInput)) {
            rejectedReasons.add("rule-based code.generate rejected because input does not look like a code task");
            ruleDsl = Optional.empty();
        }
        if (ruleDsl.isEmpty()) {
            replayProbe.setRuleCandidate("NONE");
            rejectedReasons.add("no deterministic rule matched");
            return Optional.empty();
        }
        if (bridge.isSkillPreExecuteGuardBlocked(userId, ruleDsl.get().skill(), userInput)) {
            return Optional.of(loopBlockedRoute(userId, ruleDsl.get().skill(), "rule-based skill blocked by loop guard before execution", rejectedReasons));
        }
        replayProbe.setRuleCandidate(ruleDsl.get().skill());
        Optional<RoutingOutcome> blocked = capabilityBlockedRoute(
                ruleDsl.get().skill(),
                0.99,
                "rule-based route matched but capability guard blocked execution",
                rejectedReasons
        );
        if (blocked.isPresent()) {
            return Optional.of(CompletableFuture.completedFuture(blocked.get()));
        }
        LOGGER.info("Dispatcher route=rule, userId=" + userId + ", skill=" + ruleDsl.get().skill());
        return Optional.of(executeDecisionRoute(
                decisionParamAssembler.toDecision(ruleDsl.get(), context, 0.98),
                userId,
                userInput,
                context,
                "rule",
                0.98,
                List.of("matched deterministic built-in routing rule"),
                rejectedReasons
        ));
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
                                                                                 List<String> rejectedReasons) {
        rejectedReasons.add("brave-first routing is disabled; using normal detected-skill routing");
        if (!braveFirstSearchRoutingEnabled || !bridge.isRealtimeIntent(userInput, semanticAnalysis)) {
            return Optional.empty();
        }
        return routeToBraveSearchFirst(userId, userInput, context, semanticAnalysis, rejectedReasons);
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
                                                                                  List<String> rejectedReasons) {
        List<SkillCandidate> detectedSkillCandidates = parallelDetectedSkillRoutingEnabled
                ? skillEngine.detectSkillCandidates(context.input(), parallelDetectedSkillRoutingMaxCandidates)
                : skillEngine.detectSkillName(context.input())
                .map(name -> List.of(new SkillCandidate(name, 1)))
                .orElse(List.of());
        if (!detectedSkillCandidates.isEmpty()) {
            if (!parallelDetectedSkillRoutingEnabled || detectedSkillCandidates.size() == 1) {
                return routeSingleDetectedSkill(
                        userId,
                        userInput,
                        context,
                        detectedSkillCandidates.get(0).skillName(),
                        rejectedReasons
                );
            }
            return Optional.of(routeDetectedSkillCandidatesInParallel(userId, userInput, context, detectedSkillCandidates, rejectedReasons));
        }

        rejectedReasons.add("no registered skill keyword match");
        boolean realtimeLikeInput = bridge.isRealtimeLikeInput(userInput, semanticAnalysis);
        Optional<SkillDsl> habitDsl = realtimeLikeInput
                ? Optional.empty()
                : behaviorRoutingSupport.detectSkillWithMemoryHabits(
                        userId,
                        userInput,
                        context.attributes(),
                        skill -> bridge.isSkillLoopGuardBlocked(userId, skill, userInput)
                );
        if (realtimeLikeInput) {
            rejectedReasons.add("realtime-like input skipped memory-habit routing");
        }
        if (habitDsl.isPresent()
                && "code.generate".equals(habitDsl.get().skill())
                && !dispatchRuleCatalog.isCodeGenerationIntent(userInput)
                && !behaviorRoutingSupport.isContinuationOnlyInput(userInput)) {
            rejectedReasons.add("habit-based code.generate rejected because input does not look like a code task");
            habitDsl = Optional.empty();
        }
        if (habitDsl.isPresent()) {
            if (bridge.isSkillPreExecuteGuardBlocked(userId, habitDsl.get().skill(), userInput)) {
                return Optional.of(loopBlockedRoute(userId, habitDsl.get().skill(), "habit-based skill blocked by loop guard before execution", rejectedReasons));
            }
            Optional<RoutingOutcome> blocked = capabilityBlockedRoute(
                    habitDsl.get().skill(),
                    0.90,
                    "habit route selected but capability guard blocked execution",
                    rejectedReasons
            );
            if (blocked.isPresent()) {
                return Optional.of(CompletableFuture.completedFuture(blocked.get()));
            }
            LOGGER.info("Dispatcher route=memory-habit, userId=" + userId + ", skill=" + habitDsl.get().skill());
            String habitSkillName = habitDsl.get().skill();
            return Optional.of(executeDecisionRoute(
                    decisionParamAssembler.toDecision(habitDsl.get(), context, 0.88),
                    userId,
                    userInput,
                    context,
                    "memory-habit",
                    0.88,
                    List.of("recent successful skill history matched continuation intent"),
                    rejectedReasons,
                    result -> bridge.enrichMemoryHabitResult(result, habitSkillName, context.attributes())
            ));
        }

        rejectedReasons.add("habit route confidence gate not satisfied");
        return Optional.empty();
    }

    private Optional<CompletableFuture<RoutingOutcome>> routeSingleDetectedSkill(String userId,
                                                                                 String userInput,
                                                                                 SkillContext context,
                                                                                 String skillName,
                                                                                 List<String> rejectedReasons) {
        Optional<RoutingOutcome> blocked = capabilityBlockedRoute(
                skillName,
                0.95,
                "auto-detected skill matched but capability guard blocked execution",
                rejectedReasons
        );
        if (blocked.isPresent()) {
            return Optional.of(CompletableFuture.completedFuture(blocked.get()));
        }
        if (bridge.isSkillLoopGuardBlocked(userId, skillName, userInput)) {
            return Optional.of(loopBlockedRoute(userId, skillName, "detected skill blocked by loop guard", rejectedReasons, true));
        }
        LOGGER.info("Dispatcher route=detected-skill, userId=" + userId + ", skill=" + skillName);
        return Optional.of(executeDecisionRoute(
                decisionParamAssembler.toDecision(skillName, context, 0.92),
                userId,
                userInput,
                context,
                "detected-skill",
                0.92,
                List.of("registered skill keywords matched the input"),
                rejectedReasons
        ));
    }

    private CompletableFuture<RoutingOutcome> routeViaLlmPreAnalyze(String userId,
                                                                    String userInput,
                                                                    SkillContext context,
                                                                    String memoryContext,
                                                                    SemanticAnalysisResult semanticAnalysis,
                                                                    RoutingReplayProbe replayProbe,
                                                                    List<String> rejectedReasons) {
        if (behaviorRoutingSupport.isContinuationOnlyInput(userInput)) {
            replayProbe.setPreAnalyzeCandidate("SKIPPED_CONTINUATION");
            return llmFallbackRoute(
                    userId,
                    rejectedReasons,
                    "continuation-only input skipped skill pre-analyze and used llm fallback",
                    "continuation-only"
            );
        }

        skillPreAnalyzeRequestCount.incrementAndGet();
        if (bridge.isRealtimeIntent(userInput, semanticAnalysis)) {
            replayProbe.setPreAnalyzeCandidate("SKIPPED_REALTIME");
            skillPreAnalyzeSkippedByGateCount.incrementAndGet();
            return llmFallbackRoute(
                    userId,
                    rejectedReasons,
                    "realtime intent bypassed skill pre-analyze after no registered skill matched",
                    "realtime-intent-bypass"
            );
        }

        if (!bridge.shouldRunSkillPreAnalyze(userId, userInput)) {
            replayProbe.setPreAnalyzeCandidate("SKIPPED_BY_GATE");
            skillPreAnalyzeSkippedByGateCount.incrementAndGet();
            return llmFallbackRoute(
                    userId,
                    rejectedReasons,
                    "skill pre-analyze skipped by mode/threshold gate",
                    "skill-pre-analyze-gate"
            );
        }

        skillPreAnalyzeExecutedCount.incrementAndGet();
        LlmDetectionResult llmDetection = bridge.detectSkillWithLlm(userId, userInput, memoryContext, context, context.attributes());
        if (llmDetection.directResult().isPresent()) {
            return completedRoutingFuture(
                    Optional.of(llmDetection.directResult().get()),
                    "llm-dsl-clarify",
                    "semantic.clarify",
                    0.4,
                    List.of("params_missing"),
                    rejectedReasons
            );
        }
        if (llmDetection.result().isPresent()) {
            SkillResult orchestrated = llmDetection.result().get();
            if (bridge.isSkillLoopGuardBlocked(userId, orchestrated.skillName(), userInput)) {
                return loopBlockedRoute(userId, orchestrated.skillName(), "LLM decision-orchestrated skill blocked by loop guard", rejectedReasons);
            }
            replayProbe.setPreAnalyzeCandidate(orchestrated.skillName());
            skillPreAnalyzeAcceptedCount.incrementAndGet();
            return completedRoutingFuture(
                    Optional.of(orchestrated),
                    "llm-dsl",
                    orchestrated.skillName(),
                    0.76,
                    List.of("LLM router executed via decision orchestrator"),
                    rejectedReasons
            );
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
                return loopBlockedRoute(userId, llmDsl.get().skill(), "LLM-routed skill blocked by loop guard before execution", rejectedReasons);
            }
            Optional<RoutingOutcome> blocked = capabilityBlockedRoute(
                    llmDsl.get().skill(),
                    0.80,
                    "LLM routing selected a skill but capability guard blocked execution",
                    rejectedReasons
            );
            if (blocked.isPresent()) {
                return CompletableFuture.completedFuture(blocked.get());
            }
            if (bridge.isSkillLoopGuardBlocked(userId, llmDsl.get().skill(), userInput)) {
                return loopBlockedRoute(userId, llmDsl.get().skill(), "LLM-routed skill blocked by loop guard", rejectedReasons);
            }
            LOGGER.info("Dispatcher route=llm-dsl, userId=" + userId + ", skill=" + llmDsl.get().skill());
            return executeDecisionRoute(
                    decisionParamAssembler.toDecision(llmDsl.get(), context, 0.76),
                    userId,
                    userInput,
                    context,
                    "llm-dsl",
                    0.76,
                    List.of("LLM router selected one of the shortlisted candidate skills"),
                    rejectedReasons
            );
        }

        if ("NOT_RUN".equals(replayProbe.preAnalyzeCandidate())) {
            replayProbe.setPreAnalyzeCandidate("NONE");
        }

        return llmFallbackRoute(
                userId,
                rejectedReasons,
                "LLM router returned NONE or no shortlist candidate was selected",
                null
        );
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

    private CompletableFuture<RoutingOutcome> routeDetectedSkillCandidatesInParallel(String userId,
                                                                                     String userInput,
                                                                                     SkillContext context,
                                                                                     List<SkillCandidate> candidates,
                                                                                     List<String> rejectedReasons) {
        List<ParallelSkillCandidateExecution> executions = new ArrayList<>();
        List<String> localRejected = new ArrayList<>(rejectedReasons);
        for (SkillCandidate candidate : candidates) {
            prepareParallelDetectedExecution(userId, userInput, context, candidate, localRejected)
                    .ifPresent(executions::add);
        }
        if (executions.isEmpty()) {
            return CompletableFuture.completedFuture(new RoutingOutcome(Optional.empty(), fallbackRoutingDecision(localRejected)));
        }

        CompletableFuture<?>[] futures = executions.stream().map(ParallelSkillCandidateExecution::resultFuture).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures).thenApply(ignored -> {
            List<ParallelSkillCandidateResult> successful = collectSuccessfulParallelDetectedResults(executions);
            if (successful.isEmpty()) {
                localRejected.add("parallel detected-skill candidates all failed or timed out");
                return new RoutingOutcome(Optional.empty(), fallbackRoutingDecision(localRejected));
            }
            ParallelSkillCandidateResult selected = selectParallelDetectedResult(userInput, successful);
            LOGGER.info("Dispatcher route=detected-skill-parallel, userId=" + userId + ", skill=" + selected.skillName());
            return new RoutingOutcome(selected.result(), new RoutingDecisionDto(
                    "detected-skill-parallel",
                    selected.skillName(),
                    0.93,
                    List.of("multiple registered skill candidates were executed in parallel and the highest-priority successful result was selected"),
                    List.copyOf(localRejected)
            ));
        });
    }

    private Optional<ParallelSkillCandidateExecution> prepareParallelDetectedExecution(String userId,
                                                                                       String userInput,
                                                                                       SkillContext context,
                                                                                       SkillCandidate candidate,
                                                                                       List<String> rejectedReasons) {
        String skillName = candidate.skillName();
        Optional<SkillResult> blocked = bridge.maybeBlockByCapability(skillName);
        if (blocked.isPresent()) {
            rejectedReasons.add("parallel candidate blocked by capability guard: " + skillName);
            return Optional.empty();
        }
        if (bridge.isSkillLoopGuardBlocked(userId, skillName, userInput)) {
            detectedSkillLoopSkipBlockedCount.incrementAndGet();
            rejectedReasons.add("parallel candidate blocked by loop guard: " + skillName);
            return Optional.empty();
        }
        Decision decision = decisionParamAssembler.toDecision(skillName, context, 0.93);
        CompletableFuture<Optional<SkillResult>> future = CompletableFuture.supplyAsync(() -> executeDetectedCandidate(
                        userId,
                        userInput,
                        context,
                        decision
                ))
                .completeOnTimeout(Optional.empty(), parallelDetectedSkillRoutingTimeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(error -> Optional.empty());
        return Optional.of(new ParallelSkillCandidateExecution(skillName, candidate.score(), future));
    }

    private Optional<SkillResult> executeDetectedCandidate(String userId,
                                                           String userInput,
                                                           SkillContext context,
                                                           Decision decision) {
        if (decision == null) {
            return Optional.empty();
        }
        DecisionOrchestrator.OrchestrationOutcome outcome = decisionOrchestrator.orchestrate(
                decision,
                new DecisionOrchestrator.OrchestrationRequest(
                        userId,
                        userInput,
                        context,
                        decisionParamAssembler.orchestratorProfileContext(context)
                )
        );
        return outcome.hasResult() ? Optional.ofNullable(outcome.result()) : Optional.empty();
    }

    private List<ParallelSkillCandidateResult> collectSuccessfulParallelDetectedResults(List<ParallelSkillCandidateExecution> executions) {
        List<ParallelSkillCandidateResult> successful = new ArrayList<>();
        for (ParallelSkillCandidateExecution execution : executions) {
            Optional<SkillResult> result = execution.resultFuture().join();
            if (result.isPresent() && result.get().success()) {
                successful.add(new ParallelSkillCandidateResult(execution.skillName(), execution.score(), result));
            }
        }
        return successful;
    }

    private ParallelSkillCandidateResult selectParallelDetectedResult(String userInput,
                                                                      List<ParallelSkillCandidateResult> successful) {
        successful.sort((left, right) -> {
            int leftPriority = priorityRank(left.skillName());
            int rightPriority = priorityRank(right.skillName());
            if (leftPriority != rightPriority) {
                return Integer.compare(leftPriority, rightPriority);
            }
            if (left.score() != right.score()) {
                return Integer.compare(right.score(), left.score());
            }
            return left.skillName().compareToIgnoreCase(right.skillName());
        });
        return successful.stream()
                .filter(candidate -> !isSearchLikeSkill(candidate.skillName())
                        || isSearchResultUsable(userInput, candidate.result()))
                .findFirst()
                .orElse(successful.get(0));
    }

    private boolean isSearchLikeSkill(String skillName) {
        return normalize(skillName).contains("search");
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

    private Optional<SkillDsl> detectSkillWithRules(String userInput) {
        return dispatchRuleCatalog.detectSkillWithRules(userInput);
    }

    private Optional<CompletableFuture<RoutingOutcome>> routeToBraveSearchFirst(String userId,
                                                                                String userInput,
                                                                                SkillContext context,
                                                                                SemanticAnalysisResult semanticAnalysis,
                                                                                List<String> rejectedReasons) {
        if (!braveFirstSearchRoutingEnabled) {
            return Optional.empty();
        }
        if (!bridge.isRealtimeIntent(userInput, semanticAnalysis)) {
            rejectedReasons.add("brave-first routing skipped because input is not realtime intent");
            return Optional.empty();
        }
        List<SkillCandidate> candidates = skillEngine.detectSkillCandidates(context.input(), Math.max(2, parallelDetectedSkillRoutingMaxCandidates));
        if (candidates.isEmpty()) {
            rejectedReasons.add("brave-first routing enabled but no realtime search candidates were detected");
            return Optional.empty();
        }
        return Optional.of(routeDetectedSkillCandidatesInParallel(userId, userInput, context, candidates, rejectedReasons));
    }

    private boolean isSearchResultUsable(String userInput, Optional<SkillResult> result) {
        if (result.isEmpty() || !result.get().success()) {
            return false;
        }
        String output = normalize(result.get().output());
        if (output.isBlank()) {
            return false;
        }
        if (containsAny(output, "无结果", "未找到", "not found", "no result", "empty result", "没有查到")) {
            return false;
        }
        Set<String> queryTokens = extractSearchIntentTokens(userInput);
        if (queryTokens.isEmpty()) {
            return true;
        }
        for (String token : queryTokens) {
            if (output.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> extractSearchIntentTokens(String userInput) {
        String normalized = normalize(userInput);
        if (normalized.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : ROUTING_TOKEN_SPLIT_PATTERN.split(normalized)) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (part.length() <= 1) {
                continue;
            }
            if (containsAny(part, "今天", "明天", "后天", "新闻", "最新", "实时", "天气", "查询", "搜索", "查一下", "帮我")) {
                continue;
            }
            tokens.add(part);
            if (tokens.size() >= 6) {
                break;
            }
        }
        return tokens.isEmpty() ? Set.of() : Set.copyOf(tokens);
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

    private record ParallelSkillCandidateExecution(String skillName,
                                                   int score,
                                                   CompletableFuture<Optional<SkillResult>> resultFuture) {
    }

    private record ParallelSkillCandidateResult(String skillName,
                                                int score,
                                                Optional<SkillResult> result) {
    }

    interface RoutingBridge {
        Optional<SkillResult> maybeBlockByCapability(String skillName);

        boolean isSkillPreExecuteGuardBlocked(String userId, String skillName, String userInput);

        boolean isSkillLoopGuardBlocked(String userId, String skillName, String userInput);

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
