package org.qubership.jdiff.render;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.qubership.jdiff.model.ApiChange;
import org.qubership.jdiff.model.ArtifactReport;
import org.qubership.jdiff.model.DiffReport;
import org.qubership.jdiff.model.UsageRef;

/**
 * Renders a {@link DiffReport} into a flat CSV file, one row per change (artifact fields repeated).
 */
public class CsvReportRenderer implements ReportRenderer {

    private static final char UTF8_BOM = '\uFEFF';

    private static final List<String> HEADERS = List.of(
            "groupId", "artifactId", "oldVersion", "newVersion", "semverVerdict",
            "className", "elementType", "member", "status", "changeTypes", "details",
            "binaryCompatible", "sourceCompatible", "breaking", "semver",
            "usedByModules", "usedByClasses");

    @Override
    public String format() {
        return "csv";
    }

    @Override
    public String fileName() {
        return "report.csv";
    }

    @Override
    public void render(DiffReport report, Path outputFile) {
        try {
            Files.createDirectories(outputFile.toAbsolutePath().getParent());
            try (Writer writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
                writer.write(UTF8_BOM);
                CSVFormat format = CSVFormat.DEFAULT.builder().setHeader(HEADERS.toArray(new String[0])).build();
                try (CSVPrinter printer = new CSVPrinter(writer, format)) {
                    for (ArtifactReport artifact : report.artifacts()) {
                        printRows(printer, artifact);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render CSV report to " + outputFile, e);
        }
    }

    private static void printRows(CSVPrinter printer, ArtifactReport artifact) throws IOException {
        if (artifact.changes().isEmpty()) {
            printer.printRecord(artifactColumns(artifact, emptyChangeColumns()));
            return;
        }
        for (ApiChange change : artifact.changes()) {
            printer.printRecord(artifactColumns(artifact, changeColumns(change)));
        }
    }

    private static List<Object> artifactColumns(ArtifactReport artifact, List<Object> changeColumns) {
        List<Object> row = new ArrayList<>();
        row.add(nullToEmpty(artifact.groupId()));
        row.add(nullToEmpty(artifact.artifactId()));
        row.add(nullToEmpty(artifact.oldVersion()));
        row.add(nullToEmpty(artifact.newVersion()));
        row.add(nullToEmpty(artifact.semverVerdict()));
        row.addAll(changeColumns);
        return row;
    }

    private static List<Object> changeColumns(ApiChange change) {
        List<Object> row = new ArrayList<>();
        row.add(nullToEmpty(change.className()));
        row.add(nullToEmpty(change.elementType()));
        row.add(nullToEmpty(change.member()));
        row.add(nullToEmpty(change.status()));
        row.add(change.changeTypes() == null ? "" : String.join(";", change.changeTypes()));
        row.add(nullToEmpty(change.details()));
        row.add(change.binaryCompatible() == null ? "" : change.binaryCompatible().toString());
        row.add(change.sourceCompatible() == null ? "" : change.sourceCompatible().toString());
        row.add(Boolean.toString(change.breaking()));
        row.add(nullToEmpty(change.semver()));
        row.add(usedByModules(change.usedBy()));
        row.add(usedByClasses(change.usedBy()));
        return row;
    }

    private static List<Object> emptyChangeColumns() {
        List<Object> row = new ArrayList<>();
        for (int i = 0; i < HEADERS.size() - 5; i++) {
            row.add("");
        }
        return row;
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

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
