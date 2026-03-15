package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ProceduralMemoryService {

    private final Map<String, List<ProceduralMemoryEntry>> historyByUser = new ConcurrentHashMap<>();

    public void log(String userId, String skillName, String input, boolean success) {
        historyByUser.computeIfAbsent(userId, key -> new ArrayList<>())
                .add(ProceduralMemoryEntry.of(skillName, input, success));
    }

    public void addEntry(String userId, ProceduralMemoryEntry entry) {
        historyByUser.computeIfAbsent(userId, key -> new ArrayList<>()).add(entry);
    }

    public List<ProceduralMemoryEntry> getHistory(String userId) {
        return List.copyOf(historyByUser.getOrDefault(userId, List.of()));
    }

    public List<SkillUsageStats> getSkillUsageStats(String userId) {
        return historyByUser.getOrDefault(userId, List.of()).stream()
                .collect(Collectors.groupingBy(
                        ProceduralMemoryEntry::skillName,
                        Collectors.collectingAndThen(Collectors.toList(), this::toStats)
                ))
                .values()
                .stream()
                .sorted(Comparator.comparing(SkillUsageStats::skillName))
                .toList();
    }

    private SkillUsageStats toStats(List<ProceduralMemoryEntry> entries) {
        String skillName = entries.get(0).skillName();
        long total = entries.size();
        long success = entries.stream().filter(ProceduralMemoryEntry::success).count();
        return new SkillUsageStats(skillName, total, success, total - success);
    }
}

