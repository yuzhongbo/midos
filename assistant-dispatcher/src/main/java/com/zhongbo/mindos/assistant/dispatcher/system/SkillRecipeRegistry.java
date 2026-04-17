package com.zhongbo.mindos.assistant.dispatcher.system;

import com.zhongbo.mindos.assistant.skill.DecisionCapabilityCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class SkillRecipeRegistry {

    private final AtomicReference<List<SkillRecipe>> defaultRecipes;
    private final ConcurrentHashMap<String, List<SkillRecipe>> recipesByUser = new ConcurrentHashMap<>();

    public SkillRecipeRegistry() {
        this(defaultRecipes());
    }

    SkillRecipeRegistry(List<SkillRecipe> initialRecipes) {
        this.defaultRecipes = new AtomicReference<>(sanitize(initialRecipes));
    }

    public Optional<SkillRecipe> resolve(String decisionTarget, String executionTarget) {
        return resolve("", decisionTarget, executionTarget);
    }

    public Optional<SkillRecipe> resolve(String userId, String decisionTarget, String executionTarget) {
        String normalizedDecisionTarget = normalize(decisionTarget);
        String normalizedExecutionTarget = normalize(executionTarget);
        return recipesFor(userId).stream()
                .filter(recipe -> recipe.selector().matches(normalizedDecisionTarget, normalizedExecutionTarget))
                .findFirst();
    }

    public void registerOrReplace(SkillRecipe recipe) {
        registerOrReplace("", recipe);
    }

    public void registerOrReplace(String userId, SkillRecipe recipe) {
        if (recipe == null) {
            return;
        }
        validate(recipe);
        String normalizedUserId = normalize(userId);
        List<SkillRecipe> current = recipesFor(normalizedUserId);
        LinkedHashMap<String, SkillRecipe> byId = new LinkedHashMap<>();
        for (SkillRecipe existing : current) {
            byId.put(existing.id(), existing);
        }
        byId.put(recipe.id(), recipe);
        applyRecipes(normalizedUserId, List.copyOf(byId.values()));
    }

    public void deploy(List<SkillRecipe> recipes) {
        deploy("", recipes);
    }

    public void deploy(String userId, List<SkillRecipe> recipes) {
        applyRecipes(normalize(userId), sanitize(recipes));
    }

    public void clear(String userId) {
        String normalizedUserId = normalize(userId);
        if (normalizedUserId.isBlank()) {
            defaultRecipes.set(sanitize(defaultRecipes()));
            return;
        }
        recipesByUser.remove(normalizedUserId);
    }

    public List<SkillRecipe> activeRecipes() {
        return defaultRecipes.get();
    }

    public List<SkillRecipe> activeRecipes(String userId) {
        return recipesFor(userId);
    }

    private void applyRecipes(String userId, List<SkillRecipe> recipes) {
        if (userId == null || userId.isBlank()) {
            defaultRecipes.set(recipes);
            return;
        }
        recipesByUser.put(userId, recipes);
    }

    private List<SkillRecipe> recipesFor(String userId) {
        String normalizedUserId = normalize(userId);
        List<SkillRecipe> userRecipes = normalizedUserId.isBlank() ? null : recipesByUser.get(normalizedUserId);
        return userRecipes == null ? defaultRecipes.get() : userRecipes;
    }

    private List<SkillRecipe> sanitize(List<SkillRecipe> recipes) {
        if (recipes == null || recipes.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, SkillRecipe> unique = new LinkedHashMap<>();
        for (SkillRecipe recipe : recipes) {
            validate(recipe);
            unique.put(recipe.id(), recipe);
        }
        return unique.isEmpty() ? List.of() : List.copyOf(unique.values());
    }

    private void validate(SkillRecipe recipe) {
        if (recipe == null) {
            throw new IllegalArgumentException("skill recipe is required");
        }
        if (normalize(recipe.id()).isBlank()) {
            throw new IllegalArgumentException("skill recipe id is required");
        }
        if (recipe.selector() == null || normalize(recipe.selector().decisionTarget()).isBlank()) {
            throw new IllegalArgumentException("skill recipe selector decision target is required");
        }
        if (recipe.steps().isEmpty()) {
            throw new IllegalArgumentException("skill recipe must contain at least one step");
        }
        if (recipe.maxDepth() < recipe.steps().size()) {
            throw new IllegalArgumentException("skill recipe maxDepth must cover all steps");
        }
    }

    private static List<SkillRecipe> defaultRecipes() {
        List<SkillRecipe> defaults = new ArrayList<>();
        defaults.add(new SkillRecipe(
                "code.assist.direct",
                new SkillRecipeSelector("code.assist", "code.generate"),
                "development",
                SkillCompositionService.DEVELOPMENT_WORKFLOW_REASON,
                List.of(new SkillRecipeStep(
                        "execute",
                        SkillRecipeSelector.EXECUTION_TARGET_TOKEN,
                        SkillRecipeFailurePolicy.STOP,
                        Map.of()
                )),
                true,
                1
        ));
        defaults.add(searchDetailRecipe(
                "web.lookup.detail",
                DecisionCapabilityCatalog.WEB_LOOKUP_DECISION_TARGET,
                SkillRecipeSelector.GENERIC_WEB_SEARCH_PATTERN,
                SkillCompositionService.WEB_LOOKUP_DETAIL_WORKFLOW_REASON
        ));
        defaults.add(searchDetailRecipe(
                "docs.lookup.detail",
                "docs.lookup",
                "mcp.docs.searchdocs",
                SkillCompositionService.DOCS_LOOKUP_DETAIL_WORKFLOW_REASON
        ));
        return List.copyOf(defaults);
    }

    private static SkillRecipe searchDetailRecipe(String recipeId,
                                                  String decisionTarget,
                                                  String executionTargetPattern,
                                                  String workflowReason) {
        return new SkillRecipe(
                recipeId,
                new SkillRecipeSelector(decisionTarget, executionTargetPattern),
                "skill-graph",
                workflowReason,
                List.of(
                        new SkillRecipeStep(
                                "search",
                                SkillRecipeSelector.EXECUTION_TARGET_TOKEN,
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

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
