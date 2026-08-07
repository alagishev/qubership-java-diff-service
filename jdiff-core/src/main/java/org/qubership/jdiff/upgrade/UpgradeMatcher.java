package org.qubership.jdiff.upgrade;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.qubership.jdiff.resolve.ResolvedDependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Matches requested upgrades against a module's direct dependencies by {@code groupId:artifactId}.
 */
public class UpgradeMatcher {

    private static final Logger LOG = LoggerFactory.getLogger(UpgradeMatcher.class);

    /**
     * @param specs            requested upgrades
     * @param moduleDirectDeps the module's direct dependencies
     * @return the specs that directly matched a dependency (old version taken from the dependency),
     *         plus the specs that matched none of the module's direct dependencies (candidates for
     *         BOM matching, or eventually unmatched)
     */
    public MatchResult match(List<UpgradeSpec> specs, List<ResolvedDependency> moduleDirectDeps) {
        Map<String, ResolvedDependency> byGa = moduleDirectDeps.stream()
                .collect(Collectors.toMap(dep -> dep.gav().ga(), dep -> dep, (first, second) -> first));

        List<UpgradeItem> direct = new ArrayList<>();
        List<UpgradeSpec> unmatched = new ArrayList<>();
        for (UpgradeSpec spec : specs) {
            ResolvedDependency dep = byGa.get(spec.ga());
            if (dep == null) {
                unmatched.add(spec);
                continue;
            }
            UpgradeItem item = new UpgradeItem(spec.groupId(), spec.artifactId(), dep.gav().version(), spec.newVersion());
            LOG.info("Matched direct dependency upgrade {}: {} -> {}", spec.ga(), item.oldVersion(), item.newVersion());
            direct.add(item);
        }
        return new MatchResult(direct, unmatched);
    }

    /**
     * @param direct         upgrades that matched a direct dependency of the module
     * @param unmatchedSpecs requested upgrades that matched none of the module's direct dependencies
     */
    public record MatchResult(List<UpgradeItem> direct, List<UpgradeSpec> unmatchedSpecs) {
    }
}
