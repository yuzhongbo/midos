package com.zhongbo.mindos.assistant.cli;

import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(name = "init", description = "Initialize personal assistant profile")
public class InitCommand implements Runnable {

    @CommandLine.Option(names = {"--name"}, required = true, description = "Assistant display name")
    private String name;

    @CommandLine.Option(names = {"--role"}, defaultValue = "personal-assistant", description = "Assistant role")
    private String role;

    @CommandLine.Option(names = {"--style"}, defaultValue = "concise", description = "Preferred response style")
    private String style;

    @CommandLine.Option(names = {"--language"}, defaultValue = "zh-CN", description = "Preferred language (BCP-47)")
    private String language;

    @CommandLine.Option(names = {"--timezone"}, defaultValue = "Asia/Shanghai", description = "Preferred timezone")
    private String timezone;

    @CommandLine.Option(names = {"--llm-provider"}, defaultValue = "", description = "Preferred LLM provider (optional)")
    private String llmProvider;

    @CommandLine.Option(names = {"--llm-preset"}, defaultValue = "", description = "Preferred LLM routing preset (optional)")
    private String llmPreset;

    @CommandLine.Option(names = {"--config"},
            defaultValue = "${sys:user.home}/.mindos/profile.properties",
            description = "Profile config file path")
    private Path configPath;

    private final AssistantProfileStore profileStore = new AssistantProfileStore();

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        role = withDefault(role, AssistantProfileStore.DEFAULT_ROLE);
        style = withDefault(style, AssistantProfileStore.DEFAULT_STYLE);
        language = withDefault(language, AssistantProfileStore.DEFAULT_LANGUAGE);
        timezone = withDefault(timezone, AssistantProfileStore.DEFAULT_TIMEZONE);
        llmProvider = withDefault(llmProvider, AssistantProfileStore.DEFAULT_LLM_PROVIDER);
        llmPreset = withDefault(llmPreset, AssistantProfileStore.DEFAULT_LLM_PRESET);

        validateInput();

        AssistantProfile profile = new AssistantProfile(
                name.trim(),
                role.trim(),
                style.trim(),
                language.trim(),
                timezone.trim(),
                llmProvider.trim(),
                llmPreset.trim()
        );
        profileStore.save(configPath, profile);
        System.out.println("Profile initialized at: " + configPath);
        System.out.println("assistant=" + profile.assistantName() + ", role=" + profile.role());
    }

    private void validateInput() {
        ProfileInputValidator.requireNotBlank(name, "name", spec.commandLine());
        ProfileInputValidator.requireNotBlank(role, "role", spec.commandLine());
        ProfileInputValidator.requireNotBlank(style, "style", spec.commandLine());
        ProfileInputValidator.validateLanguage(language.trim(), spec.commandLine());
        ProfileInputValidator.validateTimezone(timezone.trim(), spec.commandLine());
    }

    private String withDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}

