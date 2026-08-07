package org.qubership.jdiff.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.jdiff.model.DiffReport;
import org.qubership.jdiff.model.JsonSupport;
import org.qubership.jdiff.model.ReportMode;
import picocli.CommandLine;

/**
 * Full api-diff run through the real CLI entry point. Needs network access; run once with
 * {@code -Djdiff.it=true}.
 */
@EnabledIfSystemProperty(named = "jdiff.it", matches = "true")
class EndToEndIT {

    @Test
    void apiDiffCommonsCsvProducesAReportWithChanges(@TempDir Path tempDir) throws Exception {
        Path japicmpJar = Path.of("..", "japicmp", "japicmp-0.26.1-jar-with-dependencies.jar").normalize();
        assumeTrue(Files.isRegularFile(japicmpJar), "japicmp jar not found at " + japicmpJar.toAbsolutePath());

        int exitCode = new CommandLine(new JdiffMain()).execute(
                "api-diff",
                "--gav", "org.apache.commons:commons-csv",
                "--old", "1.11.0",
                "--new", "1.12.0",
                "--japicmp-jar", japicmpJar.toString(),
                "--output-dir", tempDir.toString());

        assertThat(exitCode).isZero();
        Path reportFile = tempDir.resolve("report.json");
        assertThat(reportFile).isRegularFile();

        DiffReport report = JsonSupport.fromJson(Files.readString(reportFile), DiffReport.class);
        assertThat(report.mode()).isEqualTo(ReportMode.API_DIFF);
        assertThat(report.artifacts()).hasSize(1);
        assertThat(report.artifacts().get(0).changes()).isNotEmpty();
    }
}
