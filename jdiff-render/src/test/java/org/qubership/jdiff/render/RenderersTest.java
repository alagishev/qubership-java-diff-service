package org.qubership.jdiff.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RenderersTest {

    @Test
    void forFormatReturnsHtmlRenderer() {
        ReportRenderer renderer = Renderers.forFormat("html");

        assertThat(renderer).isInstanceOf(HtmlReportRenderer.class);
        assertThat(renderer.format()).isEqualTo("html");
        assertThat(renderer.fileName()).isEqualTo("report.html");
    }

    @Test
    void forFormatReturnsCsvRenderer() {
        ReportRenderer renderer = Renderers.forFormat("csv");

        assertThat(renderer).isInstanceOf(CsvReportRenderer.class);
        assertThat(renderer.format()).isEqualTo("csv");
        assertThat(renderer.fileName()).isEqualTo("report.csv");
    }

    @Test
    void forFormatReturnsXlsxRenderer() {
        ReportRenderer renderer = Renderers.forFormat("xlsx");

        assertThat(renderer).isInstanceOf(XlsxReportRenderer.class);
        assertThat(renderer.format()).isEqualTo("xlsx");
        assertThat(renderer.fileName()).isEqualTo("report.xlsx");
    }

    @Test
    void forFormatThrowsForUnknownFormat() {
        assertThatThrownBy(() -> Renderers.forFormat("pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pdf");
    }

    @Test
    void supportedFormatsListsAllThreeFormats() {
        assertThat(Renderers.supportedFormats()).containsExactlyInAnyOrder("html", "csv", "xlsx");
    }
}
