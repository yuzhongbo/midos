package com.zhongbo.mindos.assistant.memory;

import com.fasterxml.jackson.core.type.TypeReference;
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
public class LongTaskService {

    private static final String STATE_FILE = "long-tasks.json";
    private static final int MAX_NOTES = 20;

    private final Map<String, Map<String, LongTask>> tasksByUser = new ConcurrentHashMap<>();
    private final MemoryStateStore memoryStateStore;
    private final LongGoalService longGoalService;

    public record AutoAdvanceResult(int claimedCount, int advancedCount, int completedCount) {
    }

    public record TaskSplitResult(LongTask parentTask, List<LongTask> childTasks) {
    }

    public LongTaskService() {
        this(MemoryStateStore.noOp(), new LongGoalService(MemoryStateStore.noOp()));
    }

    public LongTaskService(MemoryStateStore memoryStateStore) {
        this(memoryStateStore, new LongGoalService(MemoryStateStore.noOp()));
    }

    @Autowired
    public LongTaskService(MemoryStateStore memoryStateStore,
                           LongGoalService longGoalService) {
        this.memoryStateStore = memoryStateStore == null ? MemoryStateStore.noOp() : memoryStateStore;
        this.longGoalService = longGoalService == null ? new LongGoalService(MemoryStateStore.noOp()) : longGoalService;
        loadState();
    }

    public LongTask createTask(String userId,
                               String title,
                               String objective,
                               List<String> steps,
                               Instant dueAt,
                               Instant nextCheckAt) {
        return createTask(userId, title, objective, steps, dueAt, nextCheckAt, "", "");
    }

    public LongTask createTask(String userId,
                               String title,
                               String objective,
                               List<String> steps,
                               Instant dueAt,
                               Instant nextCheckAt,
                               String goalId) {
        return createTask(userId, title, objective, steps, dueAt, nextCheckAt, goalId, "");
    }

