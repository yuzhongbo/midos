package com.zhongbo.mindos.assistant.cli;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Centralized URL policy for commands that may fetch remote resources or switch backend server.
 */
final class UrlSecurityPolicy {

    private UrlSecurityPolicy() {
    }

    static String requireAllowedSensitiveUrl(String rawUrl, String fieldName) {
        String normalized = normalizeSecureHttpUrl(rawUrl);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " URL 不符合安全策略：仅允许 https，或本地/内网 http，且禁止凭据与 fragment。");
        }
        return normalized;
    }

    static String normalizeSecureHttpUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(rawUrl.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            if (uri.getRawUserInfo() != null) {
                return null;
            }
            if (uri.getRawFragment() != null) {
                return null;
            }
            if ("http".equalsIgnoreCase(scheme) && !isLocalOrPrivateHost(uri.getHost())) {
                return null;
            }
            return uri.toASCIIString();
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    private static boolean isLocalOrPrivateHost(String host) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalizedHost)) {
            return true;
        }
        if (isPrivateIpv4(normalizedHost)) {
            return true;
        }
        return isPrivateIpv6(normalizedHost);
    }

    private static boolean isPrivateIpv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3 || !parts[i].chars().allMatch(Character::isDigit)) {
                return false;
            }
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] < 0 || octets[i] > 255) {
                return false;
            }
        }
        return octets[0] == 10
                || octets[0] == 127
                || (octets[0] == 192 && octets[1] == 168)
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 169 && octets[1] == 254);
    }

    private static boolean isPrivateIpv6(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if ("::1".equals(normalized)) {
            return true;
        }
        return normalized.startsWith("fc")
                || normalized.startsWith("fd")
                || normalized.startsWith("fe80:");
    }
}

