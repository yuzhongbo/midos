package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.common.dto.SecurityAuditWriteMetricsDto;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityAuditLogServiceTest {

    @Test
    void shouldLimitPartitionFileScanToMostRecentFiles() throws Exception {
        Path file = Path.of("target/security-audit-scan-cap-test.log");
        Files.deleteIfExists(file);
        for (int i = 1; i <= 4; i++) {
            Files.deleteIfExists(Path.of("target/security-audit-scan-cap-test-2026-03-0" + i + ".log"));
        }

        Instant now = Instant.now();
        writePartitionLine(file, LocalDate.of(2026, 3, 1), "trace-day-1", now.minusSeconds(3_000));
        writePartitionLine(file, LocalDate.of(2026, 3, 2), "trace-day-2", now.minusSeconds(2_000));
        writePartitionLine(file, LocalDate.of(2026, 3, 3), "trace-day-3", now.minusSeconds(1_000));
        writePartitionLine(file, LocalDate.of(2026, 3, 4), "trace-day-4", now.minusSeconds(100));

        SecurityAuditLogService service = new SecurityAuditLogService(
                true,
                file.toString(),
                "X-Trace-Id",
                "test-signing-key",
                "v1",
                "",
                300,
                true,
                false,
                2,
                64,
                100,
                60_000
        );
        try {
            var response = service.queryRecent(10, "", null, null, "allowed", null, null, null);
            Set<String> traceIds = response.items().stream().map(item -> item.traceId()).collect(java.util.stream.Collectors.toSet());
            assertTrue(traceIds.contains("trace-day-3"));
            assertTrue(traceIds.contains("trace-day-4"));
            assertTrue(!traceIds.contains("trace-day-1"));
            assertTrue(!traceIds.contains("trace-day-2"));
        } finally {
            service.shutdownAuditWriter();
            Files.deleteIfExists(file);
            for (int i = 1; i <= 4; i++) {
                Files.deleteIfExists(Path.of("target/security-audit-scan-cap-test-2026-03-0" + i + ".log"));
            }
        }
    }

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
                400,
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
                400,
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

    private void writePartitionLine(Path baseFile, LocalDate day, String traceId, Instant timestamp) throws Exception {
        String baseName = baseFile.getFileName().toString();
        int dot = baseName.lastIndexOf('.');
        String prefix = dot > 0 ? baseName.substring(0, dot) : baseName;
        String suffix = dot > 0 ? baseName.substring(dot) : "";
        Path file = Path.of("target", prefix + "-" + day.atStartOfDay(ZoneOffset.UTC).toLocalDate() + suffix);
        String line = "{\"timestamp\":\"" + timestamp + "\",\"traceId\":\"" + traceId
                + "\",\"actor\":\"a\",\"operation\":\"op\",\"resource\":\"r\",\"result\":\"allowed\","
                + "\"reason\":\"ok\",\"remoteAddress\":\"127.0.0.1\",\"userAgent\":\"JUnit\"}";
        Files.write(file, List.of(line), StandardCharsets.UTF_8);
    }
}
