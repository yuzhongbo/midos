package com.zhongbo.mindos.assistant.skill;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;

import java.util.Optional;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

public interface SkillEngineFacade {

    Optional<SkillResult> executeDetectedSkill(SkillContext context);

    Optional<String> detectSkillName(String input);

    List<SkillEngine.SkillCandidate> detectSkillCandidates(String input, int limit);

    CompletableFuture<Optional<SkillResult>> executeDetectedSkillAsync(SkillContext context);

    CompletableFuture<Optional<SkillResult>> executeSkillByNameAsync(String skillName, SkillContext context);

    SkillResult executeDsl(SkillDsl dsl, SkillContext context);

    CompletableFuture<SkillResult> executeDslAsync(SkillDsl dsl, SkillContext context);

    Future<SkillResult> executeSkillAsync(SkillDsl dsl, SkillContext context);

    String describeAvailableSkills();

    List<String> listAvailableSkillSummaries();
}
