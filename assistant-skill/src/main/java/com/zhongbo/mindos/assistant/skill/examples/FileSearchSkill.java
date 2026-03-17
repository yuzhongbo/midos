package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class FileSearchSkill implements Skill {
    private static final Logger LOGGER = Logger.getLogger(FileSearchSkill.class.getName());
    private final LlmClient llmClient;

    public FileSearchSkill(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public FileSearchSkill() {
        this(null);
    }

    @Override
    public String name() {
        return "file.search";
    }

    @Override
    public String description() {
        return "Searches files by path and keyword (skeleton placeholder).";
    }

    @Override
    public SkillResult run(SkillContext context) {
        String filePath = asString(context, "path", "./");
        String keyword = asString(context, "keyword", "");
        if (llmClient != null) {
            try {
                String prompt = "你是一个文件搜索助手，请根据如下路径和关键词智能返回匹配文件名列表，仅输出文件名列表。路径：" + filePath + ", 关键词：" + keyword;
                String llmReply = llmClient.generateResponse(prompt, buildLlmContext(context));
                if (llmReply != null && !llmReply.isBlank()) {
                    return SkillResult.success(name(), llmReply.trim());
                }
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "LLM call failed for file.search skill, fallback to local output", ex);
            }
        }
        String output = "[file.search] Placeholder match list for path='"
                + filePath + "', keyword='" + keyword + "': [example.txt, notes.md]";
        return SkillResult.success(name(), output);
    }

    private Map<String, Object> buildLlmContext(SkillContext context) {
        Map<String, Object> llmContext = new LinkedHashMap<>();
        llmContext.put("userId", context.userId() == null ? "" : context.userId());
        llmContext.put("channel", name());
        return llmContext;
    }

    private String asString(SkillContext context, String key, String defaultValue) {
        Object value = context.attributes().get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }
}
