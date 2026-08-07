package org.qubership.jdiff.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.jdiff.model.ArtifactReport;
import org.qubership.jdiff.model.DiffReport;
import org.qubership.jdiff.model.JsonSupport;
import org.qubership.jdiff.model.ReportMode;

class CliSupportTest {

    @Test
    void findsAJapicmpJarInTheScanDirectory(@TempDir Path dir) throws Exception {
        Path japicmpJar = Files.createFile(dir.resolve("japicmp-1.2.3.jar"));

        Optional<Path> found = CliSupport.findJapicmpJar(null, dir, dir.resolve("does-not-exist"));

        assertThat(found).contains(japicmpJar);
    }

    @Test
    void ignoresJarsThatDoNotMatchTheJapicmpNamingPattern(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("some-other-library.jar"));

        Optional<Path> found = CliSupport.findJapicmpJar(null, dir, dir.resolve("does-not-exist"));

        assertThat(found).isEmpty();
    }

    @Test
    void explicitJapicmpJarWinsOverScanning(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("japicmp-1.2.3.jar"));
        Path explicit = Path.of("some/explicit/japicmp-9.9.9.jar");

        Optional<Path> found = CliSupport.findJapicmpJar(explicit, dir, dir);

        assertThat(found).contains(explicit);
    }

    @Test
    void emptyDirectoryYieldsEmptyOptional(@TempDir Path dir) {
        Optional<Path> found = CliSupport.findJapicmpJar(null, dir, dir.resolve("does-not-exist"));

        assertThat(found).isEmpty();
    }

    @Test
    void resolveJapicmpJarOrThrowFailsWithAnActionableMessageWhenNoJarIsFound() {
        assertThatThrownBy(() -> CliSupport.resolveJapicmpJarOrThrow(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("japicmp jar not found; place japicmp-*.jar next to the jdiff binary or pass "
                        + "--japicmp-jar");
    }

    @Test
    void fallsBackToTheDevDirectoryWhenTheScanDirectoryHasNoMatch(@TempDir Path dir) throws Exception {
        Path scanDir = Files.createDirectory(dir.resolve("scan"));
        Path devDir = Files.createDirectory(dir.resolve("dev"));
        Path japicmpJar = Files.createFile(devDir.resolve("japicmp-0.26.1-jar-with-dependencies.jar"));

        Optional<Path> found = CliSupport.findJapicmpJar(null, scanDir, devDir);

        assertThat(found).contains(japicmpJar);
    }

    @Test
    void writeReportAndCheckFormatAlwaysWritesReportJson(@TempDir Path dir) throws Exception {
        OutputOptions options = new OutputOptions();
        options.outputDir = dir.resolve("out");
        DiffReport report = sampleReport();

        int exitCode = CliSupport.writeReportAndCheckFormat(report, options,
                new PrintStream(new java.io.ByteArrayOutputStream()));

        assertThat(exitCode).isZero();
        Path reportFile = options.outputDir.resolve("report.json");
        assertThat(reportFile).isRegularFile();
        DiffReport parsed = JsonSupport.fromJson(Files.readString(reportFile), DiffReport.class);
        assertThat(parsed.mode()).isEqualTo(ReportMode.API_REPORT);
    }

    @Test
    void writeReportAndCheckFormatPrintsJsonToStdoutOnlyWhenFormatIsExactlyJson(@TempDir Path dir) throws Exception {
        OutputOptions options = new OutputOptions();
        options.outputDir = dir.resolve("out");
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();

        int exitCode = CliSupport.writeReportAndCheckFormat(sampleReport(), options, new PrintStream(captured));

        assertThat(exitCode).isZero();
        assertThat(captured.toString()).contains("\"mode\"");
    }

    @Test
    void writeReportAndCheckFormatReturnsTwoForUnknownFormats(@TempDir Path dir) throws Exception {
        OutputOptions options = new OutputOptions();
        options.outputDir = dir.resolve("out");
        options.format = new java.util.LinkedHashSet<>(Set.of("pdf"));

        int exitCode = CliSupport.writeReportAndCheckFormat(sampleReport(), options,
                new PrintStream(new java.io.ByteArrayOutputStream()));

        assertThat(exitCode).isEqualTo(2);
        assertThat(options.outputDir.resolve("report.json")).isRegularFile();
    }

    @Test
    void writeReportAndCheckFormatRendersHtmlAlongsideJson(@TempDir Path dir) throws Exception {
        OutputOptions options = new OutputOptions();
        options.outputDir = dir.resolve("out");
        options.format = new java.util.LinkedHashSet<>(List.of("json", "html"));

        int exitCode = CliSupport.writeReportAndCheckFormat(sampleReport(), options,
                new PrintStream(new java.io.ByteArrayOutputStream()));

        assertThat(exitCode).isZero();
        assertThat(options.outputDir.resolve("report.json")).isRegularFile();
        assertThat(options.outputDir.resolve("report.html")).isRegularFile();
        assertThat(Files.readString(options.outputDir.resolve("report.html"))).contains("<html");
    }

    @Test
    void writeReportAndCheckFormatRendersCsvAndXlsx(@TempDir Path dir) throws Exception {
        OutputOptions options = new OutputOptions();
        options.outputDir = dir.resolve("out");
        options.format = new java.util.LinkedHashSet<>(Set.of("csv", "xlsx"));

        int exitCode = CliSupport.writeReportAndCheckFormat(sampleReport(), options,
                new PrintStream(new java.io.ByteArrayOutputStream()));

        assertThat(exitCode).isZero();
        assertThat(options.outputDir.resolve("report.csv")).isRegularFile();
        assertThat(options.outputDir.resolve("report.xlsx")).isRegularFile();
    }

    private static DiffReport sampleReport() {
        return new DiffReport("jdiff", "dev", ReportMode.API_REPORT, Instant.parse("2024-01-01T00:00:00Z"),
                Map.of("gav", "g:a:1.0.0"),
                List.of(new ArtifactReport("g", "a", "1.0.0", "1.0.0", null, List.of())));
    }
}
