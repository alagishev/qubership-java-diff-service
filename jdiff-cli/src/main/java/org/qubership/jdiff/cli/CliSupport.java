package org.qubership.jdiff.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.qubership.jdiff.japicmp.JapicmpRunner;
import org.qubership.jdiff.model.DiffReport;
import org.qubership.jdiff.model.JsonSupport;
import org.qubership.jdiff.pipeline.JapicmpJarComparator;
import org.qubership.jdiff.render.ReportRenderer;
import org.qubership.jdiff.render.Renderers;
import org.qubership.jdiff.resolve.ArtifactResolver;
import org.qubership.jdiff.resolve.MavenArtifactResolver;
import org.qubership.jdiff.resolve.RepositoryConfig;
import org.qubership.jdiff.tools.ExternalToolRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared plumbing for the {@code api-report} and {@code api-diff} subcommands (and, later,
 * {@code upgrade}): repository/resolver setup, japicmp jar discovery, and the JSON output contract.
 */
public final class CliSupport {

    private static final Logger LOG = LoggerFactory.getLogger(CliSupport.class);

    private static final String JAPICMP_JAR_GLOB = "japicmp-*.jar";
    static final String JAPICMP_JAR_NOT_FOUND_MESSAGE =
            "japicmp jar not found; place japicmp-*.jar next to the jdiff binary or pass --japicmp-jar";
    private static final String REPORT_FILE_NAME = "report.json";

    private CliSupport() {
    }

    /**
     * @param options parsed output options
     * @return the repository configuration built from {@code --repo}/{@code --settings}
     */
    public static RepositoryConfig repositoryConfig(OutputOptions options) {
        return RepositoryConfig.of(options.repo, options.settings);
    }

    /**
     * @param options parsed output options
     * @return an artifact resolver backed by the repositories configured in {@code options}
     */
    public static ArtifactResolver artifactResolver(OutputOptions options) {
        return new MavenArtifactResolver(repositoryConfig(options));
    }

    /**
     * Builds a {@link JapicmpJarComparator} using the japicmp jar resolved from {@code options}
     * (see {@link #resolveJapicmpJarOrThrow(Path)}) and a fresh work directory for XML output.
     *
     * @param options parsed output options
     * @return the configured comparator
     * @throws IllegalArgumentException if no japicmp jar can be found
     */
    public static JapicmpJarComparator japicmpComparator(OutputOptions options) {
        Path japicmpJar = resolveJapicmpJarOrThrow(options.japicmpJar);
        JapicmpRunner runner = new JapicmpRunner(new ExternalToolRunner(), japicmpJar);
        return new JapicmpJarComparator(runner, createWorkDir());
    }

    /**
     * Resolves the japicmp jar to use: {@code explicit} if given; otherwise a scan of the directory
     * containing the running fat jar; falling back to {@code <cwd>/japicmp/} as a dev convenience.
     *
     * @param explicit the {@code --japicmp-jar} value, may be {@code null}
     * @return the resolved japicmp jar path
     * @throws IllegalArgumentException if no japicmp jar can be found
     */
    public static Path resolveJapicmpJarOrThrow(Path explicit) {
        return findJapicmpJar(explicit, fatJarDirectory(), devJapicmpDir())
                .orElseThrow(() -> new IllegalArgumentException(JAPICMP_JAR_NOT_FOUND_MESSAGE));
    }

    static Optional<Path> findJapicmpJar(Path explicit, Path scanDir, Path devDir) {
        if (explicit != null) {
            return Optional.of(explicit);
        }
        Optional<Path> found = scanForJapicmpJar(scanDir);
        return found.isPresent() ? found : scanForJapicmpJar(devDir);
    }

    static Optional<Path> scanForJapicmpJar(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, JAPICMP_JAR_GLOB)) {
            Iterator<Path> it = stream.iterator();
            return it.hasNext() ? Optional.of(it.next()) : Optional.empty();
        } catch (IOException e) {
            LOG.debug("Failed to scan {} for a japicmp jar: {}", dir, e.getMessage());
            return Optional.empty();
        }
    }

    static Path fatJarDirectory() {
        try {
            URL location = JdiffMain.class.getProtectionDomain().getCodeSource().getLocation();
            Path path = Path.of(location.toURI());
            if (Files.isRegularFile(path)) {
                return path.getParent();
            }
        } catch (URISyntaxException | RuntimeException e) {
            LOG.debug("Failed to determine the running jar's location: {}", e.getMessage());
        }
        return Path.of("").toAbsolutePath();
    }

    static Path devJapicmpDir() {
        return Path.of("japicmp");
    }

    /**
     * @return a fresh temp directory to hold intermediate japicmp XML reports, deleted best-effort on exit
     */
    public static Path createWorkDir() {
        try {
            Path dir = Files.createTempDirectory("jdiff");
            dir.toFile().deleteOnExit();
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create a temporary work directory", e);
        }
    }

    /**
     * Always writes {@code report.json} under {@code options.outputDir}. If {@code --format} is exactly
     * {@code [json]}, also prints the JSON document to {@code stdout}. For every other requested format
     * ({@code html}, {@code csv}, {@code xlsx}), renders the report via {@link Renderers#forFormat(String)}
     * into {@code options.outputDir}. If {@code --format} contains an unknown format string, logs an error
     * listing the supported formats and returns exit code 2 (after still writing {@code report.json}).
     *
     * @param report  the report to write
     * @param options parsed output options
     * @param stdout  where to print the JSON document when {@code --format} is exactly {@code [json]}
     * @return 0 on success, 2 if an unsupported format was requested
     */
    public static int writeReportAndCheckFormat(DiffReport report, OutputOptions options, PrintStream stdout) {
        String json = JsonSupport.toJson(report);
        try {
            Files.createDirectories(options.outputDir);
            Path jsonFile = options.outputDir.resolve(REPORT_FILE_NAME);
            Files.writeString(jsonFile, json);
            LOG.info("Wrote {}", jsonFile.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + REPORT_FILE_NAME + " to " + options.outputDir, e);
        }
        if (options.format.equals(Set.of("json"))) {
            stdout.println(json);
        }

        Set<String> supportedFormats = supportedFormats();
        Set<String> unknownFormats = new LinkedHashSet<>(options.format);
        unknownFormats.removeAll(supportedFormats);
        if (!unknownFormats.isEmpty()) {
            LOG.error("Unsupported format(s): {} (supported: {})", unknownFormats, supportedFormats);
            return 2;
        }

        for (String format : options.format) {
            if ("json".equals(format)) {
                continue;
            }
            ReportRenderer renderer = Renderers.forFormat(format);
            Path outputFile = options.outputDir.resolve(renderer.fileName());
            renderer.render(report, outputFile);
            LOG.info("Wrote {}", outputFile.toAbsolutePath());
        }
        return 0;
    }

    /**
     * @return the full set of format identifiers accepted by {@code --format}: {@code json} plus every
     *         format returned by {@link Renderers#supportedFormats()}
     */
    private static Set<String> supportedFormats() {
        Set<String> formats = new TreeSet<>(Renderers.supportedFormats());
        formats.add("json");
        return formats;
    }
}
