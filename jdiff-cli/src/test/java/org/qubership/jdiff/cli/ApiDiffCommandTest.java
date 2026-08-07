package org.qubership.jdiff.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.jdiff.model.ArtifactReport;
import org.qubership.jdiff.model.DiffReport;
import org.qubership.jdiff.model.ReportMode;

class ApiDiffCommandTest {

    @Test
    void badGavExitsWithCodeTwoAndLogsAnErrorOnStderr() {
        ApiDiffCommand command = new ApiDiffCommand();
        command.gav = "onlyonesegment";
        command.oldVersion = "1.0.0";
        command.newVersion = "2.0.0";
        command.outputOptions = new OutputOptions();

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            Integer exitCode = command.call();

            assertThat(exitCode).isEqualTo(2);
            assertThat(captured.toString()).containsIgnoringCase("invalid gav");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void htmlFormatRequestedWritesReportHtmlAlongsideJson(@TempDir Path tempDir) {
        ApiDiffCommand command = new ApiDiffCommand();
        command.outputOptions = new OutputOptions();
        command.outputOptions.outputDir = tempDir;
        command.outputOptions.format = new LinkedHashSet<>(Set.of("html"));
        DiffReport fakeReport = sampleReport();

        Integer exitCode = command.run("g", "a", "1.0.0", "2.0.0", command.outputOptions, System.out,
                () -> fakeReport);

        assertThat(exitCode).isZero();
        assertThat(tempDir.resolve("report.json")).isRegularFile();
        assertThat(tempDir.resolve("report.html")).isRegularFile();
    }

    @Test
    void unknownFormatRequestedExitsWithCodeTwoAfterWritingJson(@TempDir Path tempDir) {
        ApiDiffCommand command = new ApiDiffCommand();
        command.outputOptions = new OutputOptions();
        command.outputOptions.outputDir = tempDir;
        command.outputOptions.format = new LinkedHashSet<>(Set.of("pdf"));
        DiffReport fakeReport = sampleReport();

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            Integer exitCode = command.run("g", "a", "1.0.0", "2.0.0", command.outputOptions, System.out,
                    () -> fakeReport);

            assertThat(exitCode).isEqualTo(2);
            assertThat(captured.toString()).containsIgnoringCase("unsupported format");
            assertThat(tempDir.resolve("report.json")).isRegularFile();
        } finally {
            System.setErr(originalErr);
        }
    }

    private static DiffReport sampleReport() {
        return new DiffReport("jdiff", "dev", ReportMode.API_DIFF, Instant.parse("2024-01-01T00:00:00Z"),
                Map.of("gav", "g:a", "oldVersion", "1.0.0", "newVersion", "2.0.0"),
                List.of(new ArtifactReport("g", "a", "1.0.0", "2.0.0", "MAJOR", List.of())));
    }
}
