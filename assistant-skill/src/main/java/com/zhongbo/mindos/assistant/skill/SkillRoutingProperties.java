package com.zhongbo.mindos.assistant.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "mindos.skills.routing")
public class SkillRoutingProperties {

    private Map<String, String> keywords = new LinkedHashMap<>();

    public Map<String, String> getKeywords() {
        return keywords;
    }

    public void setKeywords(Map<String, String> keywords) {
        this.keywords = keywords == null ? new LinkedHashMap<>() : new LinkedHashMap<>(keywords);
    }
}

