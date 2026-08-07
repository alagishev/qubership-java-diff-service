package org.qubership.jdiff.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.jdiff.model.DiffReport;

class CsvReportRendererTest {

    private static final List<String> EXPECTED_HEADER = List.of(
            "groupId", "artifactId", "oldVersion", "newVersion", "semverVerdict",
            "className", "elementType", "member", "status", "changeTypes", "details",
            "binaryCompatible", "sourceCompatible", "breaking", "semver",
            "usedByModules", "usedByClasses");

    private final CsvReportRenderer renderer = new CsvReportRenderer();

    @Test
    void rendersOneRowPerChangePlusOneRowForArtifactsWithoutChanges(@TempDir Path tempDir) throws Exception {
        DiffReport report = ReportFixtures.upgradeImpactFixture();
        Path outputFile = tempDir.resolve("report.csv");

        renderer.render(report, outputFile);

        assertThat(outputFile).isRegularFile();
        assertBomPresent(outputFile);

        List<CSVRecord> records;
        try (Reader reader = Files.newBufferedReader(outputFile, StandardCharsets.UTF_8);
                CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                        .build().parse(stripBom(reader))) {
            assertThat(parser.getHeaderNames()).containsExactlyElementsOf(EXPECTED_HEADER);
            records = parser.getRecords();
        }

        assertThat(records).hasSize(3);

        CSVRecord breakingRow = records.get(0);
        assertThat(breakingRow.get("groupId")).isEqualTo("com.example");
        assertThat(breakingRow.get("artifactId")).isEqualTo("foo");
        assertThat(breakingRow.get("className")).isEqualTo(ReportFixtures.BREAKING_CLASS_NAME);
        assertThat(breakingRow.get("breaking")).isEqualTo("true");
        assertThat(breakingRow.get("usedByModules")).isEqualTo("com.example:consumer-a;com.example:consumer-b");
        assertThat(breakingRow.get("usedByClasses")).isEqualTo(
                "com.example:consumer-a=com.example.consumer.A;"
                        + "com.example:consumer-b=com.example.consumer.B|com.example.consumer.C");

        CSVRecord nonBreakingRow = records.get(1);
        assertThat(nonBreakingRow.get("breaking")).isEqualTo("false");
        assertThat(nonBreakingRow.get("usedByModules")).isEmpty();
        assertThat(nonBreakingRow.get("usedByClasses")).isEmpty();

        CSVRecord emptyArtifactRow = records.get(2);
        assertThat(emptyArtifactRow.get("groupId")).isEqualTo("com.example");
        assertThat(emptyArtifactRow.get("artifactId")).isEqualTo("baz");
        assertThat(emptyArtifactRow.get("className")).isEmpty();
        assertThat(emptyArtifactRow.get("status")).isEmpty();
        assertThat(emptyArtifactRow.get("breaking")).isEmpty();
    }

    @Test
    void formatAndFileNameAreCorrect() {
        assertThat(renderer.format()).isEqualTo("csv");
        assertThat(renderer.fileName()).isEqualTo("report.csv");
    }

    private static void assertBomPresent(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        assertThat(bytes).hasSizeGreaterThanOrEqualTo(3);
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
    }

    private static Reader stripBom(Reader reader) throws Exception {
        reader.mark(1);
        int first = reader.read();
        if (first != '\uFEFF') {
            reader.reset();
        }
        return reader;
    }
}
