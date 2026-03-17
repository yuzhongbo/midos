package com.zhongbo.mindos.assistant.cli;

import picocli.CommandLine;

@CommandLine.Command(
        name = "memory",
        mixinStandardHelpOptions = true,
        description = "Synchronize memory with MindOS server",
        subcommands = {
                MemoryPullCommand.class,
                MemoryPushCommand.class,
                MemoryStyleCommand.class,
                MemoryCompressCommand.class
        }
)
public class MemoryCommand implements Runnable {

    @Override
    public void run() {
        System.out.println("Use 'mindos memory pull|push|style|compress' to manage memory.");
    }
}

