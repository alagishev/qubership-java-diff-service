package org.qubership.jdiff.model;

import java.util.List;

/**
 * API changes for a single artifact between an old and a new version.
 *
 * @param groupId       the artifact's group id
 * @param artifactId    the artifact id
 * @param oldVersion    the version being upgraded from
 * @param newVersion    the version being upgraded to
 * @param semverVerdict overall semver impact: {@code MAJOR|MINOR|PATCH|NONE}
 * @param changes       the individual API changes
 */
public record ArtifactReport(String groupId, String artifactId, String oldVersion, String newVersion,
                              String semverVerdict, List<ApiChange> changes) {
}
