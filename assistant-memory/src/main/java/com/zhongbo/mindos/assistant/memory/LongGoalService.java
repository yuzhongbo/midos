package com.zhongbo.mindos.assistant.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zhongbo.mindos.assistant.memory.model.LongGoal;
import com.zhongbo.mindos.assistant.memory.model.LongGoalStatus;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LongGoalService {

    private static final String STATE_FILE = "long-goals.json";
    private static final int MAX_NOTES = 20;

    private final Map<String, Map<String, LongGoal>> goalsByUser = new ConcurrentHashMap<>();
    private final MemoryStateStore memoryStateStore;

    public LongGoalService() {
        this(MemoryStateStore.noOp());
    }

    @Autowired
    public LongGoalService(MemoryStateStore memoryStateStore) {
        this.memoryStateStore = memoryStateStore == null ? MemoryStateStore.noOp() : memoryStateStore;
        loadState();
    }

    public LongGoal createGoal(String userId,
                               String title,
                               String objective,
                               String successCriteria,
                               Instant dueAt,
                               Instant nextReviewAt) {
        String normalizedUserId = normalizeText(userId, "local-user");
        String normalizedTitle = normalizeText(title, "");
        String normalizedObjective = normalizeText(objective, "");
        if (normalizedTitle.isBlank() && normalizedObjective.isBlank()) {
            throw new IllegalArgumentException("goal title or objective is required");
        }
        Instant now = Instant.now();
        LongGoal goal = new LongGoal(
                UUID.randomUUID().toString(),
                normalizedUserId,
                normalizedTitle.isBlank() ? normalizedObjective : normalizedTitle,
                normalizedObjective,
                normalizeText(successCriteria, ""),
                LongGoalStatus.ACTIVE,
                0,
                List.of(),
                List.of(),
                now,
                now,
                dueAt,
                nextReviewAt == null ? now : nextReviewAt
        );
        Map<String, LongGoal> userGoals = goalsByUser.computeIfAbsent(normalizedUserId, ignored -> new ConcurrentHashMap<>());
        synchronized (userGoals) {
            userGoals.put(goal.goalId(), goal);
            persistState();
        }
        return goal;
    }

    public List<LongGoal> listGoals(String userId, String statusFilter) {
        String normalizedUserId = normalizeText(userId, "local-user");
        LongGoalStatus status = parseStatus(statusFilter);
        return goalsByUser.getOrDefault(normalizedUserId, Map.of()).values().stream()
                .filter(goal -> status == null || goal.status() == status)
                .sorted(Comparator.comparing(LongGoal::updatedAt).reversed())
                .toList();
    }

    public LongGoal getGoal(String userId, String goalId) {
        String normalizedUserId = normalizeText(userId, "local-user");
        return goalsByUser.getOrDefault(normalizedUserId, Map.of()).get(goalId);
    }

    public LongGoal updateStatus(String userId,
                                 String goalId,
                                 LongGoalStatus status,
                                 String note,
                                 Instant nextReviewAt) {
        String normalizedUserId = normalizeText(userId, "local-user");
        Map<String, LongGoal> userGoals = goalsByUser.computeIfAbsent(normalizedUserId, ignored -> new ConcurrentHashMap<>());
        synchronized (userGoals) {
            LongGoal current = userGoals.get(goalId);
            if (current == null) {
                return null;
            }
            LongGoalStatus nextStatus = status == null ? current.status() : status;
            LongGoal updated = new LongGoal(
                    current.goalId(),
                    current.userId(),
                    current.title(),
                    current.objective(),
                    current.successCriteria(),
                    nextStatus,
                    current.progressPercent(),
                    current.linkedTaskIds(),
                    appendNote(current.recentNotes(), note, "system"),
                    current.createdAt(),
                    Instant.now(),
                    current.dueAt(),
                    nextReviewAt == null ? current.nextReviewAt() : nextReviewAt
            );
            userGoals.put(goalId, updated);
            persistState();
            return updated;
        }
    }

    public LongGoal linkTask(String userId, String goalId, String taskId) {
        String normalizedUserId = normalizeText(userId, "local-user");
        String normalizedTaskId = normalizeText(taskId, "");
        if (normalizedTaskId.isBlank()) {
            return null;
        }
        Map<String, LongGoal> userGoals = goalsByUser.computeIfAbsent(normalizedUserId, ignored -> new ConcurrentHashMap<>());
        synchronized (userGoals) {
            LongGoal current = userGoals.get(goalId);
            if (current == null) {
                throw new IllegalArgumentException("goal not found: " + goalId);
            }
            if (current.status() == LongGoalStatus.CANCELLED) {
                throw new IllegalArgumentException("goal is cancelled: " + goalId);
            }
            LinkedHashSet<String> linkedTaskIds = new LinkedHashSet<>(current.linkedTaskIds());
            linkedTaskIds.add(normalizedTaskId);
            LongGoal updated = new LongGoal(
                    current.goalId(),
                    current.userId(),
                    current.title(),
                    current.objective(),
                    current.successCriteria(),
                    LongGoalStatus.ACTIVE,
                    current.progressPercent(),
                    List.copyOf(linkedTaskIds),
                    current.recentNotes(),
                    current.createdAt(),
                    Instant.now(),
                    current.dueAt(),
                    current.nextReviewAt()
            );
            userGoals.put(goalId, updated);
            persistState();
            return updated;
        }
    }

    public LongGoal syncWithTasks(String userId, String goalId, List<LongTask> tasks) {
        String normalizedUserId = normalizeText(userId, "local-user");
        Map<String, LongGoal> userGoals = goalsByUser.computeIfAbsent(normalizedUserId, ignored -> new ConcurrentHashMap<>());
        synchronized (userGoals) {
            LongGoal current = userGoals.get(goalId);
            if (current == null) {
                return null;
            }
            List<LongTask> linkedTasks = tasks == null ? List.of() : tasks.stream()
                    .filter(task -> task != null && goalId.equals(task.goalId()))
                    .toList();
            List<LongTask> effectiveTasks = linkedTasks.stream()
                    .filter(task -> task.childTaskIds() == null || task.childTaskIds().isEmpty())
                    .toList();
            if (effectiveTasks.isEmpty()) {
                effectiveTasks = linkedTasks;
            }
            LinkedHashSet<String> linkedTaskIds = new LinkedHashSet<>();
            int totalProgress = 0;
            int completedCount = 0;
            for (LongTask task : linkedTasks) {
                linkedTaskIds.add(task.taskId());
            }
            for (LongTask task : effectiveTasks) {
                totalProgress += Math.max(0, Math.min(100, task.progressPercent()));
                if (task.status() == LongTaskStatus.COMPLETED) {
                    completedCount++;
                }
            }
            int progressPercent = effectiveTasks.isEmpty()
                    ? current.progressPercent()
                    : (int) Math.round(totalProgress / (double) effectiveTasks.size());
            LongGoalStatus nextStatus = current.status();
            if (nextStatus != LongGoalStatus.CANCELLED && nextStatus != LongGoalStatus.ON_HOLD) {
                nextStatus = !effectiveTasks.isEmpty() && completedCount == effectiveTasks.size()
                        ? LongGoalStatus.ACHIEVED
                        : LongGoalStatus.ACTIVE;
            }
            LongGoal updated = new LongGoal(
                    current.goalId(),
                    current.userId(),
                    current.title(),
                    current.objective(),
                    current.successCriteria(),
                    nextStatus,
                    progressPercent,
                    List.copyOf(linkedTaskIds),
                    current.recentNotes(),
                    current.createdAt(),
                    Instant.now(),
                    current.dueAt(),
                    current.nextReviewAt()
            );
            userGoals.put(goalId, updated);
            persistState();
            return updated;
        }
    }

    public LongGoal activeGoal(String userId) {
        return listGoals(userId, LongGoalStatus.ACTIVE.name()).stream()
                .sorted(Comparator
                        .comparing((LongGoal goal) -> goal.dueAt() == null ? Instant.MAX : goal.dueAt())
                        .thenComparing(LongGoal::updatedAt, Comparator.reverseOrder()))
                .findFirst()
                .orElse(null);
    }

    private void loadState() {
        Map<String, Map<String, LongGoal>> persisted = memoryStateStore.readState(
                STATE_FILE,
                new TypeReference<>() {
                },
                Map::of
        );
        persisted.forEach((userId, goals) -> {
            if (userId == null || goals == null || goals.isEmpty()) {
                return;
            }
            goalsByUser.put(userId, new ConcurrentHashMap<>(goals));
        });
    }

    private void persistState() {
        Map<String, Map<String, LongGoal>> snapshot = new ConcurrentHashMap<>();
        goalsByUser.forEach((userId, goals) -> snapshot.put(userId, new ConcurrentHashMap<>(goals)));
        memoryStateStore.writeState(STATE_FILE, snapshot);
    }

    private List<String> appendNote(List<String> existing, String note, String author) {
        String normalized = normalizeText(note, "");
        if (normalized.isBlank()) {
            return existing == null ? List.of() : existing;
        }
        List<String> updated = new ArrayList<>(existing == null ? List.of() : existing);
        updated.add(normalizeText(author, "system") + ": " + normalized);
        int fromIndex = Math.max(0, updated.size() - MAX_NOTES);
        return List.copyOf(updated.subList(fromIndex, updated.size()));
    }

    private LongGoalStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LongGoalStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid goal status: " + value, ex);
        }
    }

    private String normalizeText(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? fallback : normalized;
    }
}
