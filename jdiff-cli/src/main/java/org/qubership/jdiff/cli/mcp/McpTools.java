package org.qubership.jdiff.cli.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.qubership.jdiff.cli.CliSupport;
import org.qubership.jdiff.jdeps.JdepsRunner;
import org.qubership.jdiff.japicmp.JapicmpRunner;
import org.qubership.jdiff.model.DiffReport;
import org.qubership.jdiff.model.Gav;
import org.qubership.jdiff.model.JsonSupport;
import org.qubership.jdiff.pipeline.ApiDiffPipeline;
import org.qubership.jdiff.pipeline.ApiReportPipeline;
import org.qubership.jdiff.pipeline.JapicmpJarComparator;
import org.qubership.jdiff.pipeline.JarComparator;
import org.qubership.jdiff.pipeline.UpgradeImpactPipeline;
import org.qubership.jdiff.pipeline.UpgradeRequest;
import org.qubership.jdiff.resolve.ArtifactResolver;
import org.qubership.jdiff.resolve.DependencyExtractor;
import org.qubership.jdiff.resolve.EffectivePomBuilder;
import org.qubership.jdiff.resolve.MavenArtifactResolver;
import org.qubership.jdiff.resolve.ProjectScanner;
import org.qubership.jdiff.resolve.RepositoryConfig;
import org.qubership.jdiff.tools.ExternalToolRunner;
import org.qubership.jdiff.upgrade.UpgradeSpec;

/**
 * Builds the three MCP tool specifications ({@code generate_api_report}, {@code generate_api_diff},
 * {@code upgrade_impact}) that {@link JdiffMcpServer} exposes. Each tool wraps the same core
 * pipelines and plumbing used by the {@code api-report}, {@code api-diff} and {@code upgrade} CLI
 * subcommands (see {@link CliSupport}), with the japicmp jar located via
 * {@link CliSupport#resolveJapicmpJarOrThrow(Path)} honoring a {@value #JAPICMP_JAR_ENV} environment
 * variable override (there is no {@code --japicmp-jar} flag in MCP mode).
 *
 * <p>The pipeline-running collaborators are injected via functional factories so tests can supply
 * fakes without exercising real artifact resolution or japicmp.
 */
public final class McpTools {

    static final String GENERATE_API_REPORT = "generate_api_report";
    static final String GENERATE_API_DIFF = "generate_api_diff";
    static final String UPGRADE_IMPACT = "upgrade_impact";

    private static final String JAPICMP_JAR_ENV = "JDIFF_JAPICMP_JAR";

    private final ApiReportRunner apiReportRunner;
    private final ApiDiffRunner apiDiffRunner;
    private final UpgradeImpactRunner upgradeImpactRunner;

    /**
     * @return an {@link McpTools} instance wired to the real pipelines and Maven/japicmp plumbing
     */
    public static McpTools createDefault() {
        return new McpTools(
                (gav, repositories) -> buildApiReportPipeline(repositories).run(gav),
                (groupId, artifactId, oldVersion, newVersion, repositories) ->
                        buildApiDiffPipeline(repositories).run(groupId, artifactId, oldVersion, newVersion),
                (request, repositories, threads) -> buildUpgradeImpactPipeline(repositories, threads).run(request));
    }

    McpTools(ApiReportRunner apiReportRunner, ApiDiffRunner apiDiffRunner, UpgradeImpactRunner upgradeImpactRunner) {
        this.apiReportRunner = apiReportRunner;
        this.apiDiffRunner = apiDiffRunner;
        this.upgradeImpactRunner = upgradeImpactRunner;
    }

    /**
     * @return the {@code generate_api_report} tool specification
     */
    public SyncToolSpecification generateApiReportTool() {
        Tool tool = Tool.builder(GENERATE_API_REPORT, generateApiReportSchema())
                .description("Generate a full API inventory report (classes, methods, fields) for a Maven jar "
                        + "artifact, as machine-readable JSON.")
                .build();
        return new SyncToolSpecification(tool, this::handleGenerateApiReport);
    }

