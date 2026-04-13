package com.zhongbo.mindos.assistant.skill;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class SkillRegistry {

    private final Map<String, Skill> skills = new LinkedHashMap<>();

    public SkillRegistry(List<Skill> discoveredSkills) {
        this(discoveredSkills, new SkillRoutingProperties());
    }

    @Autowired
    public SkillRegistry(List<Skill> discoveredSkills, SkillRoutingProperties routingProperties) {
        discoveredSkills.forEach(this::register);
    }

    public synchronized void register(Skill skill) {
        skills.put(skill.name(), skill);
    }

    public synchronized boolean unregister(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        return skills.remove(skillName) != null;
    }

    public synchronized boolean containsSkill(String skillName) {
        return skillName != null && skills.containsKey(skillName);
    }

    public synchronized int unregisterByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return 0;
        }
        int removed = 0;
        for (String key : List.copyOf(skills.keySet())) {
            if (key.startsWith(prefix)) {
                if (skills.remove(key) != null) {
                    removed++;
                }
            }
        }
        return removed;
    }

    public synchronized Optional<Skill> get(String skillName) {
        return Optional.ofNullable(skills.get(skillName));
    }

    public synchronized Optional<Skill> getSkill(String skillName) {
        return get(skillName);
    }

    public synchronized Collection<Skill> getAllSkills() {
        return List.copyOf(skills.values());
    }

    public synchronized int size() {
        return skills.size();
    }
}
