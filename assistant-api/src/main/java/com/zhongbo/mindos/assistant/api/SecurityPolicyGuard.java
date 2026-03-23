package com.zhongbo.mindos.assistant.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SecurityPolicyGuard {

    private final boolean riskyOpsRequireApproval;
    private final boolean useChallengeToken;
    private final String approvalHeaderName;
    private final String approvalExpectedValue;
    private final String challengeHeaderName;
    private final String adminTokenHeaderName;
    private final String adminToken;
    private final Set<String> loadJarAllowedHosts;
    private final Set<String> loadMcpAllowedHosts;
    private final SecurityChallengeService securityChallengeService;
    private final SecurityAuditLogService securityAuditLogService;

    public SecurityPolicyGuard(
            @Value("${mindos.security.risky-ops.require-approval:false}") boolean riskyOpsRequireApproval,
            @Value("${mindos.security.risky-ops.use-challenge-token:true}") boolean useChallengeToken,
            @Value("${mindos.security.risky-ops.approval-header:X-MindOS-Approve}") String approvalHeaderName,
            @Value("${mindos.security.risky-ops.approval-value:YES}") String approvalExpectedValue,
            @Value("${mindos.security.risky-ops.challenge-header:X-MindOS-Challenge-Token}") String challengeHeaderName,
            @Value("${mindos.security.risky-ops.admin-token-header:X-MindOS-Admin-Token}") String adminTokenHeaderName,
            @Value("${mindos.security.risky-ops.admin-token:}") String adminToken,
            @Value("${mindos.security.skill.load-jar.allowed-hosts:localhost,127.0.0.1}") String loadJarAllowedHosts,
            @Value("${mindos.security.skill.load-mcp.allowed-hosts:localhost,127.0.0.1}") String loadMcpAllowedHosts,
            SecurityChallengeService securityChallengeService,
            SecurityAuditLogService securityAuditLogService) {
        this.riskyOpsRequireApproval = riskyOpsRequireApproval;
        this.useChallengeToken = useChallengeToken;
        this.approvalHeaderName = normalizeText(approvalHeaderName, "X-MindOS-Approve");
        this.approvalExpectedValue = normalizeText(approvalExpectedValue, "YES");
        this.challengeHeaderName = normalizeText(challengeHeaderName, "X-MindOS-Challenge-Token");
        this.adminTokenHeaderName = normalizeText(adminTokenHeaderName, "X-MindOS-Admin-Token");
        this.adminToken = adminToken == null ? "" : adminToken.trim();
        this.loadJarAllowedHosts = parseHostList(loadJarAllowedHosts);
        this.loadMcpAllowedHosts = parseHostList(loadMcpAllowedHosts);
        this.securityChallengeService = securityChallengeService;
        this.securityAuditLogService = securityAuditLogService;
    }

    public void verifyRiskyOperationApproval(HttpServletRequest request,
                                             String operationName,
                                             String resource,
                                             String actor) {
        if (!riskyOpsRequireApproval) {
            return;
        }
        if (request == null) {
            throw forbidden("Request missing for risky operation: " + operationName);
        }
        String remoteAddress = normalizeText(request.getRemoteAddr(), "");
        String userAgent = normalizeText(request.getHeader("User-Agent"), "");
        String normalizedActor = normalizeText(actor, "unknown");
        String traceId = securityAuditLogService.resolveTraceId(request);

        if (useChallengeToken) {
            String token = normalizeText(request.getHeader(challengeHeaderName), "");
            boolean approved = securityChallengeService.consume(token, operationName, resource, normalizedActor, remoteAddress);
            if (!approved) {
                securityAuditLogService.record(traceId, normalizedActor, operationName, resource, "denied",
                        "invalid_or_replayed_challenge", remoteAddress, userAgent);
                throw forbidden("Invalid or expired challenge token for risky operation: " + operationName);
            }
            securityAuditLogService.record(traceId, normalizedActor, operationName, resource, "allowed",
                    "challenge_token", remoteAddress, userAgent);
            return;
        }

        String approvalValue = normalizeText(request.getHeader(approvalHeaderName), "");
        if (!approvalExpectedValue.equalsIgnoreCase(approvalValue)) {
            securityAuditLogService.record(traceId, normalizedActor, operationName, resource, "denied",
                    "missing_approval_header", remoteAddress, userAgent);
            throw forbidden("Missing approval header for risky operation: " + operationName);
        }
        if (!adminToken.isBlank()) {
            String requestToken = normalizeText(request.getHeader(adminTokenHeaderName), "");
            if (!adminToken.equals(requestToken)) {
                securityAuditLogService.record(traceId, normalizedActor, operationName, resource, "denied",
                        "invalid_admin_token", remoteAddress, userAgent);
                throw forbidden("Invalid admin token for risky operation: " + operationName);
            }
        }
        securityAuditLogService.record(traceId, normalizedActor, operationName, resource, "allowed",
                "approval_header", remoteAddress, userAgent);
    }

    public void verifyExternalSkillUrl(String rawUrl, boolean loadJar) {
        URI uri;
        try {
            uri = URI.create(rawUrl == null ? "" : rawUrl.trim());
        } catch (Exception ex) {
            securityAuditLogService.record("system", loadJar ? "skills.load-jar" : "skills.load-mcp", rawUrl,
                    "denied", "invalid_url", "", "");
            throw forbidden("Invalid URL for external skill loading");
        }
        String scheme = normalizeText(uri.getScheme(), "").toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            securityAuditLogService.record("system", loadJar ? "skills.load-jar" : "skills.load-mcp", rawUrl,
                    "denied", "invalid_scheme", "", "");
            throw forbidden("Only http/https URLs are allowed for external skill loading");
        }
        String host = normalizeText(uri.getHost(), "").toLowerCase(Locale.ROOT);
        if (host.isBlank()) {
            securityAuditLogService.record("system", loadJar ? "skills.load-jar" : "skills.load-mcp", rawUrl,
                    "denied", "missing_host", "", "");
            throw forbidden("URL host is required for external skill loading");
        }
        Set<String> allowedHosts = loadJar ? loadJarAllowedHosts : loadMcpAllowedHosts;
        if (!allowedHosts.isEmpty() && !allowedHosts.contains(host)) {
            securityAuditLogService.record("system", loadJar ? "skills.load-jar" : "skills.load-mcp", rawUrl,
                    "denied", "host_not_allowlisted", "", "");
            throw forbidden("Host is not allowed: " + host);
        }
        securityAuditLogService.record("system", loadJar ? "skills.load-jar" : "skills.load-mcp", rawUrl,
                "allowed", "url_allowlisted", "", "");
    }

    private Set<String> parseHostList(String rawHosts) {
        if (rawHosts == null || rawHosts.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(rawHosts.split(","))
                .map(item -> normalizeText(item, "").toLowerCase(Locale.ROOT))
                .filter(item -> !item.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String normalizeText(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? fallback : normalized;
    }

    private ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}

