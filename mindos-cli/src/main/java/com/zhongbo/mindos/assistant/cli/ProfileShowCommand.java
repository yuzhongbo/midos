package com.zhongbo.mindos.assistant.cli;

import com.zhongbo.mindos.assistant.common.LegacyRoleSupport;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(name = "show", description = "Show assistant profile")
public class ProfileShowCommand implements Runnable {

    @CommandLine.Option(names = {"--config"},
            defaultValue = "${sys:user.home}/.mindos/profile.properties",
            description = "Profile config file path")
    private Path configPath;

    private final AssistantProfileStore profileStore = new AssistantProfileStore();

    @Override
    public void run() {
        AssistantProfile profile = profileStore.load(configPath);
        System.out.println("assistant=" + profile.assistantName());
        String legacyRole = LegacyRoleSupport.explicitRole(profile.role());
        if (legacyRole != null) {
            System.out.println("legacy.role=" + legacyRole);
        }
        System.out.println("style=" + profile.style());
        System.out.println("language=" + profile.language());
        System.out.println("timezone=" + profile.timezone());
        System.out.println("llm.provider=" + profile.llmProvider());
        System.out.println("llm.preset=" + profile.llmPreset());
    }
}
