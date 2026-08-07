package org.qubership.jdiff.upgrade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.qubership.jdiff.model.Gav;
import org.qubership.jdiff.resolve.EffectivePomBuilder;
import org.qubership.jdiff.resolve.ResolvedDependency;

/**
 * Expands an upgrade of an import-scoped BOM into the individual dependency upgrades it implies for a
 * module: every direct dependency of the module that the old BOM manages, whose version the new BOM
 * changes.
 */
public class BomUpgradeExpander {

    private final EffectivePomBuilder pomBuilder;

    public BomUpgradeExpander(EffectivePomBuilder pomBuilder) {
        this.pomBuilder = pomBuilder;
    }

    /**
     * @param oldBom            coordinate (with concrete version) of the BOM currently in use
     * @param newBomVersion     the version to upgrade the BOM to
     * @param moduleDirectDeps  the module's direct dependencies
     * @return one {@link UpgradeItem} per direct dependency managed by {@code oldBom} whose version
     *         the new BOM changes; empty if the new BOM manages none of the module's direct dependencies
     */
    public List<UpgradeItem> expand(Gav oldBom, String newBomVersion, List<ResolvedDependency> moduleDirectDeps) {
        Map<String, String> oldManaged = managedVersions(oldBom);
        Gav newBom = new Gav(oldBom.groupId(), oldBom.artifactId(), newBomVersion, null);
        Map<String, String> newManaged = managedVersions(newBom);

        List<UpgradeItem> items = new ArrayList<>();
        for (ResolvedDependency dep : moduleDirectDeps) {
            String ga = dep.gav().ga();
            String oldManagedVersion = oldManaged.get(ga);
            if (oldManagedVersion == null) {
                continue;
            }
            String newManagedVersion = newManaged.get(ga);
            if (newManagedVersion != null && !newManagedVersion.equals(oldManagedVersion)) {
                items.add(new UpgradeItem(dep.gav().groupId(), dep.gav().artifactId(), oldManagedVersion, newManagedVersion));
            }
        }
        return items;
    }

    private Map<String, String> managedVersions(Gav bomGav) {
        Model effective = pomBuilder.build(bomGav);
        Map<String, String> managed = new HashMap<>();
        DependencyManagement management = effective.getDependencyManagement();
        if (management != null) {
            for (Dependency dep : management.getDependencies()) {
                managed.put(dep.getGroupId() + ":" + dep.getArtifactId(), dep.getVersion());
            }
        }
        return managed;
    }
}
