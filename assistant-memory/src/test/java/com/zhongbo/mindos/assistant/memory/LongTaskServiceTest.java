package com.zhongbo.mindos.assistant.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LongTaskServiceTest {

    @Test
    void shouldPersistLongTasksAcrossRestarts(@TempDir Path tempDir) {
        MemoryStateStore stateStore = new FileMemoryStateStore(true, tempDir, new ObjectMapper());
        LongTaskService first = new LongTaskService(stateStore);

        LongTask created = first.createTask(
                "u1",
                "整理发布计划",
                "确保版本发布前检查完成",
                List.of("确认范围", "执行回归"),
                Instant.parse("2026-04-10T00:00:00Z"),
                Instant.parse("2026-04-03T00:00:00Z")
        );
        first.claimReadyTasks("u1", "worker-a", 1, 120);
        first.updateProgress("u1", created.taskId(), "worker-a", "确认范围", "范围已确认", "", Instant.parse("2026-04-04T00:00:00Z"), false);

        LongTaskService second = new LongTaskService(new FileMemoryStateStore(true, tempDir, new ObjectMapper()));

        LongTask restored = second.getTask("u1", created.taskId());
        assertNotNull(restored);
        assertEquals(LongTaskStatus.RUNNING, restored.status());
        assertEquals(50, restored.progressPercent());
        assertEquals(List.of("执行回归"), restored.pendingSteps());
        assertEquals(List.of("确认范围"), restored.completedSteps());
        assertEquals("worker-a", restored.leaseOwner());
        assertEquals(1, second.listTasks("u1", null).size());
    }
}
