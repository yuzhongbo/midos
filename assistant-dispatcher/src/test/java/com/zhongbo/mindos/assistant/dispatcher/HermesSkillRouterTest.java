package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.InMemoryParamSchemaRegistry;
import com.zhongbo.mindos.assistant.skill.SkillCandidate;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillExecutionGateway;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HermesSkillRouterTest {

    @Test
    void shouldAttachCanonicalIdentityMetadataToRoutedSkillResult() {
        InMemoryParamSchemaRegistry schemaRegistry = new InMemoryParamSchemaRegistry();
        schemaRegistry.registerDefaults();
        HermesToolSchemaCatalog toolSchemaCatalog = new HermesToolSchemaCatalog(
                new TestSkillCatalog(List.of(new SkillDescriptor("todo.create", "Create todo item", List.of("待办")))),
                schemaRegistry
        );
        HermesSkillRouter router = new HermesSkillRouter(
                (dsl, context) -> CompletableFuture.completedFuture(SkillResult.success(dsl.skill(), "已创建")),
                toolSchemaCatalog
        );

        SkillResult result = router.execute(
                "task.manage",
                new Decision("task_plan", "task.manage", Map.of("task", "提交周报"), 0.92d, false),
                new SkillContext("u1", "帮我创建一个周报待办", new LinkedHashMap<>())
        );

        assertEquals("todo.create", result.skillName());
        assertEquals("task.manage", result.metadataText(HermesSkillIdentity.DECISION_TARGET_METADATA_KEY));
        assertEquals("todo.create", result.metadataText(HermesSkillIdentity.EXECUTION_TARGET_METADATA_KEY));
        assertEquals("todo.create", result.metadataText(HermesSkillIdentity.CANONICAL_SKILL_METADATA_KEY));
    }

    private static final class TestSkillCatalog implements SkillCatalogFacade {
        private final List<SkillDescriptor> descriptors;

        private TestSkillCatalog(List<SkillDescriptor> descriptors) {
            this.descriptors = descriptors == null ? List.of() : List.copyOf(descriptors);
        }

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
            return descriptors.stream().filter(descriptor -> descriptor.name().equals(skillName)).findFirst();
        }

        @Override
        public List<SkillDescriptor> listSkillDescriptors() {
            return descriptors;
        }

        @Override
        public String describeAvailableSkills() {
            return descriptors.stream().map(SkillDescriptor::name).reduce((left, right) -> left + ", " + right).orElse("");
        }

        @Override
        public List<String> listAvailableSkillSummaries() {
            return descriptors.stream()
                    .map(descriptor -> descriptor.name() + " - " + descriptor.description())
                    .toList();
        }
    }
}