    /**
     * @return the {@code generate_api_diff} tool specification
     */
    public SyncToolSpecification generateApiDiffTool() {
        Tool tool = Tool.builder(GENERATE_API_DIFF, generateApiDiffSchema())
                .description("Compare two versions of a Maven jar artifact and report API changes with "
                        + "breaking-change flags and a semver verdict, as machine-readable JSON.")
                .build();
        return new SyncToolSpecification(tool, this::handleGenerateApiDiff);
    }

    /**
     * @return the {@code upgrade_impact} tool specification
     */
    public SyncToolSpecification upgradeImpactTool() {
        Tool tool = Tool.builder(UPGRADE_IMPACT, upgradeImpactSchema())
                .description("Analyze the impact of upgrading dependencies of a Maven project (project folder or "
                        + "target jar GAV): which API changes in the upgraded dependencies affect classes actually "
                        + "used by the project. Returns machine-readable JSON.")
                .build();
        return new SyncToolSpecification(tool, this::handleUpgradeImpact);
    }

    private CallToolResult handleGenerateApiReport(McpSyncServerExchange exchange, CallToolRequest request) {
        Map<String, Object> args = arguments(request);
        String gavString = stringArg(args, "gav");
        if (isBlank(gavString)) {
            return validationError("Missing required argument 'gav'");
        }
        Gav gav;
        try {
            gav = Gav.parse(gavString);
        } catch (IllegalArgumentException e) {
            return validationError(e.getMessage());
        }
        List<String> repositories = stringListArg(args, "repositories");
        return execute(() -> apiReportRunner.run(gav, repositories));
    }

    private CallToolResult handleGenerateApiDiff(McpSyncServerExchange exchange, CallToolRequest request) {
        Map<String, Object> args = arguments(request);
        String gavString = stringArg(args, "gav");
        String oldVersion = stringArg(args, "oldVersion");
        String newVersion = stringArg(args, "newVersion");
        if (isBlank(gavString) || isBlank(oldVersion) || isBlank(newVersion)) {
            return validationError("Missing required argument: 'gav', 'oldVersion' and 'newVersion' are all "
                    + "required");
        }
        String[] ga;
        try {
            ga = splitGa(gavString);
        } catch (IllegalArgumentException e) {
            return validationError(e.getMessage());
        }
        List<String> repositories = stringListArg(args, "repositories");
        return execute(() -> apiDiffRunner.run(ga[0], ga[1], oldVersion, newVersion, repositories));
    }

    private CallToolResult handleUpgradeImpact(McpSyncServerExchange exchange, CallToolRequest request) {
        Map<String, Object> args = arguments(request);
        String project = stringArg(args, "project");
        String gavString = stringArg(args, "gav");
        if (isBlank(project) == isBlank(gavString)) {
            return validationError("Exactly one of 'project' or 'gav' must be given");
        }
        List<String> upgradeStrings = stringListArg(args, "upgrades");
        if (upgradeStrings.isEmpty()) {
            return validationError("Missing required argument 'upgrades'");
        }
        List<UpgradeSpec> upgrades;
        try {
            upgrades = upgradeStrings.stream().map(UpgradeSpec::parse).toList();
        } catch (IllegalArgumentException e) {
            return validationError(e.getMessage());
        }
        UpgradeRequest upgradeRequest;
        try {
            upgradeRequest = isBlank(project)
                    ? new UpgradeRequest(null, Gav.parse(gavString), upgrades)
                    : new UpgradeRequest(Path.of(project), null, upgrades);
        } catch (IllegalArgumentException e) {
            return validationError(e.getMessage());
        }
        List<String> repositories = stringListArg(args, "repositories");
        int threads = intArg(args, "threads", Runtime.getRuntime().availableProcessors());
        return execute(() -> upgradeImpactRunner.run(upgradeRequest, repositories, threads));
    }

