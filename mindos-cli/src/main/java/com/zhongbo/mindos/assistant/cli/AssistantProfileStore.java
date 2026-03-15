package com.zhongbo.mindos.assistant.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class AssistantProfileStore {

    public static final String DEFAULT_ASSISTANT_NAME = "MindOS Assistant";
    public static final String DEFAULT_ROLE = "personal-assistant";
    public static final String DEFAULT_STYLE = "concise";
    public static final String DEFAULT_LANGUAGE = "zh-CN";
    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    public static final String DEFAULT_LLM_PROVIDER = "";

    public void save(Path configPath, AssistantProfile profile) {
        Properties properties = new Properties();
        properties.setProperty("assistant.name", profile.assistantName());
        properties.setProperty("assistant.role", profile.role());
        properties.setProperty("assistant.style", profile.style());
        properties.setProperty("assistant.language", profile.language());
        properties.setProperty("assistant.timezone", profile.timezone());
        properties.setProperty("assistant.llm.provider", profile.llmProvider());

        try {
            Path parent = configPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(configPath)) {
                properties.store(out, "MindOS assistant profile");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save profile to " + configPath, e);
        }
    }

    public AssistantProfile load(Path configPath) {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(configPath)) {
            properties.load(in);
            return new AssistantProfile(
                    properties.getProperty("assistant.name", ""),
                    properties.getProperty("assistant.role", ""),
                    properties.getProperty("assistant.style", ""),
                    properties.getProperty("assistant.language", ""),
                    properties.getProperty("assistant.timezone", ""),
                    properties.getProperty("assistant.llm.provider", "")
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load profile from " + configPath, e);
        }
    }

    public AssistantProfile loadOrDefault(Path configPath) {
        if (!Files.exists(configPath)) {
            return defaultProfile();
        }
        return load(configPath);
    }

    public AssistantProfile defaultProfile() {
        return new AssistantProfile(
                DEFAULT_ASSISTANT_NAME,
                DEFAULT_ROLE,
                DEFAULT_STYLE,
                DEFAULT_LANGUAGE,
                DEFAULT_TIMEZONE,
                DEFAULT_LLM_PROVIDER
        );
    }
}

