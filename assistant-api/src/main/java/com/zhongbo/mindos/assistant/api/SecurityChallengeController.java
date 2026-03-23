package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.common.dto.SecurityChallengeRequestDto;
import com.zhongbo.mindos.assistant.common.dto.SecurityChallengeResponseDto;
import com.zhongbo.mindos.assistant.common.dto.SecurityAuditQueryResponseDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.zhongbo.mindos.assistant.common.dto.SecurityAuditEventDto;

import java.util.List;

@RestController
@RequestMapping("/api/security")
public class SecurityChallengeController {

    private final String adminTokenHeaderName;
    private final String adminToken;
    private final SecurityChallengeService securityChallengeService;
    private final SecurityAuditLogService securityAuditLogService;

    public SecurityChallengeController(
            @Value("${mindos.security.risky-ops.admin-token-header:X-MindOS-Admin-Token}") String adminTokenHeaderName,
            @Value("${mindos.security.risky-ops.admin-token:}") String adminToken,
            SecurityChallengeService securityChallengeService,
            SecurityAuditLogService securityAuditLogService) {
        this.adminTokenHeaderName = adminTokenHeaderName;
        this.adminToken = adminToken == null ? "" : adminToken.trim();
        this.securityChallengeService = securityChallengeService;
        this.securityAuditLogService = securityAuditLogService;
    }

    @PostMapping("/challenge")
    public SecurityChallengeResponseDto issueChallenge(@RequestBody SecurityChallengeRequestDto request,
                                                       HttpServletRequest servletRequest) {
        if (request == null || request.operation() == null || request.operation().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "operation is required");
        }
        String actor = normalize(request.actor(), "unknown");
        String traceId = securityAuditLogService.resolveTraceId(servletRequest);
        verifyAdminToken(servletRequest, actor, request.operation(), request.resource());

        SecurityChallengeService.SecurityChallenge challenge = securityChallengeService.issue(
                request.operation(),
                request.resource(),
                actor,
                servletRequest == null ? "*" : normalize(servletRequest.getRemoteAddr(), "*"),
                request.ttlSeconds()
        );
        securityAuditLogService.record(traceId,
                actor,
                "security.challenge.issue",
                request.operation() + "@" + normalize(request.resource(), "*"),
                "allowed",
                "issued",
                servletRequest == null ? "" : normalize(servletRequest.getRemoteAddr(), ""),
                servletRequest == null ? "" : normalize(servletRequest.getHeader("User-Agent"), ""));

        return new SecurityChallengeResponseDto(
                challenge.token(),
                challenge.operation(),
                challenge.resource(),
                challenge.actor(),
                challenge.expiresAt()
        );
    }

    @GetMapping("/audit")
    public List<SecurityAuditEventDto> recentAudit(@RequestParam(defaultValue = "50") int limit,
                                                   HttpServletRequest servletRequest) {
        String actor = "audit-reader";
        verifyAdminToken(servletRequest, actor, "security.audit.read", "security-audit");
        return securityAuditLogService.readRecent(Math.max(1, limit));
    }

    @GetMapping("/audit/query")
    public SecurityAuditQueryResponseDto queryAudit(@RequestParam(defaultValue = "50") int limit,
                                                    @RequestParam(defaultValue = "0") String cursor,
                                                    @RequestParam(required = false) String actor,
                                                    @RequestParam(required = false) String operation,
                                                    @RequestParam(required = false) String result,
                                                    @RequestParam(required = false) String traceId,
                                                    @RequestParam(required = false) String from,
                                                    @RequestParam(required = false) String to,
                                                    HttpServletRequest servletRequest) {
        verifyAdminToken(servletRequest, "audit-reader", "security.audit.query", "security-audit");
        try {
            return securityAuditLogService.queryRecent(limit, cursor, actor, operation, result, traceId, from, to);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private void verifyAdminToken(HttpServletRequest request,
                                  String actor,
                                  String operation,
                                  String resource) {
        String traceId = securityAuditLogService.resolveTraceId(request);
        if (adminToken.isBlank()) {
            return;
        }
        String provided = request == null ? "" : normalize(request.getHeader(adminTokenHeaderName), "");
        if (!adminToken.equals(provided)) {
            securityAuditLogService.record(traceId,
                    actor,
                    operation,
                    operation + "@" + normalize(resource, "*"),
                    "denied",
                    "invalid_admin_token",
                    request == null ? "" : normalize(request.getRemoteAddr(), ""),
                    request == null ? "" : normalize(request.getHeader("User-Agent"), ""));
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid admin token");
        }
    }

    private String normalize(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? fallback : normalized;
    }
}

