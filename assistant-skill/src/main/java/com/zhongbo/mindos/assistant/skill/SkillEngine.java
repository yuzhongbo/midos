package com.zhongbo.mindos.assistant.skill;

import com.zhongbo.mindos.assistant.skill.mcp.DefaultMcpToolCatalog;
import com.zhongbo.mindos.assistant.skill.mcp.McpToolCatalog;
import com.zhongbo.mindos.assistant.skill.mcp.McpToolExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class SkillEngine implements SkillEngineFacade {

    public record SkillCandidate(String skillName, int score) {
    }

    private final SkillRegistry skillRegistry;
    private final McpToolCatalog mcpToolCatalog;

    public SkillEngine(SkillRegistry skillRegistry,
                       SkillDslExecutor dslExecutor) {
        this(skillRegistry, dslExecutor, new DefaultMcpToolCatalog(new McpToolExecutor()));
    }

    @Autowired
    public SkillEngine(SkillRegistry skillRegistry,
                       SkillDslExecutor dslExecutor,
                       McpToolCatalog mcpToolCatalog) {
        this.skillRegistry = skillRegistry;
        this.mcpToolCatalog = mcpToolCatalog;
    }

    public Optional<String> detectSkillName(String input) {
        return detectSkillCandidates(input, 1).stream().findFirst().map(SkillCandidate::skillName);
    }

    public List<SkillCandidate> detectSkillCandidates(String input, int limit) {
        if (input == null || input.isBlank() || limit <= 0) {
            return List.of();
        }
        List<SkillCandidate> candidates = new ArrayList<>();
        skillRegistry.detectCandidates(input, limit).forEach(candidate ->
                candidates.add(new SkillCandidate(candidate.skill().name(), candidate.score()))
        );
        mcpToolCatalog.detectCandidates(input, limit).forEach(candidate ->
                candidates.add(new SkillCandidate(candidate.skillName(), candidate.score()))
        );
        candidates.sort(Comparator
                .comparingInt(SkillCandidate::score).reversed()
                .thenComparing(SkillCandidate::skillName));
        int safeLimit = Math.min(limit, candidates.size());
        return safeLimit <= 0 ? List.of() : List.copyOf(candidates.subList(0, safeLimit));
    }

    public String describeAvailableSkills() {
        return listAvailableSkillSummaries().stream().collect(Collectors.joining(", "));
    }

    public List<String> listAvailableSkillSummaries() {
        List<String> summaries = new ArrayList<>(skillRegistry.getAllSkills().stream()
                .sorted(Comparator.comparing(Skill::name))
                .map(skill -> skill.name() + " - " + (skill.description() == null ? "" : skill.description()))
                .toList());
        summaries.addAll(mcpToolCatalog.listToolSummaries());
        summaries.sort(String::compareTo);
        return List.copyOf(summaries);
    }
}