    private static CallToolResult execute(Supplier<DiffReport> action) {
        try {
            DiffReport report = action.get();
            return CallToolResult.builder()
                    .content(List.of(new TextContent(JsonSupport.toJson(report))))
                    .build();
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private static CallToolResult errorResult(Exception e) {
        String message = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
        return CallToolResult.builder()
                .content(List.of(new TextContent(e.getClass().getName() + ": " + message)))
                .isError(true)
                .build();
    }

    private static CallToolResult validationError(String message) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(message)))
                .isError(true)
                .build();
    }

    private static Map<String, Object> arguments(CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        return args != null ? args : Map.of();
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof String s ? s : null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static List<String> stringListArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(item.toString());
            }
        }
        return result;
    }

    private static int intArg(Map<String, Object> args, String key, int defaultValue) {
        Object value = args.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static String[] splitGa(String gav) {
        if (gav == null || gav.isBlank()) {
            throw new IllegalArgumentException("GAV string must not be blank: " + gav);
        }
        String[] parts = gav.split(":", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid GAV '" + gav + "': expected 'groupId:artifactId', got " + parts.length + " segment(s)");
        }
        return parts;
    }

    private static ArtifactResolver buildResolver(List<String> repositories) {
        return new MavenArtifactResolver(RepositoryConfig.of(repositories, null));
    }

    private static JarComparator buildComparator() {
        String override = System.getenv(JAPICMP_JAR_ENV);
        Path japicmpJar = CliSupport.resolveJapicmpJarOrThrow(override != null ? Path.of(override) : null);
        JapicmpRunner runner = new JapicmpRunner(new ExternalToolRunner(), japicmpJar);
        return new JapicmpJarComparator(runner, CliSupport.createWorkDir());
    }

    private static ApiReportPipeline buildApiReportPipeline(List<String> repositories) {
        return new ApiReportPipeline(buildResolver(repositories), buildComparator());
    }

    private static ApiDiffPipeline buildApiDiffPipeline(List<String> repositories) {
        return new ApiDiffPipeline(buildResolver(repositories), buildComparator());
    }

    private static UpgradeImpactPipeline buildUpgradeImpactPipeline(List<String> repositories, int threads) {
        ArtifactResolver resolver = buildResolver(repositories);
        EffectivePomBuilder pomBuilder = new EffectivePomBuilder(resolver);
        ProjectScanner scanner = new ProjectScanner(pomBuilder);
        DependencyExtractor extractor = new DependencyExtractor(pomBuilder);
        JdepsRunner jdeps = new JdepsRunner(new ExternalToolRunner());
        return new UpgradeImpactPipeline(resolver, pomBuilder, scanner, extractor, jdeps, buildComparator(), threads);
    }

    private static Map<String, Object> generateApiReportSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("gav", stringProperty("Maven coordinate \"groupId:artifactId:version[:classifier]\""));
        properties.put("repositories", stringArrayProperty("Extra Maven repository URLs"));
        return objectSchema(properties, List.of("gav"));
    }

    private static Map<String, Object> generateApiDiffSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("gav", stringProperty("Maven coordinate \"groupId:artifactId\""));
        properties.put("oldVersion", stringProperty("Version to diff from"));
        properties.put("newVersion", stringProperty("Version to diff to"));
        properties.put("repositories", stringArrayProperty("Extra Maven repository URLs"));
        return objectSchema(properties, List.of("gav", "oldVersion", "newVersion"));
    }

    private static Map<String, Object> upgradeImpactSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", stringProperty("Path to a local Maven project"));
        properties.put("gav", stringProperty("Target jar GAV \"groupId:artifactId:version\"; exactly one of "
                + "'project'/'gav' must be given"));
        properties.put("upgrades", stringArrayProperty("Requested upgrades, entries \"groupId:artifactId=newVersion\""));
        properties.put("repositories", stringArrayProperty("Extra Maven repository URLs"));
        properties.put("threads", integerProperty("Number of worker threads"));
        return objectSchema(properties, List.of("upgrades"));
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required != null && !required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static Map<String, Object> stringProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private static Map<String, Object> stringArrayProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "array");
        property.put("items", Map.of("type", "string"));
        property.put("description", description);
        return property;
    }

    private static Map<String, Object> integerProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "integer");
        property.put("description", description);
        return property;
    }

    @FunctionalInterface
    interface ApiReportRunner {
        DiffReport run(Gav gav, List<String> repositories);
    }

    @FunctionalInterface
    interface ApiDiffRunner {
        DiffReport run(String groupId, String artifactId, String oldVersion, String newVersion,
                List<String> repositories);
    }

    @FunctionalInterface
    interface UpgradeImpactRunner {
        DiffReport run(UpgradeRequest request, List<String> repositories, int threads);
    }
}
