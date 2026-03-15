package com.zhongbo.mindos.assistant.cli;

import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(name = "reset", description = "Reset assistant profile to default values")
public class ProfileResetCommand implements Runnable {

    @CommandLine.Option(names = {"--config"},
            defaultValue = "${sys:user.home}/.mindos/profile.properties",
            description = "Profile config file path")
    private Path configPath;

    private final AssistantProfileStore profileStore = new AssistantProfileStore();

    @Override
    public void run() {
        AssistantProfile profile = profileStore.defaultProfile();
        profileStore.save(configPath, profile);
        System.out.println("Profile reset at: " + configPath);
        System.out.println("assistant=" + profile.assistantName() + ", role=" + profile.role());
    }
}

