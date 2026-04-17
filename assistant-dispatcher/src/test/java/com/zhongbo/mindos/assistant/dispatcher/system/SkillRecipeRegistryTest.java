package com.zhongbo.mindos.assistant.dispatcher.system;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRecipeRegistryTest {

    @Test
    void shouldResolveGenericWebLookupRecipeFromRegistry() {
        SkillRecipeRegistry registry = new SkillRecipeRegistry();

        SkillRecipe recipe = registry.resolve("web.lookup", "mcp.bravesearch.webSearch").orElseThrow();

        assertEquals("web.lookup.detail", recipe.id());
        assertEquals("skill-graph", recipe.workflowName());
        assertEquals(2, recipe.steps().size());
    }

    @Test
    void shouldReplaceExistingRecipeById() {
        SkillRecipeRegistry registry = new SkillRecipeRegistry();
        SkillRecipe replacement = new SkillRecipe(
                "docs.lookup.detail",
                new SkillRecipeSelector("docs.lookup", "mcp.docs.searchdocs"),
                "skill-graph",
                "systemWorkflow=skill-graph:docs.lookup.detail:v2",
                List.of(new SkillRecipeStep(
                        "execute",
                        SkillRecipeSelector.EXECUTION_TARGET_TOKEN,
                        SkillRecipeFailurePolicy.STOP,
                        Map.of()
                )),
                true,
                1
        );

        registry.registerOrReplace(replacement);
        SkillRecipe resolved = registry.resolve("docs.lookup", "mcp.docs.searchDocs").orElseThrow();

        assertEquals("systemWorkflow=skill-graph:docs.lookup.detail:v2", resolved.workflowReason());
        assertEquals(1, resolved.steps().size());
        assertTrue(registry.activeRecipes().stream().anyMatch(recipe -> "docs.lookup.detail".equals(recipe.id())));
    }

    @Test
    void shouldKeepUserScopedRecipeOverrideIsolated() {
        SkillRecipeRegistry registry = new SkillRecipeRegistry();
        SkillRecipe replacement = new SkillRecipe(
                "docs.lookup.detail",
                new SkillRecipeSelector("docs.lookup", "mcp.docs.searchdocs"),
                "skill-graph",
                "systemWorkflow=skill-graph:docs.lookup.detail:user-specific",
                List.of(new SkillRecipeStep(
                        "execute",
                        SkillRecipeSelector.EXECUTION_TARGET_TOKEN,
                        SkillRecipeFailurePolicy.STOP,
                        Map.of()
                )),
                true,
                1
        );

        registry.registerOrReplace("u1", replacement);

        assertEquals("systemWorkflow=skill-graph:docs.lookup.detail:user-specific",
                registry.resolve("u1", "docs.lookup", "mcp.docs.searchDocs").orElseThrow().workflowReason());
        assertEquals("systemWorkflow=skill-graph:docs.lookup.detail",
                registry.resolve("u2", "docs.lookup", "mcp.docs.searchDocs").orElseThrow().workflowReason());
    }
}
