package com.zhongbo.mindos.assistant.skill;

import java.util.List;
import java.util.Optional;

public interface SkillEngineFacade {

    Optional<String> detectSkillName(String input);

    List<SkillEngine.SkillCandidate> detectSkillCandidates(String input, int limit);

    String describeAvailableSkills();

    List<String> listAvailableSkillSummaries();
}
