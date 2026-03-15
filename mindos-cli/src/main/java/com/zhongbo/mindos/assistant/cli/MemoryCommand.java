package com.zhongbo.mindos.assistant.cli;

import picocli.CommandLine;

@CommandLine.Command(
        name = "memory",
        mixinStandardHelpOptions = true,
        description = "Synchronize memory with MindOS server",
        subcommands = {
                MemoryPullCommand.class,
                MemoryPushCommand.class
        }
)
public class MemoryCommand implements Runnable {

    @Override
    public void run() {
        System.out.println("Use 'mindos memory pull|push' to synchronize memory.");
    }
}

