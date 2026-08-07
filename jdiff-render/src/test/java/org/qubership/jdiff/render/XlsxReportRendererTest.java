package org.qubership.jdiff.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.PaneInformation;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.jdiff.model.ArtifactReport;
import org.qubership.jdiff.model.DiffReport;

class XlsxReportRendererTest {

    private final XlsxReportRenderer renderer = new XlsxReportRenderer();

    @Test
    void rendersSummaryAndOneSheetPerArtifact(@TempDir Path tempDir) throws Exception {
        DiffReport report = ReportFixtures.upgradeImpactFixture();
        Path outputFile = tempDir.resolve("report.xlsx");

        renderer.render(report, outputFile);

        assertThat(outputFile).isRegularFile();

        try (FileInputStream in = new FileInputStream(outputFile.toFile());
                XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(3);
            assertThat(workbook.getSheetName(0)).isEqualTo("Summary");
            assertThat(workbook.getSheetName(1)).isEqualTo("foo");
            assertThat(workbook.getSheetName(2)).isEqualTo("baz");

            XSSFSheet summary = workbook.getSheet("Summary");
            Row summaryHeaderRow = findRow(summary, "artifact");
            assertThat(summaryHeaderRow).isNotNull();
            int headerRowIndex = summaryHeaderRow.getRowNum();
            Row fooRow = summary.getRow(headerRowIndex + 1);
            assertThat(fooRow.getCell(0).getStringCellValue()).isEqualTo("com.example:foo");
            assertThat(fooRow.getCell(1).getStringCellValue()).isEqualTo("1.0.0");
            assertThat(fooRow.getCell(2).getStringCellValue()).isEqualTo("2.0.0");
            assertThat(fooRow.getCell(3).getStringCellValue()).isEqualTo("MAJOR");
            assertThat(fooRow.getCell(4).getStringCellValue()).isEqualTo("2");
            assertThat(fooRow.getCell(5).getStringCellValue()).isEqualTo("1");

            PaneInformation summaryPane = summary.getPaneInformation();
            assertThat(summaryPane).isNotNull();
            assertThat(summaryPane.isFreezePane()).isTrue();
            assertThat(summaryPane.getHorizontalSplitTopRow()).isEqualTo((short) (headerRowIndex + 1));

            XSSFSheet fooSheet = workbook.getSheet("foo");
            PaneInformation fooPane = fooSheet.getPaneInformation();
            assertThat(fooPane).isNotNull();
            assertThat(fooPane.isFreezePane()).isTrue();
            assertThat(fooPane.getHorizontalSplitTopRow()).isEqualTo((short) 1);

            Row breakingRow = fooSheet.getRow(1);
            assertThat(breakingRow.getCell(0).getStringCellValue()).isEqualTo(ReportFixtures.BREAKING_CLASS_NAME);
            assertThat(breakingRow.getCell(8).getStringCellValue()).isEqualTo("true");
            assertThat(breakingRow.getCell(0).getCellStyle().getFillForegroundColor())
                    .isNotEqualTo(org.apache.poi.ss.usermodel.IndexedColors.AUTOMATIC.getIndex());

            Row nonBreakingRow = fooSheet.getRow(2);
            assertThat(nonBreakingRow.getCell(8).getStringCellValue()).isEqualTo("false");
            assertThat(nonBreakingRow.getCell(0).getCellStyle().getFillForegroundColor())
                    .isNotEqualTo(breakingRow.getCell(0).getCellStyle().getFillForegroundColor());
        }
    }

    @Test
    void sanitizesAndDeduplicatesSheetNames(@TempDir Path tempDir) throws Exception {
        String longName = "a-very-long-artifact-id-that-exceeds-thirty-one-characters";
        String slashName = "weird/name:with*illegal?chars";
        ArtifactReport artifactA = new ArtifactReport("g", longName, "1.0.0", "1.1.0", "PATCH", List.of());
        ArtifactReport artifactB = new ArtifactReport("g", longName, "1.0.0", "1.1.0", "PATCH", List.of());
        ArtifactReport artifactC = new ArtifactReport("g", slashName, "1.0.0", "1.1.0", "PATCH", List.of());
        DiffReport report = new DiffReport("jdiff", "test", org.qubership.jdiff.model.ReportMode.API_DIFF,
                java.time.Instant.parse("2024-01-01T00:00:00Z"), java.util.Map.of(),
                List.of(artifactA, artifactB, artifactC));
        Path outputFile = tempDir.resolve("report.xlsx");

        renderer.render(report, outputFile);

        try (FileInputStream in = new FileInputStream(outputFile.toFile());
                XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
            List<String> sheetNames = List.of(
                    workbook.getSheetName(0), workbook.getSheetName(1),
                    workbook.getSheetName(2), workbook.getSheetName(3));

            assertThat(sheetNames).doesNotHaveDuplicates();
            for (String name : sheetNames.subList(1, sheetNames.size())) {
                assertThat(name.length()).isLessThanOrEqualTo(31);
                assertThat(name).doesNotContain("/", "\\", "?", "*", "[", "]", ":");
            }
        }
    }

    @Test
    void formatAndFileNameAreCorrect() {
        assertThat(renderer.format()).isEqualTo("xlsx");
        assertThat(renderer.fileName()).isEqualTo("report.xlsx");
    }

    private static Row findRow(XSSFSheet sheet, String firstCellValue) {
        for (Row row : sheet) {
            if (row.getCell(0) != null
                    && row.getCell(0).getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                    && firstCellValue.equals(row.getCell(0).getStringCellValue())) {
                return row;
            }
        }
        return null;
    }
}
