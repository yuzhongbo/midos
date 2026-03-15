package com.zhongbo.mindos.assistant.cli;

import picocli.CommandLine;

import java.time.ZoneId;
import java.util.Locale;

final class ProfileInputValidator {

    private ProfileInputValidator() {
    }

    static void requireNotBlank(String value, String fieldName, CommandLine commandLine) {
        try {
            requireNotBlankValue(value, fieldName);
        } catch (IllegalArgumentException ex) {
            throw new CommandLine.ParameterException(commandLine, ex.getMessage());
        }
    }

    static void validateLanguage(String language, CommandLine commandLine) {
        try {
            validateLanguageValue(language);
        } catch (IllegalArgumentException ex) {
            throw new CommandLine.ParameterException(commandLine, ex.getMessage());
        }
    }

    static void validateTimezone(String timezone, CommandLine commandLine) {
        try {
            validateTimezoneValue(timezone);
        } catch (IllegalArgumentException ex) {
            throw new CommandLine.ParameterException(commandLine, ex.getMessage());
        }
    }

    static void requireNotBlankValue(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
    }

    static void validateLanguageValue(String language) {
        Locale locale = Locale.forLanguageTag(language);
        if (locale.getLanguage() == null || locale.getLanguage().isBlank()) {
            throw new IllegalArgumentException("Invalid language tag: " + language);
        }
    }

    static void validateTimezoneValue(String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid timezone: " + timezone);
        }
    }
}

