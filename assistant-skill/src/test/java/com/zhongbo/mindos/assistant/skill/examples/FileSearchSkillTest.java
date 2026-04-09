package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSearchSkillTest {

    private final FileSearchSkill skill = new FileSearchSkill();

    @Test
    void shouldInferPathKeywordAndFileTypeFromNaturalLanguage() {
        SkillResult result = skill.run(new SkillContext(
                "u1",
                "帮我在 assistant-skill/src/main/java 目录找 dispatcher 相关的 java 文件，前4个",
                Map.of()
        ));

        assertTrue(result.success());
        assertTrue(result.output().contains("路径：assistant-skill/src/main/java"));
        assertTrue(result.output().contains("关键词：dispatcher"));
        assertTrue(result.output().contains("文件类型：.java"));
        assertTrue(result.output().contains("建议候选数：4"));
    }

    @Test
    void shouldRespectStructuredAttributesWhenProvided() {
        SkillResult result = skill.run(new SkillContext(
                "u1",
                "",
                Map.of("path", "./docs", "keyword", "memory sync", "fileType", ".md", "limit", "2")
        ));

        assertTrue(result.success());
        assertTrue(result.output().contains("路径：./docs"));
        assertTrue(result.output().contains("关键词：memory sync"));
        assertTrue(result.output().contains("文件类型：.md"));
        assertTrue(result.output().contains("建议候选数：2"));
    }
}
