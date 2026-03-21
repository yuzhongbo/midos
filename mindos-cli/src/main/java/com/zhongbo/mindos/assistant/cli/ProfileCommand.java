package com.zhongbo.mindos.assistant.cli;

import picocli.CommandLine;

@CommandLine.Command(
        name = "profile",
        mixinStandardHelpOptions = true,
        description = "Manage assistant profile",
        subcommands = {
                ProfileShowCommand.class,
                ProfileSetCommand.class,
                ProfileResetCommand.class,
                ProfilePersonaCommand.class
        }
)
public class ProfileCommand implements Runnable {

    @Override
    public void run() {
        System.out.println("Use 'mindos profile show|set|reset|persona show' to manage profile settings.");
    }
}

