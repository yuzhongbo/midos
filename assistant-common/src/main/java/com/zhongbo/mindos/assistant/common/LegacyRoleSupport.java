package com.zhongbo.mindos.assistant.common;

import java.util.Locale;

public final class LegacyRoleSupport {

    public static final String DEFAULT_ROLE = "personal-assistant";

    private LegacyRoleSupport() {
    }

    public static String explicitRole(Object rawRole) {
        if (rawRole == null) {
            return null;
        }
        String role = String.valueOf(rawRole).trim();
        if (role.isBlank() || isDefaultRole(role)) {
            return null;
        }
        return role;
    }

    public static boolean isDefaultRole(Object rawRole) {
        if (rawRole == null) {
            return true;
        }
        String normalized = String.valueOf(rawRole).trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() || DEFAULT_ROLE.equals(normalized);
    }
}
