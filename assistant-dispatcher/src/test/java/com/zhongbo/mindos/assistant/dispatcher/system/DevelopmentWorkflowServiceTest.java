package com.zhongbo.mindos.assistant.dispatcher.system;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.SkillExecutionGateway;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevelopmentWorkflowServiceTest {

    @Test
    void shouldDelegateCodeAssistToHiddenCodeGenerateExecutor() {
        CapturingGateway gateway = new CapturingGateway();
        DevelopmentWorkflowService service = new DevelopmentWorkflowService(gateway);

        SkillResult result = service.execute(
                "code.generate",
                Map.of("task", "修复登录接口空指针"),
                new SkillContext("u1", "帮我修复登录接口空指针", Map.of("language", "java"))
        );

        assertTrue(result.success());
        assertEquals("code.generate", gateway.lastDsl.skill());
        assertEquals("修复登录接口空指针", gateway.lastDsl.input().get("task"));
        assertEquals("development", gateway.lastContext.attributes().get("systemWorkflow"));
        assertEquals("code.generate", gateway.lastContext.attributes().get("systemWorkflowExecutionTarget"));
        assertEquals("code.assist", gateway.lastContext.attributes().get("systemWorkflowCapability"));
    }

    private static final class CapturingGateway implements SkillExecutionGateway {
        private SkillDsl lastDsl;
        private SkillContext lastContext;

        @Override
        public CompletableFuture<SkillResult> executeDslAsync(SkillDsl dsl, SkillContext context) {
            this.lastDsl = dsl;
            this.lastContext = new SkillContext(
                    context.userId(),
                    context.input(),
                    new LinkedHashMap<>(context.attributes())
            );
            return CompletableFuture.completedFuture(SkillResult.success("code.generate", "代码已处理"));
        }
    }
}
