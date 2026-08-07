package org.qubership.jdiff.render;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.qubership.jdiff.model.ApiChange;
import org.qubership.jdiff.model.ArtifactReport;
import org.qubership.jdiff.model.DiffReport;
import org.qubership.jdiff.model.ReportMode;
import org.qubership.jdiff.model.UsageRef;

/**
 * Reusable {@link DiffReport} fixtures shared by the renderer tests.
 */
final class ReportFixtures {

    static final String BREAKING_CLASS_NAME = "com.example.foo.Foo";
    static final String BREAKING_MEMBER_WITH_SCRIPT = "process(<script>alert(1)</script>)";

    private ReportFixtures() {
    }

    /**
     * Mode {@code UPGRADE_IMPACT}, 2 artifacts: one with 2 changes (one breaking with usedBy across
     * 2 modules, one non-breaking), one with 0 changes. Input map has 3 entries.
     */
    static DiffReport upgradeImpactFixture() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("project", "com.example:root");
        input.put("upgrade", "com.example:foo=2.0.0");
        input.put("threads", 4);

        ApiChange breakingChange = new ApiChange(
                BREAKING_CLASS_NAME, "METHOD", BREAKING_MEMBER_WITH_SCRIPT, "REMOVED",
                List.of("METHOD_REMOVED"), null, Boolean.FALSE, Boolean.FALSE, true, "MAJOR",
                List.of(
                        new UsageRef("com.example:consumer-a", List.of("com.example.consumer.A")),
                        new UsageRef("com.example:consumer-b", List.of("com.example.consumer.B",
                                "com.example.consumer.C"))));
        ApiChange nonBreakingChange = new ApiChange(
                "com.example.foo.Bar", "METHOD", "helper(java.lang.String)", "MODIFIED",
                List.of("METHOD_NEW_DEFAULT"), null, Boolean.TRUE, Boolean.TRUE, false, "MINOR", null);

        ArtifactReport artifactWithChanges = new ArtifactReport(
                "com.example", "foo", "1.0.0", "2.0.0", "MAJOR", List.of(breakingChange, nonBreakingChange));
        ArtifactReport artifactWithoutChanges = new ArtifactReport(
                "com.example", "baz", "1.5.0", "1.6.0", "NONE", List.of());

        return new DiffReport("jdiff", "test", ReportMode.UPGRADE_IMPACT, Instant.parse("2024-06-01T10:15:30Z"),
                input, List.of(artifactWithChanges, artifactWithoutChanges));
    }

    /**
     * Mode {@code API_DIFF}, no {@code usedBy} anywhere in the report.
     */
    static DiffReport apiDiffFixture() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("gav", "com.example:qux");
        input.put("old", "1.0.0");
        input.put("new", "1.1.0");

        ApiChange change = new ApiChange(
                "com.example.qux.Qux", "CLASS", null, "MODIFIED",
                List.of("CLASS_NOW_ABSTRACT"), null, Boolean.TRUE, Boolean.FALSE, false, "MINOR", null);

        ArtifactReport artifact = new ArtifactReport(
                "com.example", "qux", "1.0.0", "1.1.0", "MINOR", List.of(change));

        return new DiffReport("jdiff", "test", ReportMode.API_DIFF, Instant.parse("2024-06-02T08:00:00Z"),
                input, List.of(artifact));
    }
}
