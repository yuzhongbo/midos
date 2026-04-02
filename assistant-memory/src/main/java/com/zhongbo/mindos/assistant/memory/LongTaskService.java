package com.zhongbo.mindos.assistant.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LongTaskService {

    private static final String STATE_FILE = "long-tasks.json";
    private static final int MAX_NOTES = 20;

    private final Map<String, Map<String, LongTask>> tasksByUser = new ConcurrentHashMap<>();
    private final MemoryStateStore memoryStateStore;

    public record AutoAdvanceResult(int claimedCount, int advancedCount, int completedCount) {
    }

    public LongTaskService() {
        this(MemoryStateStore.noOp());
    }

    @Autowired
    public LongTaskService(MemoryStateStore memoryStateStore) {
        this.memoryStateStore = memoryStateStore == null ? MemoryStateStore.noOp() : memoryStateStore;
        loadState();
    }

    public LongTask createTask(String userId,
                               String title,
                               String objective,
                               List<String> steps,
                               Instant dueAt,
                               Instant nextCheckAt) {
        String normalizedUserId = normalizeText(userId, "local-user");
        String normalizedTitle = normalizeText(title, "Untitled long task");
        String normalizedObjective = normalizeText(objective, "");
        List<String> pendingSteps = normalizeSteps(steps);
        Instant now = Instant.now();
        Instant effectiveNextCheck = nextCheckAt == null ? now : nextCheckAt;

        LongTask task = new LongTask(
                UUID.randomUUID().toString(),
                normalizedUserId,
                normalizedTitle,
                normalizedObjective,
                LongTaskStatus.PENDING,
                0,
                pendingSteps,
                List.of(),
                List.of(),
                "",
                now,
                now,
                dueAt,
                effectiveNextCheck,
                "",
                null
        );

        Map<String, LongTask> userTasks = tasksByUser.computeIfAbsent(normalizedUserId, key -> new ConcurrentHashMap<>());
        synchronized (userTasks) {
            userTasks.put(task.taskId(), task);
            persistState();
        }
        return task;
    }

    public List<LongTask> listTasks(String userId, String statusFilter) {
        String normalizedUserId = normalizeText(userId, "local-user");
        LongTaskStatus status = parseStatus(statusFilter);
        return tasksByUser.getOrDefault(normalizedUserId, Map.of()).values().stream()
                .filter(task -> status == null || task.status() == status)
                .sorted(Comparator.comparing(LongTask::updatedAt).reversed())
                .toList();
    }

    public LongTask getTask(String userId, String taskId) {
        String normalizedUserId = normalizeText(userId, "local-user");
        return tasksByUser.getOrDefault(normalizedUserId, Map.of()).get(taskId);
    }

    public List<String> listUserIds() {
        return tasksByUser.keySet().stream().sorted().toList();
    }

    public List<LongTask> claimReadyTasks(String userId, String workerId, int limit, long leaseSeconds) {
        String normalizedUserId = normalizeText(userId, "local-user");
        String normalizedWorker = normalizeText(workerId, "assistant-worker");
        int effectiveLimit = Math.max(1, limit);
        long effectiveLeaseSeconds = Math.max(5L, leaseSeconds);

        Map<String, LongTask> userTasks = tasksByUser.computeIfAbsent(normalizedUserId, key -> new ConcurrentHashMap<>());
        if (userTasks.isEmpty()) {
            return List.of();
        }

        synchronized (userTasks) {
            Instant now = Instant.now();
            Instant leaseUntil = now.plusSeconds(effectiveLeaseSeconds);
            List<LongTask> claimed = new ArrayList<>();
            List<LongTask> ordered = userTasks.values().stream()
                    .sorted(Comparator.comparing(LongTask::updatedAt))
                    .toList();

            for (LongTask task : ordered) {
                if (claimed.size() >= effectiveLimit) {
                    break;
                }
                if (!isClaimable(task, now)) {
                    continue;
                }

                LongTask updated = new LongTask(
                        task.taskId(),
                        task.userId(),
                        task.title(),
                        task.objective(),
                        LongTaskStatus.RUNNING,
                        task.progressPercent(),
                        task.pendingSteps(),
                        task.completedSteps(),
                        task.recentNotes(),
                        task.blockedReason(),
                        task.createdAt(),
                        now,
                        task.dueAt(),
                        now,
                        normalizedWorker,
                        leaseUntil
                );
                userTasks.put(task.taskId(), updated);
                claimed.add(updated);
            }
            if (!claimed.isEmpty()) {
                persistState();
            }
            return List.copyOf(claimed);
        }
    }

    public LongTask updateProgress(String userId,
                                   String taskId,
                                   String workerId,
                                   String completedStep,
                                   String note,
                                   String blockedReason,
                                   Instant nextCheckAt,
                                   boolean markCompleted) {
        String normalizedUserId = normalizeText(userId, "local-user");
        Map<String, LongTask> userTasks = tasksByUser.computeIfAbsent(normalizedUserId, key -> new ConcurrentHashMap<>());

        synchronized (userTasks) {
            LongTask current = userTasks.get(taskId);
            if (current == null) {
                return null;
            }

            String normalizedWorker = normalizeText(workerId, "assistant-worker");
            if (current.leaseOwner() != null && !current.leaseOwner().isBlank()
                    && !current.leaseOwner().equals(normalizedWorker)) {
                throw new IllegalArgumentException("task lease is owned by another worker: " + current.leaseOwner());
            }

            List<String> pending = new ArrayList<>(current.pendingSteps());
            List<String> completed = new ArrayList<>(current.completedSteps());
            String normalizedCompletedStep = normalizeText(completedStep, "");
            if (!normalizedCompletedStep.isBlank() && pending.remove(normalizedCompletedStep)) {
                completed.add(normalizedCompletedStep);
            }

            List<String> notes = appendNote(current.recentNotes(), note, normalizedWorker);
            String normalizedBlockedReason = normalizeText(blockedReason, "");

            LongTaskStatus nextStatus;
            if (markCompleted || pending.isEmpty()) {
                nextStatus = LongTaskStatus.COMPLETED;
                normalizedBlockedReason = "";
            } else if (!normalizedBlockedReason.isBlank()) {
                nextStatus = LongTaskStatus.BLOCKED;
            } else {
                nextStatus = LongTaskStatus.RUNNING;
            }

            Instant now = Instant.now();
            Instant effectiveNextCheck = nextCheckAt == null ? now : nextCheckAt;
            int progress = calculateProgressPercent(completed.size(), pending.size());
            String leaseOwner = nextStatus.isTerminal() || nextStatus == LongTaskStatus.BLOCKED ? "" : normalizedWorker;
            Instant leaseUntil = nextStatus.isTerminal() || nextStatus == LongTaskStatus.BLOCKED
                    ? null
                    : (current.leaseUntil() == null ? now.plusSeconds(300) : current.leaseUntil());

            LongTask updated = new LongTask(
                    current.taskId(),
                    current.userId(),
                    current.title(),
                    current.objective(),
                    nextStatus,
                    progress,
                    List.copyOf(pending),
                    List.copyOf(completed),
                    notes,
                    normalizedBlockedReason,
                    current.createdAt(),
                    now,
                    current.dueAt(),
                    effectiveNextCheck,
                    leaseOwner,
                    leaseUntil
            );
            userTasks.put(taskId, updated);
            persistState();
            return updated;
        }
    }

    public LongTask updateStatus(String userId,
                                 String taskId,
                                 LongTaskStatus status,
                                 String note,
                                 Instant nextCheckAt) {
        String normalizedUserId = normalizeText(userId, "local-user");
        Map<String, LongTask> userTasks = tasksByUser.computeIfAbsent(normalizedUserId, key -> new ConcurrentHashMap<>());

        synchronized (userTasks) {
            LongTask current = userTasks.get(taskId);
            if (current == null) {
                return null;
            }
            LongTaskStatus nextStatus = status == null ? current.status() : status;
            Instant now = Instant.now();
            List<String> notes = appendNote(current.recentNotes(), note, "system");
            Instant effectiveNextCheck = nextCheckAt == null ? current.nextCheckAt() : nextCheckAt;
            String leaseOwner = nextStatus.isTerminal() ? "" : current.leaseOwner();
            Instant leaseUntil = nextStatus.isTerminal() ? null : current.leaseUntil();

            LongTask updated = new LongTask(
                    current.taskId(),
                    current.userId(),
                    current.title(),
                    current.objective(),
                    nextStatus,
                    current.progressPercent(),
                    current.pendingSteps(),
                    current.completedSteps(),
                    notes,
                    current.blockedReason(),
                    current.createdAt(),
                    now,
                    current.dueAt(),
                    effectiveNextCheck,
                    leaseOwner,
                    leaseUntil
            );
            userTasks.put(taskId, updated);
            persistState();
            return updated;
        }
    }

    private void loadState() {
        Map<String, Map<String, LongTask>> persisted = memoryStateStore.readState(
                STATE_FILE,
                new TypeReference<>() {
                },
                Map::of
        );
        persisted.forEach((userId, tasks) -> {
            if (userId == null || tasks == null || tasks.isEmpty()) {
                return;
            }
            tasksByUser.put(userId, new ConcurrentHashMap<>(tasks));
        });
    }

    private void persistState() {
        Map<String, Map<String, LongTask>> snapshot = new ConcurrentHashMap<>();
        tasksByUser.forEach((userId, tasks) -> snapshot.put(userId, new ConcurrentHashMap<>(tasks)));
        memoryStateStore.writeState(STATE_FILE, snapshot);
    }

    public AutoAdvanceResult autoAdvanceReadyTasks(String userId,
                                                   String workerId,
                                                   int limit,
                                                   long leaseSeconds,
                                                   long nextCheckDelaySeconds) {
        String normalizedUserId = normalizeText(userId, "local-user");
        String normalizedWorker = normalizeText(workerId, "assistant-worker");
        long effectiveNextDelay = Math.max(5L, nextCheckDelaySeconds);
        List<LongTask> claimed = claimReadyTasks(normalizedUserId, normalizedWorker, limit, leaseSeconds);
        int advanced = 0;
        int completed = 0;

        for (LongTask task : claimed) {
            String step = task.pendingSteps().isEmpty() ? null : task.pendingSteps().get(0);
            boolean markCompleted = task.pendingSteps().size() <= 1;
            LongTask updated = updateProgress(
                    normalizedUserId,
                    task.taskId(),
                    normalizedWorker,
                    step,
                    "auto-run advanced step: " + (step == null ? "(none)" : step),
                    "",
                    Instant.now().plusSeconds(effectiveNextDelay),
                    markCompleted
            );
            if (updated != null) {
                advanced++;
                if (updated.status() == LongTaskStatus.COMPLETED) {
                    completed++;
                }
            }
        }
        return new AutoAdvanceResult(claimed.size(), advanced, completed);
    }

    private boolean isClaimable(LongTask task, Instant now) {
        if (task == null || task.status().isTerminal()) {
            return false;
        }
        if (task.nextCheckAt() != null && task.nextCheckAt().isAfter(now)) {
            return false;
        }
        return task.leaseUntil() == null || !task.leaseUntil().isAfter(now);
    }

    private List<String> normalizeSteps(List<String> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String step : steps) {
            String value = normalizeText(step, "");
            if (!value.isBlank()) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private List<String> appendNote(List<String> existing, String note, String workerId) {
        String normalized = normalizeText(note, "");
        if (normalized.isBlank()) {
            return existing == null ? List.of() : existing;
        }
        List<String> updated = new ArrayList<>(existing == null ? List.of() : existing);
        String author = normalizeText(workerId, "system");
        updated.add(author + ": " + normalized);
        int fromIndex = Math.max(0, updated.size() - MAX_NOTES);
        return List.copyOf(updated.subList(fromIndex, updated.size()));
    }

    private int calculateProgressPercent(int completedCount, int pendingCount) {
        int total = Math.max(0, completedCount + pendingCount);
        if (total == 0) {
            return 0;
        }
        return (int) Math.round(completedCount * 100.0 / total);
    }

    private String normalizeText(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? fallback : normalized;
    }

    private LongTaskStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        try {
            return LongTaskStatus.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
