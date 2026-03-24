package com.zhongbo.mindos.assistant.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AdminTokenGuard {

    private final String adminTokenHeaderName;
    private final String adminToken;
    private final SecurityAuditLogService securityAuditLogService;

    public AdminTokenGuard(
            @Value("${mindos.security.risky-ops.admin-token-header:X-MindOS-Admin-Token}") String adminTokenHeaderName,
            @Value("${mindos.security.risky-ops.admin-token:}") String adminToken,
            SecurityAuditLogService securityAuditLogService) {
        this.adminTokenHeaderName = adminTokenHeaderName == null || adminTokenHeaderName.isBlank()
                ? "X-MindOS-Admin-Token"
                : adminTokenHeaderName.trim();
        this.adminToken = adminToken == null ? "" : adminToken.trim();
        this.securityAuditLogService = securityAuditLogService;
    }

    public void verify(HttpServletRequest request, String actor, String operation, String resource) {
        if (adminToken.isBlank()) {
            return;
        }
        String provided = request == null ? "" : normalize(request.getHeader(adminTokenHeaderName), "");
        if (adminToken.equals(provided)) {
            return;
        }
        String traceId = securityAuditLogService.resolveTraceId(request);
        securityAuditLogService.record(
                traceId,
                actor,
                operation,
                operation + "@" + normalize(resource, "*"),
                "denied",
                "invalid_admin_token",
                request == null ? "" : normalize(request.getRemoteAddr(), ""),
                request == null ? "" : normalize(request.getHeader("User-Agent"), "")
        );
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid admin token");
    }

    private String normalize(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? fallback : normalized;
    }
}