    public LongTask createTask(String userId,
                               String title,
                               String objective,
                               List<String> steps,
                               Instant dueAt,
                               Instant nextCheckAt,
                               String goalId,
                               String parentTaskId) {
        String normalizedUserId = normalizeText(userId, "local-user");
        String normalizedTitle = normalizeText(title, "Untitled long task");
        String normalizedObjective = normalizeText(objective, "");
        String normalizedGoalId = normalizeText(goalId, "");
        String normalizedParentTaskId = normalizeText(parentTaskId, "");
        List<String> pendingSteps = normalizeSteps(steps);
        Instant now = Instant.now();
        Instant effectiveNextCheck = nextCheckAt == null ? now : nextCheckAt;
        if (!normalizedGoalId.isBlank() && longGoalService.getGoal(normalizedUserId, normalizedGoalId) == null) {
            throw new IllegalArgumentException("goal not found: " + normalizedGoalId);
        }

        Map<String, LongTask> userTasks = tasksByUser.computeIfAbsent(normalizedUserId, key -> new ConcurrentHashMap<>());
        LongTask parentTask = normalizedParentTaskId.isBlank() ? null : userTasks.get(normalizedParentTaskId);
        if (!normalizedParentTaskId.isBlank() && parentTask == null) {
            throw new IllegalArgumentException("parent task not found: " + normalizedParentTaskId);
        }

        LongTask task = new LongTask(
                UUID.randomUUID().toString(),
                normalizedUserId,
                normalizedTitle,
                normalizedObjective,
                normalizedGoalId,
                normalizedParentTaskId,
                List.of(),
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

        synchronized (userTasks) {
            userTasks.put(task.taskId(), task);
            if (parentTask != null) {
                userTasks.put(parentTask.taskId(), withChildTask(parentTask, task.taskId(), now));
                refreshParentChain(userTasks, parentTask.parentTaskId(), now);
            }
            persistState();
        }
        if (!normalizedGoalId.isBlank()) {
            longGoalService.linkTask(normalizedUserId, normalizedGoalId, task.taskId());
            syncGoal(normalizedUserId, normalizedGoalId);
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

    public TaskSplitResult splitTask(String userId,
                                     String taskId,
                                     String workerId,
                                     List<String> childSteps,
                                     String note,
                                     Instant nextCheckAt) {
        String normalizedUserId = normalizeText(userId, "local-user");
        Map<String, LongTask> userTasks = tasksByUser.computeIfAbsent(normalizedUserId, key -> new ConcurrentHashMap<>());
        List<LongTask> createdChildren = new ArrayList<>();
        LongTask updatedParent;

        synchronized (userTasks) {
            LongTask current = userTasks.get(taskId);
            if (current == null) {
                return null;
            }
            if (current.status().isTerminal()) {
                throw new IllegalArgumentException("task is already terminal: " + taskId);
            }
            String normalizedWorker = normalizeText(workerId, "assistant-worker");
            if (current.leaseOwner() != null
                    && !current.leaseOwner().isBlank()
                    && !current.leaseOwner().equals(normalizedWorker)) {
                throw new IllegalArgumentException("task lease is owned by another worker: " + current.leaseOwner());
            }

            List<String> selectedSteps = selectChildSteps(current.pendingSteps(), childSteps);
            if (selectedSteps.isEmpty()) {
                throw new IllegalArgumentException("no splittable steps found for task: " + taskId);
            }
            Instant now = Instant.now();
            List<String> remainingSteps = new ArrayList<>(current.pendingSteps());
            LinkedHashSet<String> childTaskIds = new LinkedHashSet<>(current.childTaskIds());
            for (String step : selectedSteps) {
                remainingSteps.remove(step);
                LongTask childTask = new LongTask(
                        UUID.randomUUID().toString(),
                        current.userId(),
                        step,
                        firstNonBlank(current.objective(), current.title()),
                        current.goalId(),
                        current.taskId(),
                        List.of(),
                        LongTaskStatus.PENDING,
                        0,
                        List.of(step),
                        List.of(),
                        List.of(),
                        "",
                        now,
                        now,
                        current.dueAt(),
                        nextCheckAt == null ? current.nextCheckAt() : nextCheckAt,
                        "",
                        null
                );
                userTasks.put(childTask.taskId(), childTask);
                createdChildren.add(childTask);
                childTaskIds.add(childTask.taskId());
            }

            List<String> notes = appendNote(
                    current.recentNotes(),
                    firstNonBlank(note, "split into " + createdChildren.size() + " child tasks"),
                    normalizedWorker
            );
            updatedParent = new LongTask(
                    current.taskId(),
                    current.userId(),
                    current.title(),
                    current.objective(),
                    current.goalId(),
                    current.parentTaskId(),
                    List.copyOf(childTaskIds),
                    current.status(),
                    current.progressPercent(),
                    List.copyOf(remainingSteps),
                    current.completedSteps(),
                    notes,
                    "",
                    current.createdAt(),
                    now,
                    current.dueAt(),
                    nextCheckAt == null ? current.nextCheckAt() : nextCheckAt,
                    current.leaseOwner(),
                    current.leaseUntil()
            );
            updatedParent = refreshAggregateTask(updatedParent, userTasks, now);
            userTasks.put(taskId, updatedParent);
            refreshParentChain(userTasks, updatedParent.parentTaskId(), now);
            persistState();
        }

        if (!updatedParent.goalId().isBlank()) {
            for (LongTask child : createdChildren) {
                longGoalService.linkTask(normalizedUserId, updatedParent.goalId(), child.taskId());
            }
            syncGoal(normalizedUserId, updatedParent.goalId());
        }
        return new TaskSplitResult(updatedParent, List.copyOf(createdChildren));
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
                        task.goalId(),
                        task.parentTaskId(),
                        task.childTaskIds(),
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
                    current.goalId(),
                    current.parentTaskId(),
                    current.childTaskIds(),
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
            updated = refreshAggregateTask(updated, userTasks, now);
            userTasks.put(taskId, updated);
            refreshParentChain(userTasks, current.parentTaskId(), now);
            persistState();
            syncGoal(normalizedUserId, current.goalId());
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
                    current.goalId(),
                    current.parentTaskId(),
                    current.childTaskIds(),
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
            updated = refreshAggregateTask(updated, userTasks, now);
            userTasks.put(taskId, updated);
            refreshParentChain(userTasks, current.parentTaskId(), now);
            persistState();
            syncGoal(normalizedUserId, current.goalId());
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
        if (task.childTaskIds() != null && !task.childTaskIds().isEmpty()) {
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

    private List<String> selectChildSteps(List<String> pendingSteps, List<String> requestedSteps) {
        List<String> pending = normalizeSteps(pendingSteps);
        if (pending.isEmpty()) {
            return List.of();
        }
        List<String> requested = normalizeSteps(requestedSteps);
        if (requested.isEmpty()) {
            return pending;
        }
        List<String> selected = new ArrayList<>();
        for (String step : requested) {
            if (!pending.contains(step)) {
                throw new IllegalArgumentException("pending step not found for split: " + step);
            }
            selected.add(step);
        }
        return List.copyOf(selected);
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

    private LongTask withChildTask(LongTask task, String childTaskId, Instant now) {
        LinkedHashSet<String> childTaskIds = new LinkedHashSet<>(task.childTaskIds());
        childTaskIds.add(childTaskId);
        return refreshAggregateTask(new LongTask(
                task.taskId(),
                task.userId(),
                task.title(),
                task.objective(),
                task.goalId(),
                task.parentTaskId(),
                List.copyOf(childTaskIds),
                task.status(),
                task.progressPercent(),
                task.pendingSteps(),
                task.completedSteps(),
                task.recentNotes(),
                task.blockedReason(),
                task.createdAt(),
                now,
                task.dueAt(),
                task.nextCheckAt(),
                task.leaseOwner(),
                task.leaseUntil()
        ), tasksByUser.getOrDefault(task.userId(), Map.of()), now);
    }

    private void refreshParentChain(Map<String, LongTask> userTasks, String parentTaskId, Instant now) {
        String currentParentId = normalizeText(parentTaskId, "");
        while (!currentParentId.isBlank()) {
            LongTask parent = userTasks.get(currentParentId);
            if (parent == null) {
                return;
            }
            LongTask refreshed = refreshAggregateTask(parent, userTasks, now);
            userTasks.put(refreshed.taskId(), refreshed);
            currentParentId = refreshed.parentTaskId();
        }
    }

    private LongTask refreshAggregateTask(LongTask current, Map<String, LongTask> userTasks, Instant now) {
        if (current == null || current.childTaskIds().isEmpty() || current.status() == LongTaskStatus.CANCELLED) {
            return current;
        }
        List<LongTask> children = current.childTaskIds().stream()
                .map(userTasks::get)
                .filter(task -> task != null)
                .toList();
        if (children.isEmpty()) {
            return current;
        }
        int progress = aggregateProgress(current, children);
        LongTaskStatus status = aggregateStatus(current, children);
        String blockedReason = status == LongTaskStatus.BLOCKED
                ? children.stream()
                .filter(child -> child.status() == LongTaskStatus.BLOCKED)
                .map(LongTask::blockedReason)
                .filter(reason -> reason != null && !reason.isBlank())
                .findFirst()
                .orElse(current.blockedReason())
                : "";
        String leaseOwner = status.isTerminal() || status == LongTaskStatus.BLOCKED ? "" : current.leaseOwner();
        Instant leaseUntil = status.isTerminal() || status == LongTaskStatus.BLOCKED ? null : current.leaseUntil();
        return new LongTask(
                current.taskId(),
                current.userId(),
                current.title(),
                current.objective(),
                current.goalId(),
                current.parentTaskId(),
                current.childTaskIds(),
                status,
                progress,
                current.pendingSteps(),
                current.completedSteps(),
                current.recentNotes(),
                blockedReason,
                current.createdAt(),
                now,
                current.dueAt(),
                current.nextCheckAt(),
                leaseOwner,
                leaseUntil
        );
    }

    private int aggregateProgress(LongTask current, List<LongTask> children) {
        int units = 0;
        int totalProgress = 0;
        int directUnits = current.completedSteps().size() + current.pendingSteps().size();
        if (directUnits > 0) {
            totalProgress += calculateProgressPercent(current.completedSteps().size(), current.pendingSteps().size());
            units++;
        }
        for (LongTask child : children) {
            totalProgress += Math.max(0, Math.min(100, child.progressPercent()));
            units++;
        }
        if (units <= 0) {
            return current.progressPercent();
        }
        return (int) Math.round(totalProgress / (double) units);
    }

    private LongTaskStatus aggregateStatus(LongTask current, List<LongTask> children) {
        if (children.stream().anyMatch(child -> child.status() == LongTaskStatus.BLOCKED)) {
            return LongTaskStatus.BLOCKED;
        }
        boolean allChildrenCompleted = children.stream().allMatch(child -> child.status() == LongTaskStatus.COMPLETED);
        boolean anyChildStarted = children.stream().anyMatch(child -> child.status() != LongTaskStatus.PENDING);
        boolean hasPendingDirectSteps = !current.pendingSteps().isEmpty();
        if (!hasPendingDirectSteps && allChildrenCompleted) {
            return LongTaskStatus.COMPLETED;
        }
        if (current.status() == LongTaskStatus.PENDING && !anyChildStarted && !allChildrenCompleted) {
            return LongTaskStatus.PENDING;
        }
        return LongTaskStatus.RUNNING;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalizeText(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? fallback : normalized;
    }

    private void syncGoal(String userId, String goalId) {
        String normalizedGoalId = normalizeText(goalId, "");
        if (normalizedGoalId.isBlank()) {
            return;
        }
        List<LongTask> userTasks = listTasks(userId, null);
        longGoalService.syncWithTasks(userId, normalizedGoalId, userTasks);
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
