package org.qubership.jdiff.render;

import freemarker.cache.ClassTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.qubership.jdiff.model.ApiChange;
import org.qubership.jdiff.model.ArtifactReport;
import org.qubership.jdiff.model.DiffReport;
import org.qubership.jdiff.model.UsageRef;

/**
 * Renders a {@link DiffReport} into a single self-contained HTML file using a FreeMarker template.
 */
public class HtmlReportRenderer implements ReportRenderer {

    private static final String TEMPLATE_NAME = "report.ftl";

    private final Configuration configuration;

    public HtmlReportRenderer() {
        this.configuration = buildConfiguration();
    }

    private static Configuration buildConfiguration() {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_34);
        cfg.setTemplateLoader(new ClassTemplateLoader(HtmlReportRenderer.class, "/templates"));
        cfg.setDefaultEncoding(StandardCharsets.UTF_8.name());
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setLogTemplateExceptions(false);
        cfg.setWrapUncheckedExceptions(true);
        cfg.setFallbackOnNullLoopVariable(false);
        return cfg;
    }

    @Override
    public String format() {
        return "html";
    }

    @Override
    public String fileName() {
        return "report.html";
    }

    @Override
    public void render(DiffReport report, Path outputFile) {
        try {
            Template template = configuration.getTemplate(TEMPLATE_NAME);
            Files.createDirectories(outputFile.toAbsolutePath().getParent());
            try (Writer writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
                template.process(buildModel(report), writer);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render HTML report to " + outputFile, e);
        } catch (TemplateException e) {
            throw new IllegalStateException("Failed to process HTML template for " + outputFile, e);
        }
    }

    private static Map<String, Object> buildModel(DiffReport report) {
        List<ArtifactView> artifacts = new ArrayList<>();
        boolean showUsedBy = false;
        for (ArtifactReport artifact : report.artifacts()) {
            artifacts.add(toArtifactView(artifact));
            for (ApiChange change : artifact.changes()) {
                if (hasUsedBy(change)) {
                    showUsedBy = true;
                }
            }
        }

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("tool", report.tool());
        model.put("toolVersion", report.toolVersion());
        model.put("mode", String.valueOf(report.mode()));
        model.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(report.generatedAt()));
        model.put("inputEntries", toInputEntries(report.input()).stream()
                .map(HtmlReportRenderer::toMap)
                .toList());
        model.put("artifacts", artifacts.stream().map(HtmlReportRenderer::toMap).toList());
        model.put("showUsedBy", showUsedBy);
        return model;
    }

    private static Map<String, Object> toMap(InputEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", entry.key());
        map.put("value", entry.value());
        return map;
    }

    private static Map<String, Object> toMap(ArtifactView artifact) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ga", artifact.ga());
        map.put("oldVersion", artifact.oldVersion());
        map.put("newVersion", artifact.newVersion());
        map.put("semverVerdict", artifact.semverVerdict());
        map.put("semverBadgeClass", artifact.semverBadgeClass());
        map.put("changeCount", artifact.changeCount());
        map.put("breakingCount", artifact.breakingCount());
        map.put("changes", artifact.changes().stream().map(HtmlReportRenderer::toMap).toList());
        return map;
    }

    private static Map<String, Object> toMap(ChangeView change) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("className", change.className());
        map.put("elementType", change.elementType());
        map.put("member", change.member());
        map.put("status", change.status());
        map.put("changeTypesJoined", change.changeTypesJoined());
        map.put("details", change.details());
        map.put("breaking", change.breaking());
        map.put("semver", change.semver());
        map.put("usedBy", change.usedBy());
        return map;
    }

    private static List<InputEntry> toInputEntries(Map<String, Object> input) {
        List<InputEntry> entries = new ArrayList<>();
        if (input != null) {
            input.forEach((key, value) -> entries.add(new InputEntry(key, String.valueOf(value))));
        }
        return entries;
    }

    private static ArtifactView toArtifactView(ArtifactReport artifact) {
        List<ChangeView> changes = new ArrayList<>();
        int breakingCount = 0;
        for (ApiChange change : artifact.changes()) {
            if (change.breaking()) {
                breakingCount++;
            }
            changes.add(toChangeView(change));
        }
        String semverVerdict = artifact.semverVerdict() == null ? "NONE" : artifact.semverVerdict();
        return new ArtifactView(artifact.groupId() + ":" + artifact.artifactId(), artifact.oldVersion(),
                artifact.newVersion(), semverVerdict, badgeClass(semverVerdict), artifact.changes().size(),
                breakingCount, changes);
    }

    private static ChangeView toChangeView(ApiChange change) {
        String changeTypesJoined = change.changeTypes() == null ? "" : String.join(", ", change.changeTypes());
        return new ChangeView(change.className(), change.elementType(), change.member(), change.status(),
                changeTypesJoined, change.details(), change.breaking(), change.semver(), formatUsedBy(change.usedBy()));
    }

    private static boolean hasUsedBy(ApiChange change) {
        return change.usedBy() != null && !change.usedBy().isEmpty();
    }

    private static String formatUsedBy(List<UsageRef> usedBy) {
        if (usedBy == null || usedBy.isEmpty()) {
            return "";
        }
        List<String> groups = new ArrayList<>();
        for (UsageRef ref : usedBy) {
            String classes = ref.classes() == null ? "" : String.join(", ", ref.classes());
            groups.add(ref.module() + ": " + classes);
        }
        return String.join("; ", groups);
    }

    private static String badgeClass(String semverVerdict) {
        return switch (semverVerdict) {
            case "MAJOR" -> "badge-major";
            case "MINOR" -> "badge-minor";
            case "PATCH" -> "badge-patch";
            default -> "badge-none";
        };
    }

    /**
     * View model for a single input map entry, formatted for display.
     */
    public record InputEntry(String key, String value) {
    }

    /**
     * View model for a single artifact's summary row and change table.
     */
    public record ArtifactView(String ga, String oldVersion, String newVersion, String semverVerdict,
                                String semverBadgeClass, int changeCount, int breakingCount,
                                List<ChangeView> changes) {
    }

    /**
     * View model for a single API change row.
     */
    public record ChangeView(String className, String elementType, String member, String status,
                              String changeTypesJoined, String details, boolean breaking, String semver,
                              String usedBy) {
    }
}
