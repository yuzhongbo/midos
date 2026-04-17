package com.zhongbo.mindos.assistant.dispatcher.system;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.DecisionCapabilityCatalog;
import com.zhongbo.mindos.assistant.skill.SkillExecutionGateway;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class SkillCompositionService {

    public static final String DEVELOPMENT_WORKFLOW_REASON = "systemWorkflow=development";
    public static final String WEB_LOOKUP_DETAIL_WORKFLOW_REASON = "systemWorkflow=skill-graph:web.lookup.detail";
    public static final String DOCS_LOOKUP_DETAIL_WORKFLOW_REASON = "systemWorkflow=skill-graph:docs.lookup.detail";

    private static final String EXECUTION_TARGET_TOKEN = "$executionTarget";

    private final SkillExecutionGateway skillExecutionGateway;
    private final WebLookupDetailHelper webLookupDetailHelper;

    public SkillCompositionService(SkillExecutionGateway skillExecutionGateway) {
        this(skillExecutionGateway, new WebLookupDetailHelper());
    }

    public SkillCompositionService(SkillExecutionGateway skillExecutionGateway,
                                   WebLookupDetailHelper webLookupDetailHelper) {
        this.skillExecutionGateway = skillExecutionGateway;
        this.webLookupDetailHelper = webLookupDetailHelper == null ? new WebLookupDetailHelper() : webLookupDetailHelper;
    }

    public boolean supports(String decisionTarget, String executionTarget) {
        return resolveRecipe(decisionTarget, executionTarget).isPresent();
    }

    public String workflowReasonFor(String decisionTarget, String executionTarget) {
        return resolveRecipe(decisionTarget, executionTarget)
                .map(SkillRecipe::workflowReason)
                .orElse("");
    }

    public SkillResult execute(String decisionTarget,
                               String executionTarget,
                               Map<String, Object> params,
                               SkillContext context) {
        Optional<SkillRecipe> resolved = resolveRecipe(decisionTarget, executionTarget);
        if (resolved.isEmpty()) {
            return SkillResult.failure(executionTarget == null || executionTarget.isBlank() ? decisionTarget : executionTarget,
                    "unsupported skill composition target");
        }
        SkillRecipe recipe = resolved.get();
        if (!recipe.safeAutoRun()) {
            return SkillResult.failure(executionTarget == null || executionTarget.isBlank() ? decisionTarget : executionTarget,
                    "skill composition recipe is not safe to auto-run");
        }
        if (recipe.steps().size() > recipe.maxDepth()) {
            return SkillResult.failure(executionTarget == null || executionTarget.isBlank() ? decisionTarget : executionTarget,
                    "skill composition recipe exceeds max depth");
        }
        Map<String, Object> requestParams = params == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(params));
        SkillContext recipeContext = enrichWorkflowContext(recipe, decisionTarget, executionTarget, context);
        Map<String, SkillResult> stepResults = new LinkedHashMap<>();
        SkillResult lastSuccessful = null;
        for (SkillRecipeStep step : recipe.steps()) {
            String resolvedStepTarget = resolveStepTarget(step.target(), executionTarget);
            Map<String, Object> stepParams = buildStepParams(step, requestParams, stepResults);
            SkillResult stepResult = executeStep(step, resolvedStepTarget, stepParams, recipeContext);
            stepResults.put(step.id(), stepResult);
            if (stepResult != null && stepResult.success()) {
                lastSuccessful = decorateWorkflowResult(
                        recipe,
                        decisionTarget,
                        executionTarget,
                        normalizeExecutionResult(stepResult, executionTarget),
                        requestParams,
                        stepResults
                );
                continue;
            }
            if (step.failurePolicy() == SkillRecipeFailurePolicy.RETURN_PREVIOUS_SUCCESS && lastSuccessful != null) {
                return lastSuccessful;
            }
            String failureOutput = stepResult == null || stepResult.output() == null || stepResult.output().isBlank()
                    ? "skill composition step failed"
                    : stepResult.output();
            return SkillResult.failure(executionTarget == null || executionTarget.isBlank() ? resolvedStepTarget : executionTarget, failureOutput);
        }
        if (lastSuccessful != null) {
            return lastSuccessful;
        }
        return SkillResult.failure(executionTarget == null || executionTarget.isBlank() ? decisionTarget : executionTarget,
                "skill composition produced no result");
    }

    private SkillResult normalizeExecutionResult(SkillResult result, String executionTarget) {
        if (result == null) {
            return SkillResult.failure(executionTarget, "skill composition step failed");
        }
        if (normalize(result.skillName()).equals(normalize(executionTarget))) {
            return result;
        }
        return result.relabel(executionTarget);
    }

    private SkillResult decorateWorkflowResult(SkillRecipe recipe,
                                              String decisionTarget,
                                              String executionTarget,
                                              SkillResult result,
                                              Map<String, Object> requestParams,
                                              Map<String, SkillResult> stepResults) {
        if (result == null) {
            return SkillResult.failure(executionTarget, "skill composition produced no result");
        }
        SkillResult decorated = result.withMetadata(Map.of(
                "systemWorkflow", recipe.workflowName(),
                "systemWorkflowRecipe", recipe.id(),
                "systemWorkflowCapability", DecisionCapabilityCatalog.decisionTarget(executionTarget),
                "systemWorkflowExecutionTarget", executionTarget == null ? "" : executionTarget,
                "systemWorkflowDecisionTarget", decisionTarget == null ? "" : decisionTarget,
                "systemWorkflowStepCount", recipe.steps().size(),
                "systemWorkflowSteps", recipe.steps().stream().map(SkillRecipeStep::id).toList()
        ));
        LinkedHashMap<String, Object> artifacts = new LinkedHashMap<>();
        putIfPresent(artifacts, "query", requestParams.get("query"));
        SkillResult searchResult = stepResults.get("search");
        if (searchResult != null && searchResult.output() != null && !searchResult.output().isBlank()) {
            artifacts.put("searchOutput", searchResult.output());
        }
        SkillResult detailResult = stepResults.get("detail");
        if (detailResult != null && detailResult.success()) {
            artifacts.put("detailApplied", detailResult.artifacts().getOrDefault("detailApplied", Boolean.TRUE));
            putIfPresent(artifacts, "detailTitle", detailResult.artifacts().get("detailTitle"));
            putIfPresent(artifacts, "detailLink", detailResult.artifacts().get("detailLink"));
            putIfPresent(artifacts, "detailSummary", detailResult.artifacts().get("detailSummary"));
        }
        return decorated.withArtifacts(artifacts);
    }

    private SkillResult executeStep(SkillRecipeStep step,
                                    String resolvedStepTarget,
                                    Map<String, Object> stepParams,
                                    SkillContext recipeContext) {
        if (WebLookupDetailHelper.HELPER_TARGET.equals(resolvedStepTarget)) {
            return webLookupDetailHelper.enrich(
                    stringValue(stepParams.get("query")),
                    stringValue(stepParams.get("searchOutput"))
            );
        }
        if (skillExecutionGateway == null) {
            return SkillResult.failure(resolvedStepTarget, "skill execution gateway unavailable");
        }
        SkillContext stepContext = buildStepContext(step, resolvedStepTarget, recipeContext);
        try {
            return skillExecutionGateway.executeDslAsync(
                    new SkillDsl(resolvedStepTarget, stepParams),
                    stepContext
            ).join();
        } catch (RuntimeException ex) {
            return SkillResult.failure(resolvedStepTarget, ex.getMessage() == null ? "skill composition step failed" : ex.getMessage());
        }
    }

    private SkillContext buildStepContext(SkillRecipeStep step,
                                          String resolvedStepTarget,
                                          SkillContext recipeContext) {
        Map<String, Object> attributes = new LinkedHashMap<>(recipeContext == null || recipeContext.attributes() == null
                ? Map.of()
                : recipeContext.attributes());
        attributes.put("systemWorkflowStep", step.id());
        attributes.put("systemWorkflowStepTarget", resolvedStepTarget);
        return new SkillContext(
                recipeContext == null ? "" : recipeContext.userId(),
                recipeContext == null ? "" : recipeContext.input(),
                attributes
        );
    }

    private SkillContext enrichWorkflowContext(SkillRecipe recipe,
                                               String decisionTarget,
                                               String executionTarget,
                                               SkillContext context) {
        Map<String, Object> attributes = new LinkedHashMap<>(context == null || context.attributes() == null
                ? Map.of()
                : context.attributes());
        attributes.put("systemWorkflow", recipe.workflowName());
        attributes.put("systemWorkflowRecipe", recipe.id());
        attributes.put("systemWorkflowCapability", DecisionCapabilityCatalog.decisionTarget(executionTarget));
        attributes.put("systemWorkflowExecutionTarget", executionTarget == null ? "" : executionTarget);
        attributes.put("systemWorkflowDecisionTarget", decisionTarget == null ? "" : decisionTarget);
        return new SkillContext(
                context == null ? "" : context.userId(),
                context == null ? "" : context.input(),
                attributes
        );
    }

    private Map<String, Object> buildStepParams(SkillRecipeStep step,
                                                Map<String, Object> requestParams,
                                                Map<String, SkillResult> stepResults) {
        Map<String, Object> resolved = new LinkedHashMap<>(requestParams);
        step.bindings().forEach((key, ref) -> {
            Object value = resolveBindingValue(ref, requestParams, stepResults);
            if (value == null) {
                return;
            }
            if (value instanceof String text && text.isBlank()) {
                return;
            }
            resolved.put(key, value);
        });
        return Map.copyOf(resolved);
    }

    private Object resolveBindingValue(SkillRecipeValueRef ref,
                                       Map<String, Object> requestParams,
                                       Map<String, SkillResult> stepResults) {
        if (ref == null) {
            return null;
        }
        return switch (ref.source()) {
            case LITERAL -> ref.literal();
            case REQUEST_PARAM -> requestParams.get(ref.key());
            case STEP_OUTPUT -> stepResults.containsKey(ref.key()) && stepResults.get(ref.key()) != null
                    ? stepResults.get(ref.key()).output()
                    : null;
        };
    }

    private Optional<SkillRecipe> resolveRecipe(String decisionTarget, String executionTarget) {
        if (matchesCodeAssist(decisionTarget, executionTarget)) {
            return Optional.of(codeAssistRecipe());
        }
        if (matchesWebLookup(decisionTarget, executionTarget)) {
            return Optional.of(webLookupDetailRecipe());
        }
        if (matchesDocsLookup(decisionTarget, executionTarget)) {
            return Optional.of(docsLookupDetailRecipe());
        }
        return Optional.empty();
    }

    private boolean matchesCodeAssist(String decisionTarget, String executionTarget) {
        String normalizedDecisionTarget = normalize(decisionTarget);
        String normalizedExecutionTarget = normalize(executionTarget);
        return "code.assist".equals(normalizedDecisionTarget) || "code.generate".equals(normalizedExecutionTarget);
    }

    private boolean matchesWebLookup(String decisionTarget, String executionTarget) {
        String normalizedDecisionTarget = normalize(decisionTarget);
        String normalizedExecutionTarget = normalize(executionTarget);
        return DecisionCapabilityCatalog.WEB_LOOKUP_DECISION_TARGET.equals(normalizedDecisionTarget)
                && DecisionCapabilityCatalog.isGenericWebSearchExecutionSkill(normalizedExecutionTarget);
    }

    private boolean matchesDocsLookup(String decisionTarget, String executionTarget) {
        String normalizedDecisionTarget = normalize(decisionTarget);
        String normalizedExecutionTarget = normalize(executionTarget);
        return "docs.lookup".equals(normalizedDecisionTarget)
                && "mcp.docs.searchdocs".equals(normalizedExecutionTarget);
    }

    private String resolveStepTarget(String target, String executionTarget) {
        if (EXECUTION_TARGET_TOKEN.equals(target)) {
            return executionTarget == null ? "" : executionTarget.trim();
        }
        return target == null ? "" : target.trim();
    }

    private SkillRecipe codeAssistRecipe() {
        return new SkillRecipe(
                "code.assist.direct",
                "code.assist",
                "development",
                DEVELOPMENT_WORKFLOW_REASON,
                List.of(new SkillRecipeStep(
                        "execute",
                        EXECUTION_TARGET_TOKEN,
                        SkillRecipeFailurePolicy.STOP,
                        Map.of()
                )),
                true,
                1
        );
    }

    private SkillRecipe webLookupDetailRecipe() {
        return searchDetailRecipe(
                "web.lookup.detail",
                DecisionCapabilityCatalog.WEB_LOOKUP_DECISION_TARGET,
                WEB_LOOKUP_DETAIL_WORKFLOW_REASON
        );
    }

    private SkillRecipe docsLookupDetailRecipe() {
        return searchDetailRecipe(
                "docs.lookup.detail",
                "docs.lookup",
                DOCS_LOOKUP_DETAIL_WORKFLOW_REASON
        );
    }

    private SkillRecipe searchDetailRecipe(String recipeId,
                                           String decisionTarget,
                                           String workflowReason) {
        return new SkillRecipe(
                recipeId,
                decisionTarget,
                "skill-graph",
                workflowReason,
                List.of(
                        new SkillRecipeStep(
                                "search",
                                EXECUTION_TARGET_TOKEN,
                                SkillRecipeFailurePolicy.STOP,
                                Map.of(
                                        "internalSkipDetailAugment",
                                        SkillRecipeValueRef.literal(Boolean.TRUE)
                                )
                        ),
                        new SkillRecipeStep(
                                "detail",
                                WebLookupDetailHelper.HELPER_TARGET,
                                SkillRecipeFailurePolicy.RETURN_PREVIOUS_SUCCESS,
                                Map.of(
                                        "query", SkillRecipeValueRef.requestParam("query"),
                                        "searchOutput", SkillRecipeValueRef.stepOutput("search")
                                )
                        )
                ),
                true,
                2
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (target == null || key == null || key.isBlank() || value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        target.put(key, value);
    }

    private enum SkillRecipeFailurePolicy {
        STOP,
        RETURN_PREVIOUS_SUCCESS
    }

    private enum SkillRecipeValueSource {
        LITERAL,
        REQUEST_PARAM,
        STEP_OUTPUT
    }

    private record SkillRecipe(String id,
                               String rootDecisionTarget,
                               String workflowName,
                               String workflowReason,
                               List<SkillRecipeStep> steps,
                               boolean safeAutoRun,
                               int maxDepth) {
    }

    private record SkillRecipeStep(String id,
                                   String target,
                                   SkillRecipeFailurePolicy failurePolicy,
                                   Map<String, SkillRecipeValueRef> bindings) {
    }

    private record SkillRecipeValueRef(SkillRecipeValueSource source,
                                       String key,
                                       Object literal) {

        static SkillRecipeValueRef literal(Object literal) {
            return new SkillRecipeValueRef(SkillRecipeValueSource.LITERAL, "", literal);
        }

        static SkillRecipeValueRef requestParam(String key) {
            return new SkillRecipeValueRef(SkillRecipeValueSource.REQUEST_PARAM, key == null ? "" : key.trim(), null);
        }

        static SkillRecipeValueRef stepOutput(String stepId) {
            return new SkillRecipeValueRef(SkillRecipeValueSource.STEP_OUTPUT, stepId == null ? "" : stepId.trim(), null);
        }
    }
}
