package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class LongTaskCommandService {

    private final MemoryFacade memoryFacade;

    @Autowired
    public LongTaskCommandService(MemoryFacade memoryFacade) {
        this.memoryFacade = memoryFacade;
    }

    public LongTask createTask(String userId,
                               String title,
                               String objective,
                               List<String> steps,
                               Instant dueAt,
                               Instant nextCheckAt) {
        return memoryFacade.createLongTask(userId, title, objective, steps, dueAt, nextCheckAt);
    }

    public List<LongTask> claimReadyTasks(String userId,
                                          String workerId,
                                          int limit,
                                          long leaseSeconds) {
        return memoryFacade.claimReadyLongTasks(userId, workerId, limit, leaseSeconds);
    }

    public LongTask updateProgress(String userId,
                                   String taskId,
                                   String workerId,
                                   String completedStep,
                                   String note,
                                   String blockedReason,
                                   Instant nextCheckAt,
                                   boolean markCompleted) {
        return memoryFacade.updateLongTaskProgress(
                userId,
                taskId,
                workerId,
                completedStep,
                note,
                blockedReason,
                nextCheckAt,
                markCompleted
        );
    }

    public LongTask updateStatus(String userId,
                                 String taskId,
                                 String status,
                                 String note,
                                 Instant nextCheckAt) {
        return updateStatus(userId, taskId, parseStatus(status), note, nextCheckAt);
    }

    public LongTask updateStatus(String userId,
                                 String taskId,
                                 LongTaskStatus status,
                                 String note,
                                 Instant nextCheckAt) {
        return memoryFacade.updateLongTaskStatus(userId, taskId, status, note, nextCheckAt);
    }

    private LongTaskStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return LongTaskStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid status: " + status, ex);
        }
    }
}
