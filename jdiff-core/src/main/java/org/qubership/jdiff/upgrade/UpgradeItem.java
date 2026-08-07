package org.qubership.jdiff.upgrade;

/**
 * A resolved dependency upgrade: a concrete {@code groupId:artifactId} moving from {@code oldVersion}
 * to {@code newVersion}, whether matched directly or via BOM expansion.
 *
 * @param groupId    the artifact's group id
 * @param artifactId the artifact id
 * @param oldVersion the version currently in use
 * @param newVersion the version to upgrade to
 */
public record UpgradeItem(String groupId, String artifactId, String oldVersion, String newVersion) {

    /**
     * @return {@code groupId:artifactId}
     */
    public String ga() {
        return groupId + ":" + artifactId;
    }
}
