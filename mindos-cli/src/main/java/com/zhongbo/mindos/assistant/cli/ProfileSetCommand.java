package com.zhongbo.mindos.assistant.cli;

import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(name = "set", description = "Update one or more assistant profile fields")
public class ProfileSetCommand implements Runnable {

    @CommandLine.Option(names = {"--name"}, description = "Assistant display name")
    private String name;

    @CommandLine.Option(names = {"--role"}, description = "Assistant role")
    private String role;

    @CommandLine.Option(names = {"--style"}, description = "Preferred response style")
    private String style;

    @CommandLine.Option(names = {"--language"}, description = "Preferred language (BCP-47)")
    private String language;

    @CommandLine.Option(names = {"--timezone"}, description = "Preferred timezone")
    private String timezone;

    @CommandLine.Option(names = {"--llm-provider"}, description = "Preferred LLM provider")
    private String llmProvider;

    @CommandLine.Option(names = {"--llm-preset"}, description = "Preferred LLM routing preset")
    private String llmPreset;

    @CommandLine.Option(names = {"--config"},
            defaultValue = "${sys:user.home}/.mindos/profile.properties",
            description = "Profile config file path")
    private Path configPath;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    private final AssistantProfileStore profileStore = new AssistantProfileStore();

    @Override
    public void run() {
        if (name == null && role == null && style == null && language == null && timezone == null
                && llmProvider == null && llmPreset == null) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Provide at least one field to update");
        }

        AssistantProfile current = profileStore.loadOrDefault(configPath);
        String nextName = choose(name, current.assistantName());
        String nextRole = choose(role, current.role());
        String nextStyle = choose(style, current.style());
        String nextLanguage = choose(language, current.language());
        String nextTimezone = choose(timezone, current.timezone());
        String nextLlmProvider = choose(llmProvider, current.llmProvider());
        String nextLlmPreset = choose(llmPreset, current.llmPreset());

        ProfileInputValidator.requireNotBlank(nextName, "name", spec.commandLine());
        ProfileInputValidator.requireNotBlank(nextRole, "role", spec.commandLine());
        ProfileInputValidator.requireNotBlank(nextStyle, "style", spec.commandLine());
        ProfileInputValidator.validateLanguage(nextLanguage, spec.commandLine());
        ProfileInputValidator.validateTimezone(nextTimezone, spec.commandLine());

        AssistantProfile updated = new AssistantProfile(nextName, nextRole, nextStyle, nextLanguage, nextTimezone, nextLlmProvider, nextLlmPreset);
        profileStore.save(configPath, updated);
        System.out.println("Profile updated at: " + configPath);
        System.out.println("assistant=" + updated.assistantName() + ", role=" + updated.role());
    }

    private String choose(String candidate, String fallback) {
        if (candidate == null) {
            return fallback;
        }
        return candidate.trim();
    }
}

