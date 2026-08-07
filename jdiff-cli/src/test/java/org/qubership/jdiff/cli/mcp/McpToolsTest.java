package org.qubership.jdiff.cli.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.jdiff.model.ArtifactReport;
import org.qubership.jdiff.model.DiffReport;
import org.qubership.jdiff.model.Gav;
import org.qubership.jdiff.model.JsonSupport;
import org.qubership.jdiff.model.ReportMode;

class McpToolsTest {

    @Test
    void generateApiReportHappyPathReturnsTheReportAsJsonTextContent() {
        DiffReport fakeReport = sampleReport(ReportMode.API_REPORT);
        McpTools tools = new McpTools(
                (gav, repositories) -> {
                    assertThat(gav).isEqualTo(Gav.parse("org.example:app:1.0.0"));
                    assertThat(repositories).containsExactly("https://example.com/repo");
                    return fakeReport;
                },
                failingApiDiffRunner(),
                failingUpgradeImpactRunner());

        CallToolResult result = callHandler(tools.generateApiReportTool(), Map.of(
                "gav", "org.example:app:1.0.0",
                "repositories", List.of("https://example.com/repo")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        DiffReport parsed = parseReport(result);
        assertThat(parsed.mode()).isEqualTo(ReportMode.API_REPORT);
    }

    @Test
    void generateApiReportMissingGavReturnsIsErrorWithoutThrowing() {
        McpTools tools = new McpTools(failingApiReportRunner(), failingApiDiffRunner(), failingUpgradeImpactRunner());

        CallToolResult result = callHandler(tools.generateApiReportTool(), Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).containsIgnoringCase("gav");
    }

    @Test
    void generateApiDiffHappyPathReturnsTheReportAsJsonTextContent() {
        DiffReport fakeReport = sampleReport(ReportMode.API_DIFF);
        McpTools tools = new McpTools(
                failingApiReportRunner(),
                (groupId, artifactId, oldVersion, newVersion, repositories) -> {
                    assertThat(groupId).isEqualTo("org.example");
                    assertThat(artifactId).isEqualTo("app");
                    assertThat(oldVersion).isEqualTo("1.0.0");
                    assertThat(newVersion).isEqualTo("2.0.0");
                    return fakeReport;
                },
                failingUpgradeImpactRunner());

        CallToolResult result = callHandler(tools.generateApiDiffTool(), Map.of(
                "gav", "org.example:app",
                "oldVersion", "1.0.0",
                "newVersion", "2.0.0"));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        DiffReport parsed = parseReport(result);
        assertThat(parsed.mode()).isEqualTo(ReportMode.API_DIFF);
    }

    @Test
    void generateApiDiffMissingRequiredArgReturnsIsErrorWithoutThrowing() {
        McpTools tools = new McpTools(failingApiReportRunner(), failingApiDiffRunner(), failingUpgradeImpactRunner());

        CallToolResult result = callHandler(tools.generateApiDiffTool(), Map.of("gav", "org.example:app"));

        assertThat(result.isError()).isTrue();
    }

    @Test
    void upgradeImpactHappyPathReturnsTheReportAsJsonTextContent() {
        DiffReport fakeReport = sampleReport(ReportMode.UPGRADE_IMPACT);
        McpTools tools = new McpTools(
                failingApiReportRunner(),
                failingApiDiffRunner(),
                (request, repositories, threads) -> {
                    assertThat(request.targetGav()).isEqualTo(Gav.parse("org.example:app:1.0.0"));
                    assertThat(request.upgrades()).hasSize(1);
                    return fakeReport;
                });

        CallToolResult result = callHandler(tools.upgradeImpactTool(), Map.of(
                "gav", "org.example:app:1.0.0",
                "upgrades", List.of("org.example:lib=2.0.0")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        DiffReport parsed = parseReport(result);
        assertThat(parsed.mode()).isEqualTo(ReportMode.UPGRADE_IMPACT);
    }

    @Test
    void upgradeImpactMissingUpgradesReturnsIsErrorWithoutThrowing() {
        McpTools tools = new McpTools(failingApiReportRunner(), failingApiDiffRunner(), failingUpgradeImpactRunner());

        CallToolResult result = callHandler(tools.upgradeImpactTool(), Map.of("gav", "org.example:app:1.0.0"));

        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).containsIgnoringCase("upgrades");
    }

    @Test
    void upgradeImpactBothProjectAndGavGivenReturnsIsErrorWithoutThrowing() {
        McpTools tools = new McpTools(failingApiReportRunner(), failingApiDiffRunner(), failingUpgradeImpactRunner());

        CallToolResult result = callHandler(tools.upgradeImpactTool(), Map.of(
                "project", "some/project",
                "gav", "org.example:app:1.0.0",
                "upgrades", List.of("org.example:lib=2.0.0")));

        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).containsIgnoringCase("exactly one");
    }

    @Test
    void upgradeImpactNeitherProjectNorGavGivenReturnsIsErrorWithoutThrowing() {
        McpTools tools = new McpTools(failingApiReportRunner(), failingApiDiffRunner(), failingUpgradeImpactRunner());

        CallToolResult result = callHandler(tools.upgradeImpactTool(), Map.of(
                "upgrades", List.of("org.example:lib=2.0.0")));

        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).containsIgnoringCase("exactly one");
    }

    @Test
    void exceptionFromThePipelineIsCaughtAndReturnedAsIsErrorInsteadOfThrowing() {
        McpTools tools = new McpTools(
                (gav, repositories) -> {
                    throw new IllegalStateException("boom");
                },
                failingApiDiffRunner(),
                failingUpgradeImpactRunner());

        CallToolResult result = callHandler(tools.generateApiReportTool(), Map.of("gav", "org.example:app:1.0.0"));

        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).contains("IllegalStateException").contains("boom");
    }

