package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import org.springframework.stereotype.Component;

@Component
public class FileSearchSkill implements Skill {

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

        String output = "[file.search] Placeholder match list for path='"
                + filePath + "', keyword='" + keyword + "': [example.txt, notes.md]";
        return SkillResult.success(name(), output);
    }

    private String asString(SkillContext context, String key, String defaultValue) {
        Object value = context.attributes().get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }
}

