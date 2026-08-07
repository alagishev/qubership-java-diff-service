package org.qubership.jdiff.cli;

import java.io.PrintStream;
import java.util.function.Supplier;
import org.qubership.jdiff.model.DiffReport;
import org.qubership.jdiff.model.Gav;
import org.qubership.jdiff.pipeline.ApiReportPipeline;
import org.qubership.jdiff.pipeline.JarComparator;
import org.qubership.jdiff.resolve.ArtifactResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Generates an API documentation report for a single artifact.
 */
@Command(name = "api-report", description = "Generate an API documentation report for an artifact.")
public class ApiReportCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(ApiReportCommand.class);

    @Option(names = "--gav", required = true, description = "Coordinate g:a:v of the artifact to report on.")
    String gav;

    @Mixin
    OutputOptions outputOptions;

    @Override
    public Integer call() {
        try {
            Gav parsedGav = Gav.parse(gav);
            return run(parsedGav, outputOptions, System.out);
        } catch (IllegalArgumentException e) {
            LOG.error(e.getMessage());
            return 2;
        } catch (Exception e) {
            LOG.error(e.getMessage());
            LOG.debug("Unexpected error running api-report", e);
            return 1;
        }
    }

    Integer run(Gav parsedGav, OutputOptions options, PrintStream stdout) {
        return run(parsedGav, options, stdout, () -> buildPipeline(options).run(parsedGav));
    }

    Integer run(Gav parsedGav, OutputOptions options, PrintStream stdout, Supplier<DiffReport> reportSupplier) {
        DiffReport report = reportSupplier.get();
        return CliSupport.writeReportAndCheckFormat(report, options, stdout);
    }

    private static ApiReportPipeline buildPipeline(OutputOptions options) {
        ArtifactResolver resolver = CliSupport.artifactResolver(options);
        JarComparator comparator = CliSupport.japicmpComparator(options);
        return new ApiReportPipeline(resolver, comparator);
    }
}
