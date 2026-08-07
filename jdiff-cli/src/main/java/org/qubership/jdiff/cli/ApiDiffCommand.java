package org.qubership.jdiff.cli;

import java.io.PrintStream;
import java.util.function.Supplier;
import org.qubership.jdiff.model.DiffReport;
import org.qubership.jdiff.pipeline.ApiDiffPipeline;
import org.qubership.jdiff.pipeline.JarComparator;
import org.qubership.jdiff.resolve.ArtifactResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Computes the API diff of an artifact between two versions.
 */
@Command(name = "api-diff", description = "Compute the API diff of an artifact between two versions.")
public class ApiDiffCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(ApiDiffCommand.class);

    @Option(names = "--gav", required = true, description = "Coordinate g:a of the artifact to diff.")
    String gav;

    @Option(names = "--old", required = true, description = "Old version to diff from.")
    String oldVersion;

    @Option(names = "--new", required = true, description = "New version to diff to.")
    String newVersion;

    @Mixin
    OutputOptions outputOptions;

    @Override
    public Integer call() {
        try {
            String[] ga = parseGa(gav);
            return run(ga[0], ga[1], oldVersion, newVersion, outputOptions, System.out);
        } catch (IllegalArgumentException e) {
            LOG.error(e.getMessage());
            return 2;
        } catch (Exception e) {
            LOG.error(e.getMessage());
            LOG.debug("Unexpected error running api-diff", e);
            return 1;
        }
    }

    private static String[] parseGa(String gav) {
        if (gav == null || gav.isBlank()) {
            throw new IllegalArgumentException("GAV string must not be blank: " + gav);
        }
        String[] parts = gav.split(":", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid GAV '" + gav + "': expected 'groupId:artifactId', got " + parts.length + " segment(s)");
        }
        return parts;
    }

    Integer run(String groupId, String artifactId, String oldVersion, String newVersion, OutputOptions options,
            PrintStream stdout) {
        return run(groupId, artifactId, oldVersion, newVersion, options, stdout,
                () -> buildPipeline(options).run(groupId, artifactId, oldVersion, newVersion));
    }

    Integer run(String groupId, String artifactId, String oldVersion, String newVersion, OutputOptions options,
            PrintStream stdout, Supplier<DiffReport> reportSupplier) {
        DiffReport report = reportSupplier.get();
        return CliSupport.writeReportAndCheckFormat(report, options, stdout);
    }

    private static ApiDiffPipeline buildPipeline(OutputOptions options) {
        ArtifactResolver resolver = CliSupport.artifactResolver(options);
        JarComparator comparator = CliSupport.japicmpComparator(options);
        return new ApiDiffPipeline(resolver, comparator);
    }
}
