package com.zhongbo.mindos.assistant.skill;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SkillRegistry {

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    public SkillRegistry(List<Skill> discoveredSkills) {
        discoveredSkills.forEach(this::register);
    }

    public void register(Skill skill) {
        skills.put(skill.name(), skill);
    }

    public int unregisterByPrefix(String prefix) {
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

    public Optional<Skill> getSkill(String skillName) {
        return Optional.ofNullable(skills.get(skillName));
    }

    public Collection<Skill> getAllSkills() {
        return List.copyOf(skills.values());
    }

    public Optional<Skill> detect(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String normalized = input.trim();
        String firstToken = normalized.split("\\s+", 2)[0].toLowerCase();

        Skill directMatch = skills.get(firstToken);
        if (directMatch != null) {
            return Optional.of(directMatch);
        }

        return skills.values().stream().filter(skill -> skill.supports(normalized)).findFirst();
    }
}
