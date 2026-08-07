package org.qubership.jdiff.cli;

import ch.qos.logback.classic.Level;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IExecutionStrategy;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.ScopeType;

import java.util.concurrent.Callable;

/**
 * Entry point for the {@code jdiff} CLI: analyzes the impact of upgrading java dependencies,
 * generates API documentation and API diff reports, and can run as a local MCP server.
 */
@Command(name = "jdiff",
        mixinStandardHelpOptions = true,
        version = "jdiff 0.1.0-SNAPSHOT",
        description = "Analyze the impact of java dependency upgrades, generate API reports and diffs.",
        subcommands = {
                UpgradeCommand.class,
                ApiReportCommand.class,
                ApiDiffCommand.class,
                McpServerCommand.class
        })
public class JdiffMain implements Callable<Integer> {

    @Option(names = "--log-level", scope = ScopeType.INHERIT,
            description = "Log verbosity: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
    LogLevel logLevel = LogLevel.INFO;

    private CommandLine.Model.CommandSpec spec;

    @CommandLine.Spec
    void setSpec(CommandLine.Model.CommandSpec spec) {
        this.spec = spec;
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 0;
    }

    /**
     * Programmatically sets the Logback root logger level to match {@code logLevel}.
     *
     * @param logLevel the requested verbosity
     */
    static void applyLogLevel(LogLevel logLevel) {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(toLogbackLevel(logLevel));
    }

    private static Level toLogbackLevel(LogLevel logLevel) {
        return switch (logLevel) {
            case INFO -> Level.INFO;
            case DEBUG -> Level.DEBUG;
            case TRACE -> Level.TRACE;
            case NOLOGS -> Level.OFF;
        };
    }

    public static void main(String[] args) {
        JdiffMain main = new JdiffMain();
        CommandLine commandLine = new CommandLine(main);
        IExecutionStrategy defaultStrategy = new CommandLine.RunLast();
        commandLine.setExecutionStrategy((ParseResult parseResult) -> {
            applyLogLevel(main.logLevel);
            return defaultStrategy.execute(parseResult);
        });
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }
}