    @Test
    void upgradeImpactAcceptsAProjectPathInsteadOfAGav() {
        DiffReport fakeReport = sampleReport(ReportMode.UPGRADE_IMPACT);
        McpTools tools = new McpTools(
                failingApiReportRunner(),
                failingApiDiffRunner(),
                (request, repositories, threads) -> {
                    assertThat(request.projectDir()).isEqualTo(Path.of("some/project"));
                    assertThat(request.targetGav()).isNull();
                    return fakeReport;
                });

        CallToolResult result = callHandler(tools.upgradeImpactTool(), Map.of(
                "project", "some/project",
                "upgrades", List.of("org.example:lib=2.0.0")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    }

    private static McpTools.ApiReportRunner failingApiReportRunner() {
        return (gav, repositories) -> {
            throw new AssertionError("apiReportRunner should not have been called");
        };
    }

    private static McpTools.ApiDiffRunner failingApiDiffRunner() {
        return (groupId, artifactId, oldVersion, newVersion, repositories) -> {
            throw new AssertionError("apiDiffRunner should not have been called");
        };
    }

    private static McpTools.UpgradeImpactRunner failingUpgradeImpactRunner() {
        return (request, repositories, threads) -> {
            throw new AssertionError("upgradeImpactRunner should not have been called");
        };
    }

    private static CallToolResult callHandler(SyncToolSpecification spec, Map<String, Object> arguments) {
        CallToolRequest request = new CallToolRequest(spec.tool().name(), arguments);
        return spec.callHandler().apply(null, request);
    }

    private static String textOf(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    private static DiffReport parseReport(CallToolResult result) {
        return JsonSupport.fromJson(textOf(result), DiffReport.class);
    }

    private static DiffReport sampleReport(ReportMode mode) {
        return new DiffReport("jdiff", "dev", mode, Instant.parse("2024-01-01T00:00:00Z"),
                Map.of("gav", "g:a:1.0.0"),
                List.of(new ArtifactReport("g", "a", "1.0.0", "1.0.0", null, List.of())));
    }
}
