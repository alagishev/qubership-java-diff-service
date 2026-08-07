package org.qubership.jdiff.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.qubership.jdiff.jdeps.JdepsRunner;
import org.qubership.jdiff.model.DiffReport;
import org.qubership.jdiff.model.Gav;
import org.qubership.jdiff.pipeline.JarComparator;
import org.qubership.jdiff.pipeline.UpgradeImpactPipeline;
import org.qubership.jdiff.pipeline.UpgradeRequest;
import org.qubership.jdiff.resolve.ArtifactResolver;
import org.qubership.jdiff.resolve.EffectivePomBuilder;
import org.qubership.jdiff.resolve.ProjectScanner;
import org.qubership.jdiff.tools.ExternalToolRunner;
import org.qubership.jdiff.upgrade.UpgradeSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Analyzes the impact of upgrading one or more dependencies of a project.
 */
@Command(name = "upgrade", description = "Analyze the impact of a dependency upgrade.")
public class UpgradeCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(UpgradeCommand.class);

    @Option(names = "--project", description = "Path to the project (pom.xml or its directory) to analyze.")
    Path project;

    @Option(names = "--gav", description = "Coordinate g:a:v of a single artifact to analyze instead of a project.")
    String gav;

    @Option(names = "--upgrade", description = "Dependency upgrade in the form g:a=newVersion (repeatable).")
    List<String> upgrade = new ArrayList<>();

    @Option(names = "--upgrades-file", description = "File listing dependency upgrades, one g:a=newVersion per line.")
    Path upgradesFile;

    @Mixin
    OutputOptions outputOptions;

    @Override
    public Integer call() {
        try {
            if ((project == null) == (gav == null)) {
                throw new IllegalArgumentException("Specify exactly one of --project or --gav");
            }
            List<UpgradeSpec> upgrades = parseUpgrades();
            if (upgrades.isEmpty()) {
                throw new IllegalArgumentException(
                        "At least one upgrade is required, via --upgrade or --upgrades-file");
            }
            UpgradeRequest request = project != null
                    ? new UpgradeRequest(normalizeProjectDir(project), null, upgrades)
                    : new UpgradeRequest(null, Gav.parse(gav), upgrades);
            return run(request, outputOptions, System.out);
        } catch (IllegalArgumentException e) {
            LOG.error(e.getMessage());
            return 2;
        } catch (Exception e) {
            LOG.error(e.getMessage());
            LOG.debug("Unexpected error running upgrade", e);
            return 1;
        }
    }

    static Path normalizeProjectDir(Path project) {
        if (!Files.isRegularFile(project)) {
            return project;
        }
        Path parent = project.getParent();
        return parent != null ? parent : Path.of(".");
    }

    List<UpgradeSpec> parseUpgrades() {
        List<String> lines = new ArrayList<>(upgrade);
        if (upgradesFile != null) {
            try {
                for (String line : Files.readAllLines(upgradesFile)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    lines.add(trimmed);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read upgrades file " + upgradesFile, e);
            }
        }
        List<UpgradeSpec> specs = new ArrayList<>();
        for (String line : lines) {
            UpgradeSpec spec = UpgradeSpec.parse(line);
            if (!specs.contains(spec)) {
                specs.add(spec);
            }
        }
        return specs;
    }

    Integer run(UpgradeRequest request, OutputOptions options, PrintStream stdout) {
        return run(request, options, stdout, () -> buildPipeline(options).run(request));
    }

    Integer run(UpgradeRequest request, OutputOptions options, PrintStream stdout, Supplier<DiffReport> reportSupplier) {
        DiffReport report = reportSupplier.get();
        return CliSupport.writeReportAndCheckFormat(report, options, stdout);
    }

    private static UpgradeImpactPipeline buildPipeline(OutputOptions options) {
        ArtifactResolver resolver = CliSupport.artifactResolver(options);
        EffectivePomBuilder pomBuilder = new EffectivePomBuilder(resolver);
        ProjectScanner scanner = new ProjectScanner(pomBuilder);
        JdepsRunner jdeps = new JdepsRunner(new ExternalToolRunner());
        JarComparator comparator = CliSupport.japicmpComparator(options);
        return new UpgradeImpactPipeline(resolver, pomBuilder, scanner, jdeps, comparator, options.threads);
    }
}
