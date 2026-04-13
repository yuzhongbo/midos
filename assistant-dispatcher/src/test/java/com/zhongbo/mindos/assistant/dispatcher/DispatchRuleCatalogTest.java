package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.skill.SkillCandidate;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchRuleCatalogTest {

    @Test
    void shouldOnlyAcceptPlannerRuleFallbackDecisions() {
        DispatchRuleCatalog catalog = new DispatchRuleCatalog(new StubSkillCatalogFacade());

        List<Candidate> selected = catalog.lowConfidenceFallbackCandidates(new Decision(
                "echo",
                "echo",
                Map.of("_plannerRouteSource", "rule-fallback", "text", "hello"),
                0.75,
                false
        ));

        assertEquals(1, selected.size());
        assertEquals("echo", selected.get(0).target());
        assertEquals("rule", selected.get(0).source());
    }

    @Test
    void shouldRejectNonFallbackPlannerDecision() {
        DispatchRuleCatalog catalog = new DispatchRuleCatalog(new StubSkillCatalogFacade());

        List<Candidate> selected = catalog.lowConfidenceFallbackCandidates(new Decision(
                "echo",
                "echo",
                Map.of("text", "hello"),
                1.0,
                false
        ));

        assertTrue(selected.isEmpty());
    }

    private static final class StubSkillCatalogFacade implements SkillCatalogFacade {
        @Override
        public Optional<String> detectSkillName(String input) {
            return Optional.empty();
        }

        @Override
        public List<SkillCandidate> detectSkillCandidates(String input, int limit) {
            return List.of();
        }

        @Override
        public Optional<SkillDescriptor> describeSkill(String skillName) {
            return Optional.empty();
        }

        @Override
        public List<SkillDescriptor> listSkillDescriptors() {
            return List.of();
        }

        @Override
        public String describeAvailableSkills() {
            return "";
        }

        @Override
        public List<String> listAvailableSkillSummaries() {
            return List.of();
        }
    }
}
