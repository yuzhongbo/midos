package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.common.dto.SecurityAuditWriteMetricsDto;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityAuditLogServiceTest {

    @Test
    void shouldKeepToBoundQueryCorrectWhenChronologicalAssumptionDisabled() throws Exception {
        Path file = Path.of("target/security-audit-out-of-order-test.log");
        Files.deleteIfExists(file);

        Instant now = Instant.now();
        String futureLine = "{\"timestamp\":\"" + now.plusSeconds(600) + "\",\"traceId\":\"trace-new\",\"actor\":\"a\",\"operation\":\"op\",\"resource\":\"r\",\"result\":\"allowed\",\"reason\":\"ok\",\"remoteAddress\":\"127.0.0.1\",\"userAgent\":\"JUnit\"}";
        String inRangeLine = "{\"timestamp\":\"" + now.minusSeconds(60) + "\",\"traceId\":\"trace-old\",\"actor\":\"a\",\"operation\":\"op\",\"resource\":\"r\",\"result\":\"allowed\",\"reason\":\"ok\",\"remoteAddress\":\"127.0.0.1\",\"userAgent\":\"JUnit\"}";
        Files.write(file, List.of(futureLine, inRangeLine), StandardCharsets.UTF_8);

        SecurityAuditLogService service = new SecurityAuditLogService(
                true,
                file.toString(),
                "X-Trace-Id",
                "test-signing-key",
                "v1",
                "",
                300,
                false,
                false,
                64,
                100,
                60_000
        );
        try {
            var response = service.queryRecent(10, "", null, null, "allowed", null, null, now.toString());
            assertEquals(1, response.items().size());
            assertEquals("trace-old", response.items().get(0).traceId());
        } finally {
            service.shutdownAuditWriter();
            Files.deleteIfExists(file);
        }
    }

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
                false,
                true,
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
