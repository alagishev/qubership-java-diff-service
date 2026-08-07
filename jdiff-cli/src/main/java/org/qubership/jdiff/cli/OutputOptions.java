package org.qubership.jdiff.cli;

import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared output-related options for the {@code upgrade}, {@code api-report} and {@code api-diff}
 * subcommands (not used by {@code mcp-server}).
 */
public class OutputOptions {

    @Option(names = "--output-dir", description = "Directory to write reports to (default: ${DEFAULT-VALUE}).")
    Path outputDir = Path.of("jdiff-out");

    @Option(names = "--format", split = ",",
            description = "Comma-separated report formats: json,html,csv,xlsx (default: ${DEFAULT-VALUE}).")
    Set<String> format = new LinkedHashSet<>(List.of("json"));

    @Option(names = "--repo", description = "Maven repository as URL or id=url (repeatable). Default: Maven Central. "
            + "Ids must match <server><id> in --settings.")
    List<String> repo = new ArrayList<>();

    @Option(names = "--settings", description = "Path to a Maven settings.xml.")
    Path settings;

    @Option(names = "--japicmp-jar", description = "Path to a japicmp jar to use instead of the bundled one.")
    Path japicmpJar;

    @Option(names = "--threads", description = "Number of worker threads (default: ${DEFAULT-VALUE}).")
    int threads = Runtime.getRuntime().availableProcessors();
}
