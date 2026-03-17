package com.zhongbo.mindos.assistant.cli;

import picocli.CommandLine;

@CommandLine.Command(
        name = "style",
        description = "Show or update the memory compression style profile",
        subcommands = {
                MemoryStyleShowCommand.class,
                MemoryStyleSetCommand.class
        }
)
public class MemoryStyleCommand implements Runnable {

    @Override
    public void run() {
        System.out.println("Use 'mindos memory style show|set' to manage memory style profile.");
    }
}

