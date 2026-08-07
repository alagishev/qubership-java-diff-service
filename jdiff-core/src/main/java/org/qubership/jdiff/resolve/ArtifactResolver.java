package org.qubership.jdiff.resolve;

import java.nio.file.Path;
import org.qubership.jdiff.model.Gav;

/**
 * Resolves Maven artifacts (jars and POMs) by coordinate to a local file.
 */
public interface ArtifactResolver {

    /**
     * @param gav coordinate of the jar to resolve
     * @return the local path of the resolved jar
     * @throws ArtifactResolutionException if the jar cannot be resolved
     */
    Path resolveJar(Gav gav);

    /**
     * @param gav coordinate of the POM to resolve
     * @return the local path of the resolved POM
     * @throws ArtifactResolutionException if the POM cannot be resolved
     */
    Path resolvePom(Gav gav);
}
