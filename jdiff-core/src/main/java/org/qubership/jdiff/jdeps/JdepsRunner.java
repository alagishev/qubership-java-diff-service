package org.qubership.jdiff.jdeps;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.qubership.jdiff.tools.ExternalToolRunner;
import org.qubership.jdiff.tools.JdkTools;
import org.qubership.jdiff.tools.ToolExecutionException;
import org.qubership.jdiff.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@code jdeps -verbose:class} against a target jar and reports which classes of that jar
 * use which classes from which dependency jar.
 */
public class JdepsRunner {

    private static final Logger log = LoggerFactory.getLogger(JdepsRunner.class);

    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    /**
     * On Windows, {@code ProcessBuilder} still encodes the full command line (~8191 chars). When the
     * joined {@code -cp} list exceeds this, materialize jars under a temp directory and pass a tiny
     * manifest-only stub jar whose {@code Class-Path} header lists them (jdeps does not accept
     * {@code dir/*} on Windows).
     */
    private static final int WINDOWS_CLASSPATH_STUB_THRESHOLD = 7000;

    private final ExternalToolRunner runner;
    private final Path jdepsBinary;

    public JdepsRunner(ExternalToolRunner runner) {
        this(runner, JdkTools.binary("jdeps"));
    }

    public JdepsRunner(ExternalToolRunner runner, Path jdepsBinary) {
        this.runner = runner;
        this.jdepsBinary = jdepsBinary;
    }

    public Set<ClassUsage> analyze(Path targetJar, List<Path> dependencyJars) {
        Path workDir = null;
        try {
            ClasspathMaterialization materialization = materializeClasspath(dependencyJars);

            List<String> command = new ArrayList<>();
            command.add(jdepsBinary.toString());
            command.add("-verbose:class");
            command.add("--ignore-missing-deps");
            command.add("--multi-release");
            command.add("base");
            if (materialization.classpathArg() != null) {
                command.add("-cp");
                command.add(materialization.classpathArg());
            }
            command.add(targetJar.toString());

            workDir = materialization.workDir();
            ToolResult result = runner.run(command, null, TIMEOUT);
            if (!result.success()) {
                throw new ToolExecutionException("jdeps exited with code " + result.exitCode()
                        + " for command: " + String.join(" ", command) + "\nstderr:\n" + result.stderr());
            }

            Set<String> dependencyJarFileNames = dependencyJars.stream()
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
            Set<ClassUsage> usages = JdepsOutputParser.parse(result.stdout(), dependencyJarFileNames);

            log.debug("jdeps analyze: command={}, usageCount={}", command, usages.size());
            if (log.isTraceEnabled()) {
                usages.forEach(usage -> log.trace("ClassUsage: {}", usage));
            }
            return usages;
        } catch (IOException e) {
            throw new ToolExecutionException("Failed to prepare jdeps classpath for " + targetJar, e);
        } finally {
            if (workDir != null) {
                deleteRecursively(workDir);
            }
        }
    }

    private ClasspathMaterialization materializeClasspath(List<Path> dependencyJars) throws IOException {
        if (dependencyJars.isEmpty()) {
            return new ClasspathMaterialization(null, null);
        }
        String joined = dependencyJars.stream()
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator));
        if (!needsClasspathStub(joined)) {
            return new ClasspathMaterialization(joined, null);
        }

        Path workDir = Files.createTempDirectory("jdeps-cp-");
        List<String> entryNames = new ArrayList<>();
        for (int i = 0; i < dependencyJars.size(); i++) {
            String entryName = "dep-" + i + ".jar";
            Path linkTarget = workDir.resolve(entryName);
            linkOrCopy(dependencyJars.get(i), linkTarget);
            entryNames.add(entryName);
        }
        Path stubJar = workDir.resolve("classpath-stub.jar");
        writeClasspathStubJar(stubJar, entryNames);
        log.debug("jdeps classpath: {} jars materialized under {} (manifest stub)", dependencyJars.size(), workDir);
        return new ClasspathMaterialization(stubJar.toString(), workDir);
    }

    private static void linkOrCopy(Path source, Path target) throws IOException {
        try {
            Files.createLink(target, source.toAbsolutePath());
        } catch (IOException | UnsupportedOperationException ex) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeClasspathStubJar(Path stubJar, List<String> classPathEntries) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.CLASS_PATH, String.join(" ", classPathEntries));
        try (OutputStream out = Files.newOutputStream(stubJar);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(Attributes.Name.MANIFEST_VERSION + "/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            manifest.write(zip);
            zip.closeEntry();
        }
    }

    private static boolean needsClasspathStub(String joinedClasspath) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return false;
        }
        return joinedClasspath.length() > WINDOWS_CLASSPATH_STUB_THRESHOLD;
    }

    private static void deleteRecursively(Path root) {
        try {
            if (!Files.exists(root)) {
                return;
            }
            Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete {}: {}", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to clean up jdeps classpath dir {}: {}", root, e.getMessage());
        }
    }

    private record ClasspathMaterialization(String classpathArg, Path workDir) {
    }
}
