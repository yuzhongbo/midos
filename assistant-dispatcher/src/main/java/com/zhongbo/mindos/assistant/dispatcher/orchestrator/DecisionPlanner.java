package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.Candidate;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface DecisionPlanner {

    Decision plan(String userInput, String intent, Map<String, Object> params, SkillContext context);

    default Optional<Candidate> selectCandidate(String userInput, SkillContext context, List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(candidate -> candidate != null && candidate.target() != null && !candidate.target().isBlank())
                .max(Comparator.comparingDouble(Candidate::score)
                        .thenComparing(Candidate::source)
                        .thenComparing(Candidate::target));
    }
}
