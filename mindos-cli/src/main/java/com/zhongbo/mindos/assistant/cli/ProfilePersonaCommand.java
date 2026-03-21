package com.zhongbo.mindos.assistant.cli;

import picocli.CommandLine;

@CommandLine.Command(
        name = "persona",
        description = "Show learned long-term persona profile from the server",
        subcommands = {
                ProfilePersonaShowCommand.class,
                ProfilePersonaExplainCommand.class
        }
)
public class ProfilePersonaCommand implements Runnable {

    @Override
    public void run() {
        System.out.println("Use 'mindos profile persona show|explain' to inspect learned persona details.");
    }
}

