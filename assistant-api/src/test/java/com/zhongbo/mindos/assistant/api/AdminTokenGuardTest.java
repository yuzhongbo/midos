package com.zhongbo.mindos.assistant.api;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminTokenGuardTest {

    @Test
    void shouldAllowWhenAdminTokenNotConfigured() {
        SecurityAuditLogService auditLogService = mock(SecurityAuditLogService.class);
        AdminTokenGuard guard = new AdminTokenGuard("X-Test-Admin", "   ", auditLogService);

        guard.verify(mock(HttpServletRequest.class), "actor", "security.audit.read", "security-audit");

        verifyNoInteractions(auditLogService);
    }

    @Test
    void shouldAllowWhenProvidedAdminTokenMatches() {
        SecurityAuditLogService auditLogService = mock(SecurityAuditLogService.class);
        AdminTokenGuard guard = new AdminTokenGuard("X-Test-Admin", "test-admin-token", auditLogService);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Test-Admin")).thenReturn(" test-admin-token ");

        guard.verify(request, "actor", "security.audit.read", "security-audit");

        verifyNoInteractions(auditLogService);
    }

    @Test
    void shouldRejectAndAuditWhenAdminTokenInvalid() {
        SecurityAuditLogService auditLogService = mock(SecurityAuditLogService.class);
        when(auditLogService.resolveTraceId(any())).thenReturn("trace-1");
        AdminTokenGuard guard = new AdminTokenGuard("X-Test-Admin", "test-admin-token", auditLogService);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Test-Admin")).thenReturn("wrong-token");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("JUnit");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> guard.verify(request, "audit-reader", "security.audit.read", "security-audit"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(auditLogService).record(
                eq("trace-1"),
                eq("audit-reader"),
                eq("security.audit.read"),
                eq("security.audit.read@security-audit"),
                eq("denied"),
                eq("invalid_admin_token"),
                eq("127.0.0.1"),
                eq("JUnit")
        );
    }

    @Test
    void shouldHandleNullRequestAndStillRejectWithAudit() {
        SecurityAuditLogService auditLogService = mock(SecurityAuditLogService.class);
        when(auditLogService.resolveTraceId(null)).thenReturn("trace-null");
        AdminTokenGuard guard = new AdminTokenGuard("X-Test-Admin", "test-admin-token", auditLogService);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> guard.verify(null, "audit-reader", "security.audit.query", "security-audit"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(auditLogService).record(
                eq("trace-null"),
                eq("audit-reader"),
                eq("security.audit.query"),
                eq("security.audit.query@security-audit"),
                eq("denied"),
                eq("invalid_admin_token"),
                eq(""),
                eq("")
        );
    }
}

