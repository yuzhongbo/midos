package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.InMemoryProcedureMemoryEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProceduralMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.RecoveryAction;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.RecoveryManager;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.RecoveryManager.RecoveryReport;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemory;
import com.zhongbo.mindos.assistant.skill.DefaultSkillExecutionGateway;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDslExecutor;
import com.zhongbo.mindos.assistant.skill.SkillExecutionGateway;
import com.zhongbo.mindos.assistant.skill.SkillEngine;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DefaultDecisionOrchestratorTest {

    private DefaultDecisionOrchestrator orchestrator(SkillRuntime runtime, boolean parallelMcp) {
        InMemoryParamSchemaRegistry registry = new InMemoryParamSchemaRegistry();
        registry.registerDefaults();
        return new DefaultDecisionOrchestrator(
                new SimpleCandidatePlanner(runtime.skillEngine(), dispatcherMemoryFacade(noopGateway()), 3, 0.40, 0.35, 0.15, 0.10),
                new SimpleParamValidator(registry, noopGateway()),
                new SimpleConversationLoop(),
                new SimpleFallbackPlan(),
                runtime.executionGateway(),
                noopGateway(),
                noopRecorder(),
                new TaskExecutor(3),
                parallelMcp,
                500,
                0,
                "",
                3
        );
    }

    private DecisionOrchestrator.OrchestrationRequest request() {
        return new DecisionOrchestrator.OrchestrationRequest("user", "input", new SkillContext("user", "input", Map.of()), Map.of());
    }

    @Test
    void shouldClarifyWhenSchemaMissing() {
        InMemoryParamSchemaRegistry registry = new InMemoryParamSchemaRegistry();
        registry.registerDefaults();
        assertTrue(registry.find("teaching.plan").isPresent());
        ParamValidator validator = new SimpleParamValidator(registry, noopGateway());
        ParamValidator.ValidationResult validation = validator.validate("teaching.plan", Map.of());
        assertFalse(validation.valid());
        SkillRuntime emptyRuntime = simpleSkillRuntime(Map.of());
        DefaultDecisionOrchestrator orchestrator = new DefaultDecisionOrchestrator(
                new SimpleCandidatePlanner(emptyRuntime.skillEngine(), dispatcherMemoryFacade(noopGateway()), 3, 0.40, 0.35, 0.15, 0.10),
                validator,
                new SimpleConversationLoop(),
                new SimpleFallbackPlan(),
                emptyRuntime.executionGateway(),
                noopGateway(),
                noopRecorder(),
                new TaskExecutor(3),
                false,
                500,
                0,
                "",
                3
        );
        Decision decision = new Decision("plan", "teaching.plan", Map.of(), 0.72, false);

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(
                decision,
                new DecisionOrchestrator.OrchestrationRequest("user", "", new SkillContext("user", "", Map.of()), Map.of())
        );

        assertTrue(outcome.hasClarification());
        assertEquals("semantic.clarify", outcome.clarification().skillName());
    }

    @Test
    void shouldRejectInvalidMcpNamespace() {
        DefaultDecisionOrchestrator orchestrator = orchestrator(simpleSkillRuntime(Map.of()), false);
        Decision decision = new Decision("tool", "mcp.invalid", Map.of("input", "hi"), 0.80, false);

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(decision, request());

        assertTrue(outcome.hasClarification());
        assertTrue(outcome.clarification().output().contains("MCP"));
    }

    @Test
    void shouldReturnDslWhenParamsPresent() {
        SkillRuntime skillRuntime = simpleSkillRuntime(Map.of("todo.create", SkillResult.success("todo.create", "ok")));
        DefaultDecisionOrchestrator orchestrator = orchestrator(skillRuntime, false);
        Decision decision = new Decision("todo", "todo.create", Map.of("task", "demo task"), 0.9, false);

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(decision, request());

        assertTrue(outcome.hasSkillDsl());
        assertTrue(outcome.hasResult());
        assertEquals("single-task-graph", outcome.trace().strategy());
        assertEquals("todo.create", outcome.skillDsl().skill());
        assertEquals("todo.create", outcome.result().skillName());
    }

    @Test
    void shouldPickPreferredMcpInParallel() {
        SkillRuntime skillRuntime = simpleSkillRuntime(Map.of(
                "mcp.a.tool", SkillResult.success("mcp.a.tool", "a"),
                "mcp.b.tool", SkillResult.success("mcp.b.tool", "b")
        ));
        DefaultDecisionOrchestrator orchestrator = orchestrator(skillRuntime, true);
        Decision decision = new Decision("tool", "mcp.a.tool", Map.of("input", "demo"), 0.9, false);

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(decision, request());

        assertTrue(outcome.hasResult());
        assertEquals("mcp.a.tool", outcome.result().skillName());
    }

    @Test
    void shouldRetryAfterParamRepair() {
        InMemoryParamSchemaRegistry registry = new InMemoryParamSchemaRegistry();
        registry.registerDefaults();
        registry.register("custom.plan", ParamSchema.of(Set.of(), Set.of()));
        GraphMemory graphMemory = new GraphMemory();
        graphMemory.addNode("u1", new com.zhongbo.mindos.assistant.memory.graph.MemoryNode(
                "entity:student:42",
                "entity.student",
                Map.of("studentId", "stu-42"),
                null,
                null
        ));
        ParamValidator validator = new SimpleParamValidator(registry, dispatcherMemoryFacade(noopGateway(), graphMemory));
        Skill retryingSkill = new Skill() {
            private int calls;

            @Override
            public String name() {
                return "custom.plan";
            }

            @Override
            public String description() {
                return "custom.plan";
            }

            @Override
            public SkillResult run(SkillContext context) {
                calls++;
                if (context.attributes().get("studentId") == null) {
                    return SkillResult.failure(name(), "缺少必填参数: studentId");
                }
                return SkillResult.success(name(), "recovered on call " + calls);
            }
        };
        SkillRegistry skillRegistry = new SkillRegistry(List.of(retryingSkill));
        SkillDslExecutor dslExecutor = new SkillDslExecutor(skillRegistry);
        SkillEngine skillEngine = new SkillEngine(skillRegistry, dslExecutor);
        DefaultDecisionOrchestrator orchestrator = new DefaultDecisionOrchestrator(
                new SimpleCandidatePlanner(skillEngine, dispatcherMemoryFacade(gatewayWithHistory(List.of())), 3, 0.40, 0.35, 0.15, 0.10),
                validator,
                new SimpleConversationLoop(),
                new SimpleFallbackPlan(),
                new DefaultSkillExecutionGateway(skillRegistry, dslExecutor),
                noopGateway(),
                noopRecorder(),
                new TaskExecutor(3),
                false,
                500,
                0,
                "",
                3
        );

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(
                new Decision("custom", "custom.plan", Map.of(), 0.85, false),
                new DecisionOrchestrator.OrchestrationRequest("u1", "please plan", new SkillContext("u1", "please plan", Map.of()), Map.of())
        );

        assertTrue(outcome.hasResult());
        assertTrue(outcome.result().success());
        assertEquals(1, outcome.trace().replanCount());
        assertTrue(outcome.result().output().contains("recovered"));
    }

    @Test
    void shouldExecuteTaskPlanSequentially() {
        Skill fetchSkill = new Skill() {
            @Override
            public String name() {
                return "step.fetch";
            }

            @Override
            public String description() {
                return "step.fetch";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name(), "headlines");
            }
        };
        Skill summarizeSkill = new Skill() {
            @Override
            public String name() {
                return "step.summarize";
            }

            @Override
            public String description() {
                return "step.summarize";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name(), "summary:" + context.attributes().get("source"));
            }
        };
        SkillRegistry registry = new SkillRegistry(List.of(fetchSkill, summarizeSkill));
        SkillDslExecutor dslExecutor = new SkillDslExecutor(registry);
        SkillEngine skillEngine = new SkillEngine(registry, dslExecutor);
        DefaultDecisionOrchestrator orchestrator = new DefaultDecisionOrchestrator(
                new SimpleCandidatePlanner(skillEngine, dispatcherMemoryFacade(noopGateway()), 3, 0.40, 0.35, 0.15, 0.10),
                new SimpleParamValidator(new InMemoryParamSchemaRegistry(), noopGateway()),
                new SimpleConversationLoop(),
                new SimpleFallbackPlan(),
                new DefaultSkillExecutionGateway(registry, dslExecutor),
                noopGateway(),
                noopRecorder(),
                new TaskExecutor(3),
                false,
                500,
                0,
                "",
                3
        );

        Decision decision = new Decision("task", "task.plan", Map.of(
                "steps", List.of(
                        Map.of("id", "fetch", "target", "step.fetch", "params", Map.of("query", "today news"), "saveAs", "news"),
                        Map.of("id", "summarize", "target", "step.summarize", "params", Map.of("source", "${task.news.output}"), "saveAs", "summary")
                )
        ), 0.90, false);

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(decision, request());

        assertTrue(outcome.hasResult());
        assertEquals("step.summarize", outcome.result().skillName());
        assertEquals("summary:headlines", outcome.result().output());
        assertEquals(2, outcome.trace().steps().size());
    }

    @Test
    void shouldExecuteTaskPlanFromTasksAlias() {
        Skill fetchSkill = new Skill() {
            @Override
            public String name() {
                return "student.get";
            }

            @Override
            public String description() {
                return "student.get";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name(), "student:stu-42");
            }
        };
        Skill analyzeSkill = new Skill() {
            @Override
            public String name() {
                return "student.analyze";
            }

            @Override
            public String description() {
                return "student.analyze";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name(), "analysis:" + context.attributes().get("student"));
            }
        };
        SkillRegistry registry = new SkillRegistry(List.of(fetchSkill, analyzeSkill));
        SkillDslExecutor dslExecutor = new SkillDslExecutor(registry);
        SkillEngine skillEngine = new SkillEngine(registry, dslExecutor);
        DefaultDecisionOrchestrator orchestrator = new DefaultDecisionOrchestrator(
                new SimpleCandidatePlanner(skillEngine, dispatcherMemoryFacade(noopGateway()), 3, 0.40, 0.35, 0.15, 0.10),
                new SimpleParamValidator(new InMemoryParamSchemaRegistry(), noopGateway()),
                new SimpleConversationLoop(),
                new SimpleFallbackPlan(),
                new DefaultSkillExecutionGateway(registry, dslExecutor),
                noopGateway(),
                noopRecorder(),
                new TaskExecutor(3),
                false,
                500,
                0,
                "",
                3
        );

        Decision decision = new Decision("student.plan", "student.get", Map.of(
                "tasks", List.of(
                        Map.of("target", "student.get", "saveAs", "student"),
                        Map.of("target", "student.analyze", "params", Map.of("student", "${task.student.output}"))
                )
        ), 0.65, false);

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(decision, request());

        assertTrue(outcome.hasResult());
        assertEquals("student.analyze", outcome.result().skillName());
        assertEquals("analysis:student:stu-42", outcome.result().output());
        assertEquals(2, outcome.trace().steps().size());
    }

    @Test
    void shouldExecuteDagTaskGraphFromNodesAndEdges() {
        Skill fetchSkill = new Skill() {
            @Override
            public String name() {
                return "student.get";
            }

            @Override
            public String description() {
                return "student.get";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name(), "student:stu-42");
            }
        };
        Skill analyzeSkill = new Skill() {
            @Override
            public String name() {
                return "student.analyze";
            }

            @Override
            public String description() {
                return "student.analyze";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name(), "analysis:" + context.attributes().get("student"));
            }
        };
        Skill planSkill = new Skill() {
            @Override
            public String name() {
                return "teaching.plan";
            }

            @Override
            public String description() {
                return "teaching.plan";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name(), "plan:" + context.attributes().get("analysis"));
            }
        };
        SkillRegistry registry = new SkillRegistry(List.of(fetchSkill, analyzeSkill, planSkill));
        SkillDslExecutor dslExecutor = new SkillDslExecutor(registry);
        SkillEngine skillEngine = new SkillEngine(registry, dslExecutor);
        DefaultDecisionOrchestrator orchestrator = new DefaultDecisionOrchestrator(
                new SimpleCandidatePlanner(skillEngine, dispatcherMemoryFacade(noopGateway()), 3, 0.40, 0.35, 0.15, 0.10),
                new SimpleParamValidator(new InMemoryParamSchemaRegistry(), noopGateway()),
                new SimpleConversationLoop(),
                new SimpleFallbackPlan(),
                new DefaultSkillExecutionGateway(registry, dslExecutor),
                noopGateway(),
                noopRecorder(),
                new TaskExecutor(3),
                false,
                500,
                0,
                "",
                3
        );

        Decision decision = new Decision("student.plan", "task.plan", Map.of(
                "nodes", List.of(
                        Map.of("id", "fetch", "target", "student.get", "saveAs", "student"),
                        Map.of("id", "analyze", "target", "student.analyze", "params", Map.of("student", "${task.student.output}"), "saveAs", "analysis"),
                        Map.of("id", "plan", "target", "teaching.plan", "params", Map.of("analysis", "${task.analysis.output}"), "saveAs", "plan")
                ),
                "edges", List.of(
                        Map.of("from", "fetch", "to", "analyze"),
                        Map.of("from", "analyze", "to", "plan")
                )
        ), 0.55, false);

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(decision, request());

        assertTrue(outcome.hasResult());
        assertEquals("slow-path", outcome.trace().strategy());
        assertEquals("teaching.plan", outcome.result().skillName());
        assertEquals("plan:analysis:student:stu-42", outcome.result().output());
        assertEquals(3, outcome.trace().steps().size());
    }

    @Test
    void shouldAutoPromoteLowConfidenceDecisionToTaskPlan() {
        Skill studentGet = new Skill() {
            @Override
            public String name() {
                return "student.get";
            }

            @Override
            public String description() {
                return "student.get";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name(), "student:stu-42");
            }
        };
        Skill studentAnalyze = new Skill() {
            @Override
            public String name() {
                return "student.analyze";
            }

            @Override
            public String description() {
                return "student.analyze";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name(), "analysis:" + context.attributes().get("task.last.output"));
            }
        };
        Skill teachingPlan = new Skill() {
            @Override
            public String name() {
                return "teaching.plan";
            }

            @Override
            public String description() {
                return "teaching.plan";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name(), "plan:" + context.attributes().get("task.last.output"));
            }
        };
        SkillRegistry registry = new SkillRegistry(List.of(studentGet, studentAnalyze, teachingPlan));
        SkillDslExecutor dslExecutor = new SkillDslExecutor(registry);
        SkillEngine skillEngine = new SkillEngine(registry, dslExecutor);
        DefaultDecisionOrchestrator orchestrator = new DefaultDecisionOrchestrator(
                new CandidatePlanner() {
                    @Override
                    public List<ScoredCandidate> plan(String suggestedTarget, DecisionOrchestrator.OrchestrationRequest request) {
                        if (!"student.plan".equals(suggestedTarget)) {
                            return List.of(new ScoredCandidate(suggestedTarget, 0.95, 0.8, 0.0, 0.5, List.of("exact-step")));
                        }
                        return List.of(
                                new ScoredCandidate("student.get", 0.91, 0.8, 0.0, 0.5, List.of("candidate-1")),
                                new ScoredCandidate("student.analyze", 0.87, 0.7, 0.0, 0.5, List.of("candidate-2")),
                                new ScoredCandidate("teaching.plan", 0.83, 0.6, 0.0, 0.5, List.of("candidate-3"))
                        );
                    }
                },
                new SimpleParamValidator(new InMemoryParamSchemaRegistry(), noopGateway()),
                new SimpleConversationLoop(),
                new SimpleFallbackPlan(),
                new DefaultSkillExecutionGateway(registry, dslExecutor),
                noopGateway(),
                noopRecorder(),
                new TaskExecutor(3),
                false,
                500,
                0,
                "",
                3
        );

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(
                new Decision("student.plan", "student.plan", Map.of("studentId", "stu-42"), 0.62, false),
                request()
        );

        assertTrue(outcome.hasResult());
        assertEquals("slow-path", outcome.trace().strategy());
        assertEquals("teaching.plan", outcome.result().skillName());
        assertEquals("plan:analysis:student:stu-42", outcome.result().output());
        assertEquals(3, outcome.trace().steps().size());
    }

    @Test
    void shouldFallbackFromFastPathToSlowPathWhenFastPathFails() {
        Skill primary = new Skill() {
            @Override
            public String name() {
                return "todo.create";
            }

            @Override
            public String description() {
                return "todo.create";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.failure(name(), "primary failed");
            }
        };
        Skill backup = new Skill() {
            @Override
            public String name() {
                return "todo.create.backup";
            }

            @Override
            public String description() {
                return "todo.create.backup";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name(), "backup created:" + context.attributes().get("task"));
            }
        };
        SkillRegistry registry = new SkillRegistry(List.of(primary, backup));
        SkillDslExecutor dslExecutor = new SkillDslExecutor(registry);
        DefaultDecisionOrchestrator orchestrator = new DefaultDecisionOrchestrator(
                new CandidatePlanner() {
                    @Override
                    public List<ScoredCandidate> plan(String suggestedTarget, DecisionOrchestrator.OrchestrationRequest request) {
                        return List.of(
                                new ScoredCandidate("todo.create", 0.95, 0.9, 0.0, 0.5, List.of("primary")),
                                new ScoredCandidate("todo.create.backup", 0.85, 0.7, 0.0, 0.5, List.of("backup"))
                        );
                    }
                },
                new SimpleParamValidator(new InMemoryParamSchemaRegistry(), noopGateway()),
                new SimpleConversationLoop(),
                new SimpleFallbackPlan(),
                new DefaultSkillExecutionGateway(registry, dslExecutor),
                noopGateway(),
                noopRecorder(),
                new TaskExecutor(3),
                false,
                500,
                0,
                "",
                3
        );

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(
                new Decision("todo", "todo.create", Map.of("task", "demo"), 0.92, false),
                request()
        );

        assertTrue(outcome.hasResult());
        assertTrue(outcome.result().success());
        assertEquals("slow-path", outcome.trace().strategy());
        assertEquals("todo.create.backup", outcome.result().skillName());
        assertEquals("backup created:demo", outcome.result().output());
    }

    @Test
    void shouldRetrySingleSkillAfterRecoveryPlan() {
        AtomicInteger calls = new AtomicInteger();
        Skill flaky = new Skill() {
            @Override
            public String name() {
                return "flaky.plan";
            }

            @Override
            public String description() {
                return "flaky.plan";
            }

            @Override
            public SkillResult run(SkillContext context) {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    return SkillResult.failure(name(), "transient failure");
                }
                return SkillResult.success(name(), "recovered on call " + call);
            }
        };
        SkillRegistry registry = new SkillRegistry(List.of(flaky));
        SkillDslExecutor dslExecutor = new SkillDslExecutor(registry);
        DefaultDecisionOrchestrator orchestrator = new DefaultDecisionOrchestrator(
                new SimpleCandidatePlanner(new SkillEngine(registry, dslExecutor), dispatcherMemoryFacade(noopGateway()), 3, 0.40, 0.35, 0.15, 0.10),
                new SimpleParamValidator(new InMemoryParamSchemaRegistry(), noopGateway()),
                new SimpleConversationLoop(),
                new SimpleFallbackPlan(),
                new DefaultSkillExecutionGateway(registry, dslExecutor),
                noopGateway(),
                noopRecorder(),
                new TaskExecutor(3),
                false,
                500,
                0,
                "",
                3
        );
        orchestrator.setRecoveryManager(new RecoveryManager() {
            @Override
            public RecoveryReport planRetry(String traceId, String skillName, SkillResult failure, Map<String, Object> currentContext, List<com.zhongbo.mindos.assistant.common.dto.PlanStepDto> executedSteps) {
                RecoveryAction action =
                        RecoveryAction.retry(
                                "retry-node",
                                skillName,
                                1,
                                List.of("task.last.output", "task.last.skill", "task.last.success"),
                                Map.of("recovery.stage", "retry"),
                                "transient"
                        );
                return new RecoveryReport(
                        traceId,
                        "retry",
                        false,
                        null,
                        List.of(action),
                        List.of(action.nodeId()),
                        List.of(),
                        List.of(),
                        action.clearKeys(),
                        action.contextPatch(),
                        new TaskGraph(List.of(), List.of()),
                        "retry " + skillName
                );
            }

            @Override
            public RecoveryReport planRollback(String traceId, TaskGraph graph, com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutionResult result, Map<String, Object> currentContext) {
                return RecoveryReport.noop(traceId, "rollback", "not used");
            }
        });

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(
                new Decision("flaky", "flaky.plan", Map.of(), 0.92, false),
                request()
        );

        assertTrue(outcome.hasResult());
        assertTrue(outcome.result().success());
        assertEquals("flaky.plan", outcome.result().skillName());
        assertEquals("recovered on call 2", outcome.result().output());
        assertEquals(2, outcome.trace().steps().size());
    }

    @Test
    void shouldFallbackTaskGraphAfterRecoveryPlan() {
        Skill primary = new Skill() {
            @Override
            public String name() {
                return "primary.fetch";
            }

            @Override
            public String description() {
                return "primary.fetch";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.failure(name(), "primary failed");
            }
        };
        Skill backup = new Skill() {
            @Override
            public String name() {
                return "backup.fetch";
            }

            @Override
            public String description() {
                return "backup.fetch";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name(), "backup recovered");
            }
        };
        SkillRegistry registry = new SkillRegistry(List.of(primary, backup));
        SkillDslExecutor dslExecutor = new SkillDslExecutor(registry);
        DefaultDecisionOrchestrator orchestrator = new DefaultDecisionOrchestrator(
                new SimpleCandidatePlanner(new SkillEngine(registry, dslExecutor), dispatcherMemoryFacade(noopGateway()), 3, 0.40, 0.35, 0.15, 0.10),
                new SimpleParamValidator(new InMemoryParamSchemaRegistry(), noopGateway()),
                new SimpleConversationLoop(),
                new SimpleFallbackPlan(),
                new DefaultSkillExecutionGateway(registry, dslExecutor),
                noopGateway(),
                noopRecorder(),
                new TaskExecutor(3),
                false,
                500,
                0,
                "",
                3
        );
        orchestrator.setRecoveryManager(new RecoveryManager() {
            @Override
            public RecoveryReport planRetry(String traceId, String skillName, SkillResult failure, Map<String, Object> currentContext, List<com.zhongbo.mindos.assistant.common.dto.PlanStepDto> executedSteps) {
                return RecoveryReport.noop(traceId, "retry", "not used");
            }

            @Override
            public RecoveryReport planRollback(String traceId, TaskGraph graph, com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutionResult result, Map<String, Object> currentContext) {
                RecoveryAction action =
                        RecoveryAction.fallback(
                                "fetch",
                                "primary.fetch",
                                "backup.fetch",
                                "",
                                List.of("task.fetch.output", "task.fetch.skill", "task.fetch.success"),
                                Map.of("recovery.stage", "fallback"),
                                "primary failed"
                        );
                return new RecoveryReport(
                        traceId,
                        "rollback",
                        true,
                        null,
                        List.of(action),
                        List.of(),
                        List.of(action.nodeId()),
                        List.of(),
                        action.clearKeys(),
                        action.contextPatch(),
                        graph,
                        "fallback to backup"
                );
            }
        });

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(
                new Decision("plan", "task.plan", Map.of(
                        "nodes", List.of(
                                Map.of("id", "fetch", "target", "primary.fetch")
                        )
                ), 0.55, false),
                request()
        );

        assertTrue(outcome.hasResult());
        assertTrue(outcome.result().success());
        assertEquals("backup.fetch", outcome.result().skillName());
        assertEquals("backup recovered", outcome.result().output());
        assertEquals(2, outcome.trace().steps().size());
    }

    @Test
    void shouldReuseRecordedProcedureBeforePlanning() {
        Skill queryWeather = new Skill() {
            @Override
            public String name() {
                return "query_weather";
            }

            @Override
            public String description() {
                return "query_weather";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name(), "weather:sunny");
            }
        };
        Skill sendDingtalk = new Skill() {
            @Override
            public String name() {
                return "send_dingtalk";
            }

            @Override
            public String description() {
                return "send_dingtalk";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name(), "dingtalk:" + context.attributes().get("task.weather.output"));
            }
        };
        SkillRegistry registry = new SkillRegistry(List.of(queryWeather, sendDingtalk));
        SkillDslExecutor dslExecutor = new SkillDslExecutor(registry);
        InMemoryProcedureMemoryEngine procedureEngine = new InMemoryProcedureMemoryEngine();
        ProceduralMemory proceduralMemory = new ProceduralMemory(procedureEngine);
        DefaultDecisionOrchestrator orchestrator = new DefaultDecisionOrchestrator(
                new CandidatePlanner() {
                    @Override
                    public List<ScoredCandidate> plan(String suggestedTarget, DecisionOrchestrator.OrchestrationRequest request) {
                        return List.of(new ScoredCandidate("query_weather", 0.9, 0.8, 0.0, 0.5, List.of("candidate")));
                    }
                },
                new SimpleParamValidator(new InMemoryParamSchemaRegistry(), noopGateway()),
                new SimpleConversationLoop(),
                new SimpleFallbackPlan(),
                new DefaultSkillExecutionGateway(registry, dslExecutor),
                noopGateway(),
                noopRecorder(),
                new TaskExecutor(3),
                false,
                500,
                0,
                "",
                3
        );
        orchestrator.setProceduralMemory(proceduralMemory);

        Decision firstDecision = new Decision("weather.notify", "task.plan", Map.of(
                "nodes", List.of(
                        Map.of("id", "query", "target", "query_weather", "saveAs", "weather"),
                        Map.of("id", "notify", "target", "send_dingtalk", "params", Map.of("content", "${task.weather.output}"), "saveAs", "notify")
                ),
                "edges", List.of(Map.of("from", "query", "to", "notify"))
        ), 0.55, false);

        DecisionOrchestrator.OrchestrationOutcome firstOutcome = orchestrator.orchestrate(firstDecision, request());
        assertTrue(firstOutcome.hasResult());
        assertEquals("send_dingtalk", firstOutcome.result().skillName());

        Decision secondDecision = new Decision("weather.notify", "weather.notify", Map.of(), 0.55, false);
        DecisionOrchestrator.OrchestrationOutcome secondOutcome = orchestrator.orchestrate(
                secondDecision,
                new DecisionOrchestrator.OrchestrationRequest("user", "查天气并发钉钉", new SkillContext("user", "查天气并发钉钉", Map.of()), Map.of())
        );

        assertTrue(secondOutcome.hasResult());
        assertEquals("procedural-memory", secondOutcome.trace().strategy());
        assertEquals("send_dingtalk", secondOutcome.result().skillName());
        assertEquals("dingtalk:weather:sunny", secondOutcome.result().output());
    }

    @Test
    void shouldExecuteViaConvenienceEntrypoint() {
        SkillRuntime skillRuntime = simpleSkillRuntime(Map.of("todo.create", SkillResult.success("todo.create", "created")));
        DefaultDecisionOrchestrator orchestrator = orchestrator(skillRuntime, false);

        SkillResult result = orchestrator.execute("创建待办", "todo.create", Map.of("task", "demo"));

        assertTrue(result.success());
        assertEquals("todo.create", result.skillName());
        assertEquals("created", result.output());
    }

    @Test
    void shouldReturnUnifiedFailurePayloadWhenAllCandidatesFail() {
        SkillRuntime failedRuntime = simpleSkillRuntime(Map.of(
                "todo.create", SkillResult.failure("todo.create", "primary failed"),
                "todo.create.backup", SkillResult.failure("todo.create.backup", "backup failed")
        ));
        DefaultDecisionOrchestrator orchestrator = new DefaultDecisionOrchestrator(
                new CandidatePlanner() {
                    @Override
                    public List<ScoredCandidate> plan(String suggestedTarget, DecisionOrchestrator.OrchestrationRequest request) {
                        return List.of(
                                new ScoredCandidate("todo.create", 0.9, 0.8, 0.0, 0.5, List.of("primary")),
                                new ScoredCandidate("todo.create.backup", 0.8, 0.7, 0.0, 0.5, List.of("fallback"))
                        );
                    }
                },
                new SimpleParamValidator(new InMemoryParamSchemaRegistry(), noopGateway()),
                new SimpleConversationLoop(),
                new SimpleFallbackPlan(),
                failedRuntime.executionGateway(),
                noopGateway(),
                noopRecorder(),
                new TaskExecutor(3),
                false,
                500,
                0,
                "",
                3
        );

        SkillResult result = orchestrator.execute("创建待办", "todo.create", Map.of("task", "demo"));

        assertFalse(result.success());
        assertEquals("decision.orchestrator", result.skillName());
        assertTrue(result.output().contains("\"status\":\"failed\""));
        assertTrue(result.output().contains("\"intent\":\"todo.create\""));
        assertTrue(result.output().contains("\"attemptedCandidates\""));
    }

    private SkillRuntime simpleSkillRuntime(Map<String, SkillResult> results) {
        List<Skill> skills = results.entrySet().stream()
                .map(entry -> (Skill) new Skill() {
                    @Override
                    public String name() {
                        return entry.getKey();
                    }

                    @Override
                    public String description() {
                        return "stub skill";
                    }

                    @Override
                    public SkillResult run(SkillContext context) {
                        return entry.getValue();
                    }
                }).toList();
        SkillRegistry registry = new SkillRegistry(skills);
        SkillDslExecutor executor = new SkillDslExecutor(registry);
        return new SkillRuntime(new SkillEngine(registry, executor), new DefaultSkillExecutionGateway(registry, executor));
    }

    private record SkillRuntime(SkillEngine skillEngine, SkillExecutionGateway executionGateway) {
    }

    private PostExecutionMemoryRecorder noopRecorder() {
        return new PostExecutionMemoryRecorder(noopGateway(), false, false, "", 280);
    }

    private DispatcherMemoryFacade dispatcherMemoryFacade(MemoryGateway memoryGateway) {
        return new DispatcherMemoryFacade(memoryGateway, null, null);
    }

    private DispatcherMemoryFacade dispatcherMemoryFacade(MemoryGateway memoryGateway, GraphMemory graphMemory) {
        return new DispatcherMemoryFacade(new com.zhongbo.mindos.assistant.memory.MemoryFacade(graphMemory, null), memoryGateway, graphMemory, null);
    }

    private MemoryGateway noopGateway() {
        return new MemoryGateway() {
            @Override
            public java.util.List<com.zhongbo.mindos.assistant.memory.model.ConversationTurn> recentHistory(String userId) {
                return java.util.List.of();
            }

            @Override
            public java.util.List<com.zhongbo.mindos.assistant.memory.model.SkillUsageStats> skillUsageStats(String userId) {
                return java.util.List.of();
            }

            @Override
            public void appendUserConversation(String userId, String message) {
            }

            @Override
            public void appendAssistantConversation(String userId, String message) {
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
            public void writeSemantic(String userId, String text, java.util.List<Double> embedding, String bucket) {
            }

            @Override
            public com.zhongbo.mindos.assistant.memory.model.PreferenceProfile updatePreferenceProfile(
                    String userId,
                    com.zhongbo.mindos.assistant.memory.model.PreferenceProfile profile) {
                return com.zhongbo.mindos.assistant.memory.model.PreferenceProfile.empty();
            }

            @Override
            public com.zhongbo.mindos.assistant.memory.model.LongTask createLongTask(
                    String userId,
                    String title,
                    String objective,
                    java.util.List<String> steps,
                    java.time.Instant dueAt,
                    java.time.Instant nextCheckAt) {
                return null;
            }

            @Override
            public com.zhongbo.mindos.assistant.memory.model.LongTask updateLongTaskProgress(
                    String userId,
                    String taskId,
                    String workerId,
                    String completedStep,
                    String note,
                    String blockedReason,
                    java.time.Instant nextCheckAt,
                    boolean markCompleted) {
                return null;
            }

            @Override
            public com.zhongbo.mindos.assistant.memory.model.LongTask updateLongTaskStatus(
                    String userId,
                    String taskId,
                    com.zhongbo.mindos.assistant.memory.model.LongTaskStatus status,
                    String note,
                    java.time.Instant nextCheckAt) {
                return null;
            }
        };
    }

    private MemoryGateway gatewayWithHistory(java.util.List<com.zhongbo.mindos.assistant.memory.model.ConversationTurn> history) {
        return new MemoryGateway() {
            @Override
            public java.util.List<com.zhongbo.mindos.assistant.memory.model.ConversationTurn> recentHistory(String userId) {
                return history;
            }

            @Override
            public java.util.List<com.zhongbo.mindos.assistant.memory.model.SkillUsageStats> skillUsageStats(String userId) {
                return java.util.List.of();
            }

            @Override
            public void appendUserConversation(String userId, String message) {
            }

            @Override
            public void appendAssistantConversation(String userId, String message) {
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
            public void writeSemantic(String userId, String text, java.util.List<Double> embedding, String bucket) {
            }

            @Override
            public com.zhongbo.mindos.assistant.memory.model.PreferenceProfile updatePreferenceProfile(String userId, com.zhongbo.mindos.assistant.memory.model.PreferenceProfile profile) {
                return com.zhongbo.mindos.assistant.memory.model.PreferenceProfile.empty();
            }

            @Override
            public com.zhongbo.mindos.assistant.memory.model.LongTask createLongTask(String userId, String title, String objective, java.util.List<String> steps, java.time.Instant dueAt, java.time.Instant nextCheckAt) {
                return null;
            }

            @Override
            public com.zhongbo.mindos.assistant.memory.model.LongTask updateLongTaskProgress(String userId, String taskId, String workerId, String completedStep, String note, String blockedReason, java.time.Instant nextCheckAt, boolean markCompleted) {
                return null;
            }

            @Override
            public com.zhongbo.mindos.assistant.memory.model.LongTask updateLongTaskStatus(String userId, String taskId, com.zhongbo.mindos.assistant.memory.model.LongTaskStatus status, String note, java.time.Instant nextCheckAt) {
                return null;
            }
        };
    }
}
