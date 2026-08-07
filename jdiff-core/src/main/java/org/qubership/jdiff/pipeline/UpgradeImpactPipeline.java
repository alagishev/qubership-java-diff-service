package org.qubership.jdiff.pipeline;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.qubership.jdiff.jdeps.ClassUsage;
import org.qubership.jdiff.jdeps.JdepsRunner;
import org.qubership.jdiff.japicmp.JapicmpResult;
import org.qubership.jdiff.model.ApiChange;
import org.qubership.jdiff.model.ArtifactReport;
import org.qubership.jdiff.model.DiffReport;
import org.qubership.jdiff.model.Gav;
import org.qubership.jdiff.model.ReportMode;
import org.qubership.jdiff.resolve.ArtifactResolutionException;
import org.qubership.jdiff.resolve.ArtifactResolver;
import org.qubership.jdiff.resolve.DependencyExtractor;
import org.qubership.jdiff.resolve.EffectivePomBuilder;
import org.qubership.jdiff.resolve.EffectivePomBuilder.BuildOutcome;
import org.qubership.jdiff.resolve.MavenModule;
import org.qubership.jdiff.resolve.ProjectScanner;
import org.qubership.jdiff.resolve.ResolvedDependency;
import org.qubership.jdiff.upgrade.BomUpgradeExpander;
import org.qubership.jdiff.upgrade.ImpactAnalyzer;
import org.qubership.jdiff.upgrade.UpgradeItem;
import org.qubership.jdiff.upgrade.UpgradeMatcher;
import org.qubership.jdiff.upgrade.UpgradeSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Produces the upgrade-impact report (mode: {@code upgrade-impact}): resolves the target modules'
 * dependencies, matches requested upgrades against them (directly, or via BOM expansion), analyzes
 * class-level usage of the affected dependencies via jdeps, diffs the affected dependencies via
 * japicmp, and keeps only the API changes actually used by a module.
 */
