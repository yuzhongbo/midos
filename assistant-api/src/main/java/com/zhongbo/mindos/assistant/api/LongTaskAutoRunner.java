package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.memory.LongTaskService;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.logging.Logger;

@Component
public class LongTaskAutoRunner {

    private static final Logger LOGGER = Logger.getLogger(LongTaskAutoRunner.class.getName());

    private final MemoryManager memoryManager;
    private final boolean enabled;
    private final String workerId;
    private final int claimLimit;
    private final long leaseSeconds;
    private final long nextCheckDelaySeconds;

    public LongTaskAutoRunner(MemoryManager memoryManager,
                              @Value("${mindos.tasks.auto-run.enabled:false}") boolean enabled,
                              @Value("${mindos.tasks.auto-run.worker-id:auto-runner}") String workerId,
                              @Value("${mindos.tasks.auto-run.claim-limit:2}") int claimLimit,
                              @Value("${mindos.tasks.auto-run.lease-seconds:180}") long leaseSeconds,
                              @Value("${mindos.tasks.auto-run.next-check-delay-seconds:120}") long nextCheckDelaySeconds) {
        this.memoryManager = memoryManager;
        this.enabled = enabled;
        this.workerId = workerId;
        this.claimLimit = Math.max(1, claimLimit);
        this.leaseSeconds = Math.max(5L, leaseSeconds);
        this.nextCheckDelaySeconds = Math.max(5L, nextCheckDelaySeconds);
    }

    @Scheduled(fixedDelayString = "${mindos.tasks.auto-run.fixed-delay-ms:30000}")
    public void autoRun() {
        if (!enabled) {
            return;
        }
        for (String userId : memoryManager.listLongTaskUsers()) {
            runOnceForUser(userId);
        }
    }

    public LongTaskService.AutoAdvanceResult runOnceForUser(String userId) {
        LongTaskService.AutoAdvanceResult result = memoryManager.autoAdvanceLongTasks(
                userId,
                workerId,
                claimLimit,
                leaseSeconds,
                nextCheckDelaySeconds
        );
        if (result.claimedCount() > 0 || result.advancedCount() > 0 || result.completedCount() > 0) {
            LOGGER.info("LongTask auto-run userId=" + userId
                    + ", workerId=" + workerId
                    + ", claimed=" + result.claimedCount()
                    + ", advanced=" + result.advancedCount()
                    + ", completed=" + result.completedCount());
        }
        return result;
    }

    public List<String> listUsers() {
        return memoryManager.listLongTaskUsers();
    }

    public String workerId() {
        return workerId;
    }
}


