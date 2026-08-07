package org.qubership.jdiff.render;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.qubership.jdiff.model.ApiChange;
import org.qubership.jdiff.model.ArtifactReport;
import org.qubership.jdiff.model.DiffReport;
import org.qubership.jdiff.model.UsageRef;

/**
 * Renders a {@link DiffReport} into an XLSX workbook: a "Summary" sheet plus one sheet per artifact.
 */
public class XlsxReportRenderer implements ReportRenderer {

    private static final int MAX_COLUMN_WIDTH_CHARS = 60;
    private static final List<String> CHANGE_HEADERS = List.of(
            "className", "elementType", "member", "status", "changeTypes", "details",
            "binaryCompatible", "sourceCompatible", "breaking", "semver",
            "usedByModules", "usedByClasses");
    private static final List<String> SUMMARY_TABLE_HEADERS = List.of(
            "artifact", "oldVersion", "newVersion", "semverVerdict", "totalChanges", "breakingCount");

    @Override
    public String format() {
        return "xlsx";
    }

    @Override
    public String fileName() {
        return "report.xlsx";
    }

    @Override
    public void render(DiffReport report, Path outputFile) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = new Styles(workbook);
            writeSummarySheet(workbook, styles, report);
            writeArtifactSheets(workbook, styles, report);

            Files.createDirectories(outputFile.toAbsolutePath().getParent());
            try (OutputStream out = Files.newOutputStream(outputFile)) {
                workbook.write(out);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render XLSX report to " + outputFile, e);
        }
    }

    private static void writeSummarySheet(XSSFWorkbook workbook, Styles styles, DiffReport report) {
        XSSFSheet sheet = workbook.createSheet("Summary");
        int rowNum = 0;

        rowNum = writeInfoRow(sheet, rowNum, "Tool", report.tool());
        rowNum = writeInfoRow(sheet, rowNum, "Version", report.toolVersion());
        rowNum = writeInfoRow(sheet, rowNum, "Mode", String.valueOf(report.mode()));
        rowNum = writeInfoRow(sheet, rowNum, "Generated at", String.valueOf(report.generatedAt()));
        if (report.input() != null) {
            for (Map.Entry<String, Object> entry : report.input().entrySet()) {
                rowNum = writeInfoRow(sheet, rowNum, entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        rowNum++;

        Row headerRow = sheet.createRow(rowNum++);
        for (int i = 0; i < SUMMARY_TABLE_HEADERS.size(); i++) {
            createCell(headerRow, i, SUMMARY_TABLE_HEADERS.get(i), styles.header);
        }
        int headerRowIndex = headerRow.getRowNum();

        for (ArtifactReport artifact : report.artifacts()) {
            Row row = sheet.createRow(rowNum++);
            int breakingCount = (int) artifact.changes().stream().filter(ApiChange::breaking).count();
            createCell(row, 0, artifact.groupId() + ":" + artifact.artifactId(), null);
            createCell(row, 1, artifact.oldVersion(), null);
            createCell(row, 2, artifact.newVersion(), null);
            createCell(row, 3, artifact.semverVerdict(), null);
            createCell(row, 4, String.valueOf(artifact.changes().size()), null);
            createCell(row, 5, String.valueOf(breakingCount), null);
        }

        sheet.createFreezePane(0, headerRowIndex + 1);
        autoSizeColumns(sheet, SUMMARY_TABLE_HEADERS.size());
    }

    private static int writeInfoRow(XSSFSheet sheet, int rowNum, String label, String value) {
        Row row = sheet.createRow(rowNum);
        createCell(row, 0, label, null);
        createCell(row, 1, value, null);
        return rowNum + 1;
    }

    private static void writeArtifactSheets(XSSFWorkbook workbook, Styles styles, DiffReport report) {
        Set<String> usedNames = new HashSet<>();
        for (ArtifactReport artifact : report.artifacts()) {
            String sheetName = uniqueSheetName(artifact.artifactId(), usedNames);
            XSSFSheet sheet = workbook.createSheet(sheetName);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < CHANGE_HEADERS.size(); i++) {
                createCell(headerRow, i, CHANGE_HEADERS.get(i), styles.header);
            }

            int rowNum = 1;
            for (ApiChange change : artifact.changes()) {
                Row row = sheet.createRow(rowNum++);
                writeChangeRow(row, change, change.breaking() ? styles.breaking : null);
            }

            sheet.createFreezePane(0, 1);
            autoSizeColumns(sheet, CHANGE_HEADERS.size());
        }
    }

    private static void writeChangeRow(Row row, ApiChange change, CellStyle rowStyle) {
        createCell(row, 0, change.className(), rowStyle);
        createCell(row, 1, change.elementType(), rowStyle);
        createCell(row, 2, change.member(), rowStyle);
        createCell(row, 3, change.status(), rowStyle);
        createCell(row, 4, change.changeTypes() == null ? "" : String.join(";", change.changeTypes()), rowStyle);
        createCell(row, 5, change.details() == null ? "" : change.details(), rowStyle);
        createCell(row, 6, change.binaryCompatible() == null ? "" : change.binaryCompatible().toString(), rowStyle);
        createCell(row, 7, change.sourceCompatible() == null ? "" : change.sourceCompatible().toString(), rowStyle);
        createCell(row, 8, Boolean.toString(change.breaking()), rowStyle);
        createCell(row, 9, change.semver(), rowStyle);
        createCell(row, 10, usedByModules(change.usedBy()), rowStyle);
        createCell(row, 11, usedByClasses(change.usedBy()), rowStyle);
    }

    private static String usedByModules(List<UsageRef> usedBy) {
        if (usedBy == null || usedBy.isEmpty()) {
            return "";
        }
        List<String> modules = new ArrayList<>();
        for (UsageRef ref : usedBy) {
            modules.add(ref.module());
        }
        return String.join(";", modules);
    }

    private static String usedByClasses(List<UsageRef> usedBy) {
        if (usedBy == null || usedBy.isEmpty()) {
            return "";
        }
        List<String> groups = new ArrayList<>();
        for (UsageRef ref : usedBy) {
            String classes = ref.classes() == null ? "" : String.join("|", ref.classes());
            groups.add(ref.module() + "=" + classes);
        }
        return String.join(";", groups);
    }

    private static void createCell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    private static void autoSizeColumns(XSSFSheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
            int width = sheet.getColumnWidth(i);
            int maxWidth = (MAX_COLUMN_WIDTH_CHARS + 2) * 256;
            if (width > maxWidth) {
                sheet.setColumnWidth(i, maxWidth);
            }
        }
    }

    private static String uniqueSheetName(String artifactId, Set<String> usedNames) {
        String base = WorkbookUtil.createSafeSheetName(artifactId == null ? "artifact" : artifactId);
        String candidate = base;
        int suffix = 2;
        while (!usedNames.add(candidate)) {
            String suffixStr = "-" + suffix;
            int allowedBaseLength = Math.max(1, 31 - suffixStr.length());
            String truncatedBase = base.length() > allowedBaseLength ? base.substring(0, allowedBaseLength) : base;
            candidate = truncatedBase + suffixStr;
            suffix++;
        }
        return candidate;
    }

    private static final class Styles {
        private final CellStyle header;
        private final CellStyle breaking;

        Styles(XSSFWorkbook workbook) {
            Font boldFont = workbook.createFont();
            boldFont.setBold(true);

            header = workbook.createCellStyle();
            header.setFont(boldFont);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            breaking = workbook.createCellStyle();
            breaking.setFillForegroundColor(IndexedColors.ROSE.getIndex());
            breaking.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
    }
}
