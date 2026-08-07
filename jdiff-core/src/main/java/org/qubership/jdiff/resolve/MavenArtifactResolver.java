package org.qubership.jdiff.resolve;

import java.nio.file.Path;
import java.util.List;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.qubership.jdiff.model.Gav;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves jars and POMs by GAV using the Maven Resolver (Aether) libraries, downloading into
 * (and reusing artifacts already cached in) the local repository configured via {@link RepositoryConfig}.
 */
public class MavenArtifactResolver implements ArtifactResolver {

    private static final Logger LOG = LoggerFactory.getLogger(MavenArtifactResolver.class);

    private final RepositorySystem repositorySystem;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> remoteRepositories;

    public MavenArtifactResolver(RepositoryConfig config) {
        this.repositorySystem = new RepositorySystemSupplier().get();
        this.session = newSession(repositorySystem, config.localRepository());
        this.remoteRepositories = config.repositories().stream()
                .map(repo -> {
                    RemoteRepository.Builder builder =
                            new RemoteRepository.Builder(repo.id(), "default", repo.url());
                    ServerCredentials credentials = config.serverCredentials().get(repo.id());
                    if (credentials != null) {
                        builder.setAuthentication(toAuthentication(credentials));
                    }
                    return builder.build();
                })
                .toList();
    }

    private static Authentication toAuthentication(ServerCredentials credentials) {
        return new AuthenticationBuilder()
                .addUsername(credentials.username())
                .addPassword(credentials.password())
                .build();
    }

    private static RepositorySystemSession newSession(RepositorySystem system, Path localRepository) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        LocalRepository localRepo = new LocalRepository(localRepository.toFile());
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
        return session;
    }

    @Override
    public Path resolveJar(Gav gav) {
        return resolve(gav, "jar");
    }

    @Override
    public Path resolvePom(Gav gav) {
        return resolve(gav, "pom");
    }

    private Path resolve(Gav gav, String extension) {
        Artifact artifact = new DefaultArtifact(
                gav.groupId(), gav.artifactId(), gav.classifier(), extension, gav.version());
        ArtifactRequest request = new ArtifactRequest(artifact, remoteRepositories, null);
        try {
            ArtifactResult result = repositorySystem.resolveArtifact(session, request);
            Path path = result.getArtifact().getFile().toPath();
            LOG.debug("Resolved {} -> {}", gav, path);
            return path;
        } catch (org.eclipse.aether.resolution.ArtifactResolutionException e) {
            throw new ArtifactResolutionException("Failed to resolve " + extension + " for " + gav, e);
        }
    }
}
