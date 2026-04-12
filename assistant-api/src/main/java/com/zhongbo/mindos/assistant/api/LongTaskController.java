package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.common.dto.LongTaskCreateRequestDto;
import com.zhongbo.mindos.assistant.common.dto.LongTaskAutoRunResultDto;
import com.zhongbo.mindos.assistant.common.dto.LongTaskDto;
import com.zhongbo.mindos.assistant.common.dto.LongTaskProgressUpdateDto;
import com.zhongbo.mindos.assistant.common.dto.LongTaskStatusUpdateDto;
import com.zhongbo.mindos.assistant.memory.LongTaskCommandService;
import com.zhongbo.mindos.assistant.memory.MemoryFacade;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class LongTaskController {

    private final MemoryFacade memoryFacade;
    private final LongTaskCommandOrchestrator longTaskCommandOrchestrator;
    private final LongTaskAutoRunner longTaskAutoRunner;
    private final SecurityPolicyGuard securityPolicyGuard;

    public LongTaskController(MemoryFacade memoryFacade,
                              LongTaskCommandOrchestrator longTaskCommandOrchestrator,
                              LongTaskAutoRunner longTaskAutoRunner,
                              SecurityPolicyGuard securityPolicyGuard) {
        this.memoryFacade = memoryFacade;
        this.longTaskCommandOrchestrator = longTaskCommandOrchestrator;
        this.longTaskAutoRunner = longTaskAutoRunner;
        this.securityPolicyGuard = securityPolicyGuard;
    }

    @PostMapping("/{userId}")
    public LongTaskDto createTask(@PathVariable String userId,
                                  @RequestBody LongTaskCreateRequestDto request) {
        LongTask created = longTaskCommandOrchestrator.createTask(
                userId,
                request == null ? null : request.title(),
                request == null ? null : request.objective(),
                request == null ? List.of() : request.steps(),
                request == null ? null : request.dueAt(),
                request == null ? null : request.nextCheckAt()
        );
        return toDto(created);
    }

    @GetMapping("/{userId}")
    public List<LongTaskDto> listTasks(@PathVariable String userId,
                                       @RequestParam(required = false) String status) {
        return memoryFacade.listLongTasks(userId, status).stream().map(this::toDto).toList();
    }

    @GetMapping("/{userId}/{taskId}")
    public LongTaskDto getTask(@PathVariable String userId,
                               @PathVariable String taskId) {
        LongTask task = memoryFacade.getLongTask(userId, taskId);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found");
        }
        return toDto(task);
    }

    @PostMapping("/{userId}/claim")
    public List<LongTaskDto> claimReadyTasks(@PathVariable String userId,
                                             @RequestParam(defaultValue = "assistant-worker") String workerId,
                                             @RequestParam(defaultValue = "1") int limit,
                                             @RequestParam(defaultValue = "300") long leaseSeconds) {
        return longTaskCommandOrchestrator.claimReadyTasks(userId, workerId, limit, leaseSeconds)
                .stream().map(this::toDto).toList();
    }

    @PostMapping("/{userId}/auto-run")
    public LongTaskAutoRunResultDto autoRunOnce(@PathVariable String userId,
                                                HttpServletRequest servletRequest) {
        securityPolicyGuard.verifyRiskyOperationApproval(servletRequest, "tasks.auto-run", userId, userId);
        var result = longTaskAutoRunner.runOnceForUser(userId);
        return new LongTaskAutoRunResultDto(
                userId,
                longTaskAutoRunner.workerId(),
                result.claimedCount(),
                result.advancedCount(),
                result.completedCount()
        );
    }

    @PostMapping("/{userId}/{taskId}/progress")
    public LongTaskDto updateProgress(@PathVariable String userId,
                                      @PathVariable String taskId,
                                      @RequestBody LongTaskProgressUpdateDto request) {
        try {
            LongTask updated = longTaskCommandOrchestrator.updateProgress(
                    userId,
                    taskId,
                    request == null ? null : request.workerId(),
                    request == null ? null : request.completedStep(),
                    request == null ? null : request.note(),
                    request == null ? null : request.blockedReason(),
                    request == null ? null : request.nextCheckAt(),
                    request != null && request.markCompleted()
            );
            if (updated == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found");
            }
            return toDto(updated);
        } catch (IllegalArgumentException ex) {
            throw translateCommandFailure(ex);
        }
    }

    @PostMapping("/{userId}/{taskId}/status")
    public LongTaskDto updateStatus(@PathVariable String userId,
                                    @PathVariable String taskId,
                                    @RequestBody LongTaskStatusUpdateDto request) {
        try {
            LongTask updated = longTaskCommandOrchestrator.updateStatus(
                    userId,
                    taskId,
                    request == null ? null : request.status(),
                    request == null ? null : request.note(),
                    request == null ? null : request.nextCheckAt()
            );
            if (updated == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found");
            }
            return toDto(updated);
        } catch (IllegalArgumentException ex) {
            throw translateCommandFailure(ex);
        }
    }

    private ResponseStatusException translateCommandFailure(IllegalArgumentException ex) {
        String message = ex == null || ex.getMessage() == null || ex.getMessage().isBlank()
                ? "invalid task command"
                : ex.getMessage();
        HttpStatus status = message.startsWith("invalid status:") ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
        return new ResponseStatusException(status, message, ex);
    }

    private LongTaskDto toDto(LongTask task) {
        return new LongTaskDto(
                task.taskId(),
                task.userId(),
                task.title(),
                task.objective(),
                task.status().name(),
                task.progressPercent(),
                task.pendingSteps(),
                task.completedSteps(),
                task.recentNotes(),
                task.blockedReason(),
                task.createdAt(),
                task.updatedAt(),
                task.dueAt(),
                task.nextCheckAt(),
                task.leaseOwner(),
                task.leaseUntil()
        );
    }
}
