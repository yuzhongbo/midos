package com.zhongbo.mindos.assistant.dispatcher.system;

import com.zhongbo.mindos.assistant.common.SkillContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillStudioWorkflowServiceTest {

    @Test
    void shouldDraftWebScraperStyleSkillFromNaturalLanguageRequest() {
        SkillStudioWorkflowService service = new SkillStudioWorkflowService();

        String output = service.execute(
                "skill.factory",
                new SkillContext("u1", "帮我开发一个用于抓取 GitHub Releases 的技能", Map.of())
        ).output();

        assertTrue(output.contains("[skill.studio]"), output);
        assertTrue(output.contains("generated-web-scraper"), output);
        assertTrue(output.contains("draft"), output);
        assertTrue(output.contains("GitHub Releases"), output);
    }

    @Test
    void shouldKeepExternalRepoLearningInDraftReviewTrack() {
        SkillStudioWorkflowService service = new SkillStudioWorkflowService();

        String output = service.execute(
                "skill.factory",
                new SkillContext(
                        "u1",
                        "帮我学习这个 GitHub 开源仓库技能并适配成我的助手能力：https://github.com/example/weather-skill",
                        Map.of()
                )
        ).output();

        assertTrue(output.contains("repo-review"), output);
        assertTrue(output.contains("不会直接执行外部仓库 / JAR 代码"), output);
        assertTrue(output.contains("https://github.com/example/weather-skill"), output);
    }
}
