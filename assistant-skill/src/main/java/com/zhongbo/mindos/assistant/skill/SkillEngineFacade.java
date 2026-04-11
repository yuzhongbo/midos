package com.zhongbo.mindos.assistant.skill;

import java.util.List;
import java.util.Optional;

public interface SkillEngineFacade {

    Optional<String> detectSkillName(String input);

    List<SkillCandidate> detectSkillCandidates(String input, int limit);

    Optional<SkillDescriptor> describeSkill(String skillName);

    List<SkillDescriptor> listSkillDescriptors();

    String describeAvailableSkills();

    List<String> listAvailableSkillSummaries();
}
