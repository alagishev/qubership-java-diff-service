package org.qubership.jdiff.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.jdiff.model.DiffReport;

class HtmlReportRendererTest {

    private final HtmlReportRenderer renderer = new HtmlReportRenderer();

    @Test
    void rendersUpgradeImpactReportWithArtifactsBadgeAndEscaping(@TempDir Path tempDir) throws Exception {
        DiffReport report = ReportFixtures.upgradeImpactFixture();
        Path outputFile = tempDir.resolve("report.html");

        renderer.render(report, outputFile);

        assertThat(outputFile).isRegularFile();
        String html = Files.readString(outputFile);

        assertThat(html).contains("com.example:foo");
        assertThat(html).contains("com.example:baz");
        assertThat(html).contains(ReportFixtures.BREAKING_CLASS_NAME);
        assertThat(html).contains("MAJOR");
        assertThat(html).doesNotContain("${");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("Used by");
    }

    @Test
    void omitsUsedByColumnWhenNoChangeHasUsedBy(@TempDir Path tempDir) throws Exception {
        DiffReport report = ReportFixtures.apiDiffFixture();
        Path outputFile = tempDir.resolve("report.html");

        renderer.render(report, outputFile);

        String html = Files.readString(outputFile);

        assertThat(html).doesNotContain("${");
        assertThat(html).doesNotContain("Used by");
    }

    @Test
    void formatAndFileNameAreCorrect() {
        assertThat(renderer.format()).isEqualTo("html");
        assertThat(renderer.fileName()).isEqualTo("report.html");
    }
}