public class UpgradeImpactPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(UpgradeImpactPipeline.class);

    private static final Set<String> MODULE_PACKAGINGS = Set.of("jar", "bundle");
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    private final ArtifactResolver resolver;
    private final EffectivePomBuilder pomBuilder;
    private final ProjectScanner scanner;
    private final DependencyExtractor extractor;
    private final JdepsRunner jdeps;
    private final JarComparator comparator;
    private final int threads;
    private final UpgradeMatcher matcher = new UpgradeMatcher();
    private final BomUpgradeExpander bomExpander;

    public UpgradeImpactPipeline(ArtifactResolver resolver, EffectivePomBuilder pomBuilder, ProjectScanner scanner,
            DependencyExtractor extractor, JdepsRunner jdeps, JarComparator comparator, int threads) {
        this.resolver = resolver;
        this.pomBuilder = pomBuilder;
        this.scanner = scanner;
        this.extractor = extractor;
        this.jdeps = jdeps;
        this.comparator = comparator;
        this.threads = threads;
        this.bomExpander = new BomUpgradeExpander(pomBuilder);
    }

    /**
     * @param request the target (project or single artifact) and the requested upgrades
     * @return the assembled {@link DiffReport}
     */
    public DiffReport run(UpgradeRequest request) {
        Object target = request.projectDir() != null ? request.projectDir() : request.targetGav();
        LOG.info("Starting upgrade-impact analysis for {} with {} requested upgrade(s)", target,
                request.upgrades().size());

        List<ModuleTarget> moduleTargets = discoverModuleTargets(request);

        List<AnalyzedModule> analyzedModules = new ArrayList<>();
        List<UpgradeItem> matchedItems = new ArrayList<>();
        Set<String> matchedSpecGas = new HashSet<>();
        List<String> modulesAnalyzed = new ArrayList<>();

        for (ModuleTarget moduleTarget : moduleTargets) {
            Path moduleJar = resolveModuleJar(moduleTarget);
            if (moduleJar == null) {
                continue;
            }

            BuildOutcome outcome = buildOutcome(moduleTarget);
            List<ResolvedDependency> directDeps = extractor.directDependencies(outcome.effective());
            LOG.debug("Module {} direct dependencies: {}", moduleTarget.gav(), directDeps);

            UpgradeMatcher.MatchResult matchResult = matcher.match(request.upgrades(), directDeps);
            matchedItems.addAll(matchResult.direct());
            matchResult.direct().forEach(item -> matchedSpecGas.add(item.ga()));

            for (UpgradeSpec spec : matchResult.unmatchedSpecs()) {
                List<UpgradeItem> expanded = tryExpandBom(spec, outcome, moduleTarget.gav(), directDeps);
                if (expanded != null) {
                    matchedItems.addAll(expanded);
                    matchedSpecGas.add(spec.ga());
                }
            }

            analyzedModules.add(new AnalyzedModule(moduleTarget.gav(), moduleJar, directDeps));
            modulesAnalyzed.add(moduleTarget.gav().toString());
        }

        List<UpgradeSpec> unmatchedSpecs = request.upgrades().stream()
                .filter(spec -> !matchedSpecGas.contains(spec.ga()))
                .toList();
        unmatchedSpecs.forEach(spec ->
                LOG.warn("Requested upgrade {} matches no dependency", specToString(spec)));

        List<UpgradeItem> distinctItems = matchedItems.stream().distinct().toList();

        Map<String, Set<ClassUsage>> usageByModule = analyzeUsage(analyzedModules);
        Map<UpgradeItem, ComparisonOutcome> comparisons = compareUpgrades(distinctItems);

        List<ArtifactReport> artifactReports = new ArrayList<>();
        int totalChanges = 0;
        int impactedChanges = 0;
        for (UpgradeItem item : distinctItems) {
            ComparisonOutcome outcome = comparisons.get(item);
            List<ApiChange> changes = outcome.result().changes();
            List<ApiChange> impacted = ImpactAnalyzer.impacted(changes, usageByModule, outcome.oldJarFileName());
            totalChanges += changes.size();
            impactedChanges += impacted.size();
            LOG.info("Upgrade {} {} -> {}: {}/{} change(s) impacted", item.ga(), item.oldVersion(), item.newVersion(),
                    impacted.size(), changes.size());
            artifactReports.add(new ArtifactReport(item.groupId(), item.artifactId(), item.oldVersion(),
                    item.newVersion(), outcome.result().semverVerdict(), impacted));
        }

        Map<String, Object> input = new LinkedHashMap<>();
        if (request.projectDir() != null) {
            input.put("project", request.projectDir().toString());
        } else {
            input.put("gav", request.targetGav().toString());
        }
        input.put("requestedUpgrades", request.upgrades().stream().map(UpgradeImpactPipeline::specToString).toList());
        input.put("unmatchedUpgrades", unmatchedSpecs.stream().map(UpgradeImpactPipeline::specToString).toList());
        input.put("modulesAnalyzed", modulesAnalyzed);
        input.put("totalChanges", totalChanges);
        input.put("impactedChanges", impactedChanges);

        return DiffReport.create(ReportMode.UPGRADE_IMPACT, input, artifactReports);
    }

    private List<ModuleTarget> discoverModuleTargets(UpgradeRequest request) {
        if (request.projectDir() != null) {
            List<MavenModule> scanned = scanner.scan(request.projectDir());
            List<ModuleTarget> targets = new ArrayList<>();
            for (MavenModule module : scanned) {
                if (!MODULE_PACKAGINGS.contains(module.packaging())) {
                    continue;
                }
                targets.add(new ModuleTarget(module.gav(), module.pomFile(), module.targetJar()));
            }
            return targets;
        }
        return List.of(new ModuleTarget(request.targetGav(), null, null));
    }

    private Path resolveModuleJar(ModuleTarget moduleTarget) {
        try {
            return resolver.resolveJar(moduleTarget.gav());
        } catch (ArtifactResolutionException e) {
            if (moduleTarget.targetJarFallback() != null) {
                LOG.warn("Could not resolve jar for {} ({}), using locally built jar {}", moduleTarget.gav(),
                        e.getMessage(), moduleTarget.targetJarFallback());
                return moduleTarget.targetJarFallback();
            }
            LOG.warn("Could not resolve jar for {} and no locally built jar is available, skipping module: {}",
                    moduleTarget.gav(), e.getMessage());
            return null;
        }
    }

    private BuildOutcome buildOutcome(ModuleTarget moduleTarget) {
        return moduleTarget.pomFile() != null
                ? pomBuilder.buildFull(moduleTarget.pomFile())
                : pomBuilder.buildFull(moduleTarget.gav());
    }

    private List<UpgradeItem> tryExpandBom(UpgradeSpec spec, BuildOutcome outcome, Gav moduleGav,
            List<ResolvedDependency> directDeps) {
        Dependency bomDependency = findImportBom(outcome.rawLineage(), spec.groupId(), spec.artifactId());
        if (bomDependency == null) {
            return null;
        }
        String oldVersion = interpolate(bomDependency.getVersion(), outcome.effective());
        if (oldVersion == null) {
            return null;
        }
        Gav oldBom = new Gav(spec.groupId(), spec.artifactId(), oldVersion, null);
        List<UpgradeItem> expanded = bomExpander.expand(oldBom, spec.newVersion(), directDeps);
        LOG.info("Expanded BOM upgrade {} -> {} for module {}: {} dependency upgrade(s)", oldBom, spec.newVersion(),
                moduleGav, expanded.size());
        if (expanded.isEmpty()) {
            LOG.warn("Upgraded BOM {} -> {} manages no direct dependency of module {}", oldBom, spec.newVersion(),
                    moduleGav);
        }
        return expanded;
    }

    /**
     * Searches the module's raw model lineage (own model first, then ancestors, nearest first) for an
     * import-scoped {@code dependencyManagement} entry matching {@code groupId:artifactId}. Real Maven
     * projects commonly declare and inherit import-scoped BOMs from a parent POM rather than the
     * module's own, so the whole lineage must be searched, not just the module's own raw model.
     */
    private static Dependency findImportBom(List<Model> rawLineage, String groupId, String artifactId) {
        for (Model rawModel : rawLineage) {
            DependencyManagement management = rawModel.getDependencyManagement();
            if (management == null) {
                continue;
            }
            for (Dependency dep : management.getDependencies()) {
                if ("import".equals(dep.getScope()) && groupId.equals(dep.getGroupId())
                        && artifactId.equals(dep.getArtifactId())) {
                    return dep;
                }
            }
        }
        return null;
    }

    /**
     * Resolves {@code ${...}} placeholders in {@code value} against the effective model's properties
     * (which already include properties inherited from ancestors).
     *
     * @return the interpolated value, or {@code null} if a referenced property cannot be resolved (in
     *         which case a WARN is logged and the caller should treat the upgrade as unmatched for
     *         this module rather than aborting the whole run)
     */
    private static String interpolate(String value, Model effectiveModel) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        Matcher propertyMatcher = PROPERTY_REFERENCE.matcher(value);
        StringBuilder result = new StringBuilder();
        while (propertyMatcher.find()) {
            String propertyName = propertyMatcher.group(1);
            String replacement = effectiveModel.getProperties().getProperty(propertyName);
            if (replacement == null && ("project.version".equals(propertyName) || "version".equals(propertyName))) {
                replacement = effectiveModel.getVersion();
            }
            if (replacement == null) {
                LOG.warn("Cannot resolve property '{}' referenced in BOM version '{}' of module {}, "
                        + "skipping this upgrade for this module", propertyName, value, effectiveModel.getId());
                return null;
            }
            propertyMatcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        propertyMatcher.appendTail(result);
        return result.toString();
    }

    private Map<String, Set<ClassUsage>> analyzeUsage(List<AnalyzedModule> modules) {
        if (modules.isEmpty()) {
            return Map.of();
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Map<String, Future<Set<ClassUsage>>> futures = new LinkedHashMap<>();
            for (AnalyzedModule module : modules) {
                futures.put(module.gav().ga(), pool.submit(() -> {
                    List<Path> dependencyJars = resolveDependencyJars(module);
                    Set<ClassUsage> usages = jdeps.analyze(module.jar(), dependencyJars);
                    LOG.debug("Module {} usage-set size: {}", module.gav(), usages.size());
                    return usages;
                }));
            }
            Map<String, Set<ClassUsage>> result = new LinkedHashMap<>();
            for (Map.Entry<String, Future<Set<ClassUsage>>> entry : futures.entrySet()) {
                result.put(entry.getKey(), await(entry.getValue(), futures.values(),
                        "jdeps analysis failed for module " + entry.getKey()));
            }
            return result;
        } finally {
            pool.shutdown();
        }
    }

    private List<Path> resolveDependencyJars(AnalyzedModule module) {
        List<Path> jars = new ArrayList<>();
        for (ResolvedDependency dep : module.directDeps()) {
            try {
                jars.add(resolver.resolveJar(dep.gav()));
            } catch (ArtifactResolutionException e) {
                LOG.warn("Could not resolve dependency jar {} of module {}, excluding it from usage analysis: {}",
                        dep.gav(), module.gav(), e.getMessage());
            }
        }
        return jars;
    }

    private Map<UpgradeItem, ComparisonOutcome> compareUpgrades(List<UpgradeItem> items) {
        if (items.isEmpty()) {
            return Map.of();
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Map<UpgradeItem, Future<ComparisonOutcome>> futures = new LinkedHashMap<>();
            for (UpgradeItem item : items) {
                futures.put(item, pool.submit(() -> {
                    Gav oldGav = new Gav(item.groupId(), item.artifactId(), item.oldVersion(), null);
                    Gav newGav = new Gav(item.groupId(), item.artifactId(), item.newVersion(), null);
                    Path oldJar = resolver.resolveJar(oldGav);
                    Path newJar = resolver.resolveJar(newGav);
                    JapicmpResult result = comparator.compare(oldJar, newJar, false);
                    return new ComparisonOutcome(result, oldJar.getFileName().toString());
                }));
            }
            Map<UpgradeItem, ComparisonOutcome> result = new LinkedHashMap<>();
            for (Map.Entry<UpgradeItem, Future<ComparisonOutcome>> entry : futures.entrySet()) {
                result.put(entry.getKey(), await(entry.getValue(), futures.values(),
                        "API diff failed for upgrade " + entry.getKey().ga()));
            }
            return result;
        } finally {
            pool.shutdown();
        }
    }

    private static <T> T await(Future<T> future, Collection<? extends Future<?>> siblings, String failureMessage) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOG.error("{}: {}", failureMessage, cause.getMessage());
            siblings.forEach(sibling -> sibling.cancel(true));
            throw new RuntimeException(failureMessage, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            siblings.forEach(sibling -> sibling.cancel(true));
            throw new RuntimeException(failureMessage + " (interrupted)", e);
        }
    }

    private static String specToString(UpgradeSpec spec) {
        return spec.groupId() + ":" + spec.artifactId() + "=" + spec.newVersion();
    }

    private record ModuleTarget(Gav gav, Path pomFile, Path targetJarFallback) {
    }

    private record AnalyzedModule(Gav gav, Path jar, List<ResolvedDependency> directDeps) {
    }

    private record ComparisonOutcome(JapicmpResult result, String oldJarFileName) {
    }
}
