package com.zhongbo.mindos.assistant.dispatcher.system;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zhongbo.mindos.assistant.memory.MemoryStateStore;
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

    private static final String STATE_FILE = "hermes-skill-recipe-registry.json";

    private final AtomicReference<List<SkillRecipe>> defaultRecipes;
    private final ConcurrentHashMap<String, List<SkillRecipe>> recipesByUser = new ConcurrentHashMap<>();
    private volatile MemoryStateStore memoryStateStore = MemoryStateStore.noOp();

    public SkillRecipeRegistry() {
        this(defaultRecipes(), MemoryStateStore.noOp());
    }

    public SkillRecipeRegistry(MemoryStateStore memoryStateStore) {
        this(defaultRecipes(), memoryStateStore);
    }

    SkillRecipeRegistry(List<SkillRecipe> initialRecipes) {
        this(initialRecipes, MemoryStateStore.noOp());
    }

    SkillRecipeRegistry(List<SkillRecipe> initialRecipes, MemoryStateStore memoryStateStore) {
        this.defaultRecipes = new AtomicReference<>(sanitize(initialRecipes));
        configurePersistence(memoryStateStore);
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
        persistState();
    }

    public void deploy(List<SkillRecipe> recipes) {
        deploy("", recipes);
    }

    public void deploy(String userId, List<SkillRecipe> recipes) {
        applyRecipes(normalize(userId), sanitize(recipes));
        persistState();
    }

    public void clear(String userId) {
        String normalizedUserId = normalize(userId);
        if (normalizedUserId.isBlank()) {
            defaultRecipes.set(sanitize(defaultRecipes()));
            persistState();
            return;
        }
        recipesByUser.remove(normalizedUserId);
        persistState();
    }

    public List<SkillRecipe> activeRecipes() {
        return defaultRecipes.get();
    }

    public List<SkillRecipe> activeRecipes(String userId) {
        return recipesFor(userId);
    }

    public void configurePersistence(MemoryStateStore memoryStateStore) {
        this.memoryStateStore = memoryStateStore == null ? MemoryStateStore.noOp() : memoryStateStore;
        restoreState();
    }

    private void applyRecipes(String userId, List<SkillRecipe> recipes) {
        if (userId == null || userId.isBlank()) {
            defaultRecipes.set(recipes);
            return;
        }
        recipesByUser.put(userId, recipes);
    }

    private synchronized void restoreState() {
        PersistedRecipeState persisted = memoryStateStore.readState(
                STATE_FILE,
                new TypeReference<>() {
                },
                this::snapshotState
        );
        applyState(persisted);
    }

    private synchronized void persistState() {
        memoryStateStore.writeState(STATE_FILE, snapshotState());
    }

    private PersistedRecipeState snapshotState() {
        LinkedHashMap<String, List<SkillRecipe>> normalized = new LinkedHashMap<>();
        recipesByUser.forEach((userId, recipes) -> {
            String normalizedUserId = normalize(userId);
            if (!normalizedUserId.isBlank()) {
                normalized.put(normalizedUserId, sanitize(recipes));
            }
        });
        return new PersistedRecipeState(defaultRecipes.get(), normalized);
    }

    private void applyState(PersistedRecipeState persisted) {
        PersistedRecipeState safeState = persisted == null ? snapshotState() : persisted;
        defaultRecipes.set(sanitize(safeState.defaultRecipes()));
        recipesByUser.clear();
        if (safeState.recipesByUser() != null) {
            safeState.recipesByUser().forEach((userId, recipes) -> {
                String normalizedUserId = normalize(userId);
                if (!normalizedUserId.isBlank()) {
                    recipesByUser.put(normalizedUserId, sanitize(recipes));
                }
            });
        }
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

    private record PersistedRecipeState(List<SkillRecipe> defaultRecipes,
                                        Map<String, List<SkillRecipe>> recipesByUser) {
    }
}
