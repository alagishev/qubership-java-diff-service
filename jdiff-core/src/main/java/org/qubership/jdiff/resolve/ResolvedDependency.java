package org.qubership.jdiff.resolve;

import org.qubership.jdiff.model.Gav;

/**
 * A direct dependency of a Maven module, with its scope and effective version already resolved.
 */
public record ResolvedDependency(Gav gav, String scope, boolean optional) {
}
