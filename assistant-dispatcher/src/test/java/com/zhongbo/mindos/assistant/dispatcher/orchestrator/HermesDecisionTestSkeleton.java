package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.decision.DecisionParser;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDslExecutor;
import com.zhongbo.mindos.assistant.skill.SkillEngine;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HermesDecisionTestSkeleton {

    private static final String DECISION_JSON_EXAMPLE = """
            {
              "intent": "teach.plan",
              "target": "teaching.plan",
              "params": {
                "studentId": "stu-1",
                "topic": "math",
                "durationWeeks": 6,
                "weeklyHours": 8
              },
              "confidence": 0.82,
              "requiresClarify": false
            }
            """;

    private static final String CANDIDATE_PLANNER_EXAMPLE = """
            {
              "suggestedTarget": "teaching.plan",
              "candidates": [
                "teaching.plan",
                "mcp.teaching.plan",
                "teaching.plan.backup"
              ]
            }
            """;

    private static final String PARAM_VALIDATOR_EXAMPLE = """
            {
              "target": "teaching.plan",
              "inputParams": {
                "topic": "math"
              },
              "autoFilled": {
                "studentId": "stu-1",
                "durationWeeks": 6,
                "weeklyHours": 8
              },
              "validatedParams": {
                "studentId": "stu-1",
                "topic": "math",
                "durationWeeks": 6,
                "weeklyHours": 8
              },
              "valid": true,
              "message": ""
            }
            """;

    private static final String CONVERSATION_LOOP_EXAMPLE = """
            {
              "turns": [
                {
                  "decision": {
                    "intent": "teach.plan",
                    "target": "teaching.plan",
                    "params": {
                      "studentId": "stu-1",
                      "durationWeeks": 6
                    },
                    "confidence": 0.64,
                    "requiresClarify": true
                  },
                  "dispatcherReply": "我理解你想执行 `teaching.plan`，但还缺少关键信息：缺少必填参数: topic,weeklyHours"
                },
                {
                  "userReply": "主题是 math",
                  "dispatcherReply": "我理解你想执行 `teaching.plan`，但还缺少关键信息：缺少必填参数: weeklyHours"
                },
                {
                  "userReply": "每周 8 小时",
                  "dispatcherReply": "[SKILL] invoke teaching.plan attrs={studentId=stu-1, durationWeeks=6, topic=math, weeklyHours=8}"
                }
              ]
            }
            """;

    private static final String DISPATCHER_FLOW_EXAMPLE = """
            Decision decision = decisionParser.parse(rawDecisionJson).orElseThrow();
            Map<String, Object> completedParams = paramAutoFill.merge(decision.params(), memoryView, profileContext);
            ValidationResult validation = paramValidator.validate(decision.target(), completedParams);
            while (!validation.valid()) {
                SkillResult clarification = conversationLoop.requestClarification(decision.target(), validation.message());
                Map<String, Object> replyParams = clarifyExtractor.extract(userReply);
                completedParams = paramAutoFill.merge(completedParams, replyParams);
                validation = paramValidator.validate(decision.target(), completedParams);
            }
            OrchestrationOutcome outcome = orchestrator.orchestrate(
                new Decision(decision.intent(), decision.target(), completedParams, decision.confidence(), false),
                request
            );
            """;

    private static final String TEST_IO_EXAMPLE = """
            {
              "input": "给 stu-1 做一个 6 周的数学教学计划，每周 8 小时",
              "decisionJson": {
                "intent": "teach.plan",
                "target": "teaching.plan",
                "params": {
                  "studentId": "stu-1",
                  "topic": "math",
                  "durationWeeks": 6,
                  "weeklyHours": 8
                },
                "confidence": 0.82,
                "requiresClarify": false
              },
              "orchestratorOutput": {
                "selectedSkill": "teaching.plan",
                "usedFallback": false,
                "skillResult": "teaching.plan ok"
              }
            }
            """;

    @Test
    void shouldParseStrictDecisionJsonAndKeepHermesExamplesRunnable() {
        DecisionParser parser = new DecisionParser();

        Optional<Decision> parsed = parser.parse(DECISION_JSON_EXAMPLE);

        assertTrue(parsed.isPresent());
        assertEquals("teach.plan", parsed.get().intent());
        assertEquals("teaching.plan", parsed.get().target());
        assertEquals("stu-1", parsed.get().params().get("studentId"));
        assertEquals(6, parsed.get().params().get("durationWeeks"));
        assertFalse(parsed.get().requiresClarify());
        assertTrue(CANDIDATE_PLANNER_EXAMPLE.contains("\"mcp.teaching.plan\""));
        assertTrue(PARAM_VALIDATOR_EXAMPLE.contains("\"autoFilled\""));
        assertTrue(CONVERSATION_LOOP_EXAMPLE.contains("\"dispatcherReply\""));
        assertTrue(DISPATCHER_FLOW_EXAMPLE.contains("orchestrator.orchestrate"));
        assertTrue(TEST_IO_EXAMPLE.contains("\"selectedSkill\": \"teaching.plan\""));
    }

    @Test
    void shouldTriggerSkillInvocationFromDecisionJson() {
        HermesHarness harness = HermesHarness.withResults(Map.of(
                "teaching.plan", SkillResult.success("teaching.plan", "teaching.plan ok"),
                "mcp.teaching.plan", SkillResult.success("mcp.teaching.plan", "mcp ok"),
                "teaching.plan.backup", SkillResult.success("teaching.plan.backup", "backup ok")
        ));

        HermesOutcome outcome = harness.run(
                DECISION_JSON_EXAMPLE,
                "给 stu-1 做一个 6 周的数学教学计划，每周 8 小时",
                Map.of(),
                Map.of(),
                List.of()
        );

        assertTrue(outcome.parsedDecision().isPresent());
        assertTrue(outcome.orchestration().hasResult());
        assertEquals("teaching.plan", outcome.orchestration().selectedSkill());
        assertEquals(List.of("teaching.plan", "mcp.teaching.plan", "teaching.plan.backup"), outcome.candidates());
        assertEquals("teaching.plan ok", outcome.orchestration().result().output());
        assertTrue(outcome.invocationLog().get(0).startsWith("[SKILL] invoke teaching.plan"));
    }

    @Test
    void shouldAutofillParamsBeforeValidation() {
        HermesHarness harness = HermesHarness.withResults(Map.of(
                "teaching.plan", SkillResult.success("teaching.plan", "teaching.plan ok")
        ));
        String partialDecision = """
                {
                  "intent": "teach.plan",
                  "target": "teaching.plan",
                  "params": {
                    "topic": "math"
                  },
                  "confidence": 0.78,
                  "requiresClarify": false
                }
                """;

        HermesOutcome outcome = harness.run(
                partialDecision,
                "给我一个数学计划",
                Map.of("durationWeeks", 6),
                Map.of("studentId", "stu-1", "weeklyHours", 8),
                List.of()
        );

        assertTrue(outcome.orchestration().hasResult());
        assertEquals(Map.of(
                "studentId", "stu-1",
                "topic", "math",
                "durationWeeks", 6,
                "weeklyHours", 8
        ), outcome.completedParams());
        assertTrue(outcome.validation().valid());
        assertTrue(outcome.autofillLog().contains("studentId=stu-1"));
        assertTrue(outcome.autofillLog().contains("weeklyHours=8"));
    }

    @Test
    void shouldClarifyAcrossMultipleTurnsUntilRequiredParamsAreFilled() {
        HermesHarness harness = HermesHarness.withResults(Map.of(
                "teaching.plan", SkillResult.success("teaching.plan", "teaching.plan ok")
        ));
        String clarifyDecision = """
                {
                  "intent": "teach.plan",
                  "target": "teaching.plan",
                  "params": {
                    "studentId": "stu-1",
                    "durationWeeks": 6
                  },
                  "confidence": 0.64,
                  "requiresClarify": true
                }
                """;

        HermesOutcome outcome = harness.run(
                clarifyDecision,
                "给我做教学计划",
                Map.of(),
                Map.of(),
                List.of("主题是 math", "每周 8 小时")
        );

        assertEquals(2, outcome.clarificationMessages().size());
        assertTrue(outcome.clarificationMessages().get(0).contains("topic"));
        assertTrue(outcome.clarificationMessages().get(1).contains("weeklyHours"));
        assertTrue(outcome.orchestration().hasResult());
        assertEquals("teaching.plan", outcome.orchestration().selectedSkill());
        assertEquals("math", outcome.completedParams().get("topic"));
        assertEquals(8, outcome.completedParams().get("weeklyHours"));
    }

    @Test
    void shouldFallbackToMcpWhenPrimarySkillFails() {
        HermesHarness harness = HermesHarness.withResults(Map.of(
                "teaching.plan", SkillResult.failure("teaching.plan", "primary unavailable"),
                "mcp.teaching.plan", SkillResult.success("mcp.teaching.plan", "mcp ok")
        ));

        HermesOutcome outcome = harness.run(
                DECISION_JSON_EXAMPLE,
                "给 stu-1 做一个 6 周的数学教学计划，每周 8 小时",
                Map.of(),
                Map.of(),
                List.of()
        );

        assertTrue(outcome.orchestration().hasResult());
        assertEquals("mcp.teaching.plan", outcome.orchestration().selectedSkill());
        assertTrue(outcome.orchestration().usedFallback());
        assertEquals(2, outcome.invocationLog().size());
        assertTrue(outcome.invocationLog().get(0).startsWith("[SKILL] invoke teaching.plan"));
        assertTrue(outcome.invocationLog().get(1).startsWith("[MCP] invoke mcp.teaching.plan"));
    }

    @Test
    void shouldReportSkillInvocationRateAndSemanticAnalysisSuccessRate() {
        HermesHarness harness = HermesHarness.withResults(Map.of(
                "teaching.plan", SkillResult.success("teaching.plan", "ok"),
                "mcp.teaching.plan", SkillResult.success("mcp.teaching.plan", "ok"),
                "teaching.plan.backup", SkillResult.success("teaching.plan.backup", "ok")
        ));

        List<HermesOutcome> outcomes = List.of(
                harness.run(DECISION_JSON_EXAMPLE, "数学教学计划", Map.of(), Map.of(), List.of()),
                harness.run("""
                        {"intent":"teach.plan","target":"teaching.plan","params":{"topic":"math"},"confidence":0.8,"requiresClarify":false}
                        """, "数学教学计划", Map.of("durationWeeks", 6), Map.of("studentId", "stu-1", "weeklyHours", 8), List.of()),
                harness.run("not json", "坏的 DSL", Map.of(), Map.of(), List.of()),
                harness.run("""
                        {"intent":"teach.plan","target":"teaching.plan","params":{"studentId":"stu-1","durationWeeks":6},"confidence":0.6,"requiresClarify":true}
                        """, "需要澄清", Map.of(), Map.of(), List.of("主题是 math", "每周 8 小时"))
        );

        HermesBatchMetrics metrics = HermesBatchMetrics.from(outcomes);

        assertEquals(4, metrics.totalInputs());
        assertEquals(3, metrics.semanticSuccesses());
        assertEquals(3, metrics.skillInvocations());
        assertEquals(0.75d, metrics.semanticAnalysisSuccessRate());
        assertEquals(1.0d, metrics.skillInvocationRate());
    }

    private record HermesOutcome(Optional<Decision> parsedDecision,
                                 List<String> candidates,
                                 Map<String, Object> completedParams,
                                 ParamValidator.ValidationResult validation,
                                 DecisionOrchestrator.OrchestrationOutcome orchestration,
                                 List<String> clarificationMessages,
                                 List<String> invocationLog,
                                 String autofillLog) {
    }

    private record HermesBatchMetrics(int totalInputs,
                                      int semanticSuccesses,
                                      int skillInvocations,
                                      double semanticAnalysisSuccessRate,
                                      double skillInvocationRate) {

        private static HermesBatchMetrics from(List<HermesOutcome> outcomes) {
            int total = outcomes == null ? 0 : outcomes.size();
            int semanticSuccesses = outcomes == null ? 0 : (int) outcomes.stream()
                    .filter(outcome -> outcome.parsedDecision().isPresent())
                    .count();
            int skillInvocations = outcomes == null ? 0 : (int) outcomes.stream()
                    .filter(outcome -> outcome.orchestration() != null && outcome.orchestration().hasResult())
                    .count();
            double semanticRate = total == 0 ? 0.0d : semanticSuccesses / (double) total;
            double invocationRate = semanticSuccesses == 0 ? 0.0d : skillInvocations / (double) semanticSuccesses;
            return new HermesBatchMetrics(total, semanticSuccesses, skillInvocations, semanticRate, invocationRate);
        }
    }

    private static final class HermesHarness {

        private final DecisionParser parser = new DecisionParser();
        private final CandidatePlanner candidatePlanner = new ExampleCandidatePlanner();
        private final HermesTeachingPlanValidator validator = new HermesTeachingPlanValidator();
        private final ConversationLoop conversationLoop = new SimpleConversationLoop();
        private final List<String> invocationLog = new ArrayList<>();
        private final DefaultDecisionOrchestrator orchestrator;

        private HermesHarness(Map<String, SkillResult> scriptedResults) {
            this.orchestrator = new DefaultDecisionOrchestrator(
                    candidatePlanner,
                    validator,
                    conversationLoop,
                    new ExampleFallbackPlan(),
                    skillEngine(scriptedResults),
                    noopRecorder(),
                    false,
                    500
            );
        }

        static HermesHarness withResults(Map<String, SkillResult> scriptedResults) {
            return new HermesHarness(scriptedResults);
        }

        HermesOutcome run(String rawDecisionJson,
                          String userInput,
                          Map<String, Object> memoryView,
                          Map<String, Object> profileContext,
                          List<String> clarifyReplies) {
            invocationLog.clear();
            Optional<Decision> parsed = parser.parse(rawDecisionJson);
            if (parsed.isEmpty()) {
                return new HermesOutcome(
                        Optional.empty(),
                        List.of(),
                        Map.of(),
                        ParamValidator.ValidationResult.error("invalid decision json"),
                        null,
                        List.of(),
                        List.copyOf(invocationLog),
                        "{}"
                );
            }

            Decision modelDecision = parsed.get();
            Map<String, Object> completed = merge(modelDecision.params(), memoryView, profileContext);
            Map<String, Object> autoFilled = diff(completed, modelDecision.params());
            String autoFillLog = autoFilled.toString();
            List<String> clarifications = new ArrayList<>();
            DecisionOrchestrator.OrchestrationOutcome outcome = null;
            int replyIndex = 0;

            while (true) {
                ParamValidator.ValidationResult validation = validator.validate(modelDecision.target(), completed);
                boolean effectiveRequiresClarify = !validation.valid();
                Decision attempt = new Decision(
                        modelDecision.intent(),
                        modelDecision.target(),
                        completed,
                        modelDecision.confidence(),
                        effectiveRequiresClarify
                );
                outcome = orchestrator.orchestrate(attempt, request(userInput, completed, profileContext));
                if (!outcome.hasClarification()) {
                    return new HermesOutcome(
                            parsed,
                            candidatesFor(modelDecision.target()),
                            Map.copyOf(completed),
                            validation,
                            outcome,
                            List.copyOf(clarifications),
                            List.copyOf(invocationLog),
                            autoFillLog
                    );
                }
                clarifications.add(outcome.clarification().output());
                if (replyIndex >= clarifyReplies.size()) {
                    return new HermesOutcome(
                            parsed,
                            candidatesFor(modelDecision.target()),
                            Map.copyOf(completed),
                            validation,
                            outcome,
                            List.copyOf(clarifications),
                            List.copyOf(invocationLog),
                            autoFillLog
                    );
                }
                completed = merge(completed, extractReplyParams(clarifyReplies.get(replyIndex++)), Map.of());
            }
        }

        private DecisionOrchestrator.OrchestrationRequest request(String userInput,
                                                                  Map<String, Object> completed,
                                                                  Map<String, Object> profileContext) {
            return new DecisionOrchestrator.OrchestrationRequest(
                    "demo-user",
                    userInput,
                    new SkillContext("demo-user", userInput, completed),
                    profileContext
            );
        }

        private SkillEngine skillEngine(Map<String, SkillResult> scriptedResults) {
            List<Skill> skills = scriptedResults.entrySet().stream()
                    .map(entry -> (Skill) new Skill() {
                        @Override
                        public String name() {
                            return entry.getKey();
                        }

                        @Override
                        public String description() {
                            return "Hermes test double";
                        }

                        @Override
                        public SkillResult run(SkillContext context) {
                            String kind = entry.getKey().startsWith("mcp.") ? "[MCP]" : "[SKILL]";
                            invocationLog.add(kind + " invoke " + entry.getKey() + " attrs=" + context.attributes());
                            return entry.getValue();
                        }

                        @Override
                        public int routingScore(String input) {
                            return 900;
                        }
                    })
                    .toList();
            SkillRegistry registry = new SkillRegistry(skills);
            SkillDslExecutor executor = new SkillDslExecutor(registry);
            return new SkillEngine(registry, executor);
        }

        private Map<String, Object> merge(Map<String, Object> modelParams,
                                          Map<String, Object> memoryView,
                                          Map<String, Object> profileContext) {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (profileContext != null) {
                merged.putAll(profileContext);
            }
            if (memoryView != null) {
                merged.putAll(memoryView);
            }
            if (modelParams != null) {
                merged.putAll(modelParams);
            }
            return merged;
        }

        private Map<String, Object> diff(Map<String, Object> completed, Map<String, Object> rawParams) {
            Map<String, Object> raw = rawParams == null ? Map.of() : rawParams;
            Map<String, Object> diff = new LinkedHashMap<>();
            completed.forEach((key, value) -> {
                if (!raw.containsKey(key)) {
                    diff.put(key, value);
                }
            });
            return diff;
        }

        private Map<String, Object> extractReplyParams(String reply) {
            if (reply == null || reply.isBlank()) {
                return Map.of();
            }
            Map<String, Object> extracted = new LinkedHashMap<>();
            String normalized = reply.toLowerCase(Locale.ROOT);
            if (normalized.contains("math") || reply.contains("数学")) {
                extracted.put("topic", "math");
            }
            Matcher weeklyHours = Pattern.compile("(\\d+)\\s*小时").matcher(reply);
            if (weeklyHours.find()) {
                extracted.put("weeklyHours", Integer.parseInt(weeklyHours.group(1)));
            }
            Matcher durationWeeks = Pattern.compile("(\\d+)\\s*周").matcher(reply);
            if (durationWeeks.find()) {
                extracted.put("durationWeeks", Integer.parseInt(durationWeeks.group(1)));
            }
            Matcher studentId = Pattern.compile("(stu-[A-Za-z0-9_-]+)").matcher(reply);
            if (studentId.find()) {
                extracted.put("studentId", studentId.group(1));
            }
            return extracted;
        }

        private List<String> candidatesFor(String target) {
            List<String> combined = new ArrayList<>(candidatePlanner.plan(target));
            combined.addAll(new ExampleFallbackPlan().fallbacks(target));
            return List.copyOf(combined);
        }
    }

    private static final class ExampleCandidatePlanner implements CandidatePlanner {

        @Override
        public List<String> plan(String suggestedTarget) {
            if ("teaching.plan".equals(suggestedTarget)) {
                return List.of("teaching.plan", "mcp.teaching.plan");
            }
            return suggestedTarget == null || suggestedTarget.isBlank() ? List.of() : List.of(suggestedTarget);
        }
    }

    private static final class ExampleFallbackPlan implements FallbackPlan {

        @Override
        public List<String> fallbacks(String primary) {
            if ("teaching.plan".equals(primary)) {
                return List.of("teaching.plan.backup");
            }
            return List.of();
        }
    }

    private static final class HermesTeachingPlanValidator implements ParamValidator {

        private static final Set<String> REQUIRED = Set.of("studentId", "topic", "durationWeeks", "weeklyHours");

        @Override
        public ValidationResult validate(String target, Map<String, Object> params) {
            if (target == null || target.isBlank()) {
                return ValidationResult.error("missing target");
            }
            if (!"teaching.plan".equals(target) && !"teaching.plan.backup".equals(target) && !"mcp.teaching.plan".equals(target)) {
                return ValidationResult.ok();
            }
            Map<String, Object> safeParams = params == null ? Map.of() : params;
            List<String> missing = REQUIRED.stream()
                    .filter(key -> !safeParams.containsKey(key) || isBlank(safeParams.get(key)))
                    .sorted()
                    .toList();
            if (missing.isEmpty()) {
                return ValidationResult.ok();
            }
            return ValidationResult.error("缺少必填参数: " + String.join(",", missing));
        }

        private boolean isBlank(Object value) {
            if (value == null) {
                return true;
            }
            if (value instanceof Number) {
                return false;
            }
            return String.valueOf(value).isBlank();
        }
    }

    private static PostExecutionMemoryRecorder noopRecorder() {
        return new PostExecutionMemoryRecorder(new MemoryGateway() {
            @Override
            public List<com.zhongbo.mindos.assistant.memory.model.ConversationTurn> recentHistory(String userId) {
                return List.of();
            }

            @Override
            public void recordSkillUsage(String userId, String skillName, String input, boolean success) {
            }

            @Override
            public void writeProcedural(String userId, com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry entry) {
            }

            @Override
            public void writeSemantic(String userId, com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry entry) {
            }

            @Override
            public void writeSemantic(String userId, String text, List<Double> embedding, String bucket) {
            }
        }, false, false, "", 280);
    }
}
