package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.common.dto.SecurityAuditWriteMetricsDto;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityAuditLogServiceTest {

    @Test
    void shouldThrottleFlushWarningsWhileCountingTimeouts() throws Exception {
        SecurityAuditLogService service = new SecurityAuditLogService(
                true,
                "target/security-audit-flush-throttle-test.log",
                "X-Trace-Id",
                "test-signing-key",
                "v1",
                "",
                300,
                64,
                5,
                60_000
        );
        try {
            Object auditFileWriteLock = readAuditFileWriteLock(service);
            AtomicLong lastWarningEpochMillis = readLastWarningEpochMillis(service);

            synchronized (auditFileWriteLock) {
                service.record("trace-1", "actor", "operation", "resource", "allowed", "ok", "127.0.0.1", "JUnit");
                Thread.sleep(20);

                service.readRecent(5);
                long firstWarningTimestamp = lastWarningEpochMillis.get();
                assertTrue(firstWarningTimestamp > 0, "first throttled warning timestamp should be recorded");

                service.readRecent(5);
                long secondWarningTimestamp = lastWarningEpochMillis.get();

                SecurityAuditWriteMetricsDto metrics = service.getWriteMetrics();
                assertTrue(metrics.flushTimeoutCount() >= 2, "flush timeout counter should still track every timeout");
                assertEquals(firstWarningTimestamp, secondWarningTimestamp,
                        "warning timestamp should stay unchanged within throttle interval");
            }
        } finally {
            service.shutdownAuditWriter();
        }
    }

    private AtomicLong readLastWarningEpochMillis(SecurityAuditLogService service) throws Exception {
        Field field = SecurityAuditLogService.class.getDeclaredField("lastFlushWarningEpochMillis");
        field.setAccessible(true);
        return (AtomicLong) field.get(service);
    }

    private Object readAuditFileWriteLock(SecurityAuditLogService service) throws Exception {
        Field field = SecurityAuditLogService.class.getDeclaredField("auditFileWriteLock");
        field.setAccessible(true);
        return field.get(service);
    }
}
