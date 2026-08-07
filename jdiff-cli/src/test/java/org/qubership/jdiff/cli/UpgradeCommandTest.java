package org.qubership.jdiff.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.jdiff.upgrade.UpgradeSpec;

class UpgradeCommandTest {

    @Test
    void bothProjectAndGavGivenExitsWithCodeTwo() {
        UpgradeCommand command = new UpgradeCommand();
        command.project = Path.of("some/project");
        command.gav = "org.example:app:1.0.0";
        command.upgrade = List.of("org.example:lib-a=2.0.0");
        command.outputOptions = new OutputOptions();

        assertExitCodeTwo(command, "exactly one");
    }

    @Test
    void neitherProjectNorGavGivenExitsWithCodeTwo() {
        UpgradeCommand command = new UpgradeCommand();
        command.upgrade = List.of("org.example:lib-a=2.0.0");
        command.outputOptions = new OutputOptions();

        assertExitCodeTwo(command, "exactly one");
    }

    @Test
    void noUpgradesGivenExitsWithCodeTwo() {
        UpgradeCommand command = new UpgradeCommand();
        command.gav = "org.example:app:1.0.0";
        command.outputOptions = new OutputOptions();

        assertExitCodeTwo(command, "at least one");
    }

    @Test
    void upgradesFileParsingIgnoresCommentsAndBlankLinesMergesWithRepeatableOptionAndDedupes(@TempDir Path tempDir)
            throws IOException {
        Path upgradesFile = tempDir.resolve("upgrades.txt");
        Files.writeString(upgradesFile, "# a comment\n\norg.example:lib-a=2.0.0\n   \norg.example:lib-b=3.0.0\n"
                + "org.example:lib-c=4.0.0\n");

        UpgradeCommand command = new UpgradeCommand();
        // lib-c and lib-a are duplicated between --upgrade and --upgrades-file: each must survive once,
        // at its first-encountered position.
        command.upgrade = List.of("org.example:lib-c=4.0.0", "org.example:lib-a=2.0.0");
        command.upgradesFile = upgradesFile;

        List<UpgradeSpec> specs = command.parseUpgrades();

        assertThat(specs).containsExactly(
                UpgradeSpec.parse("org.example:lib-c=4.0.0"),
                UpgradeSpec.parse("org.example:lib-a=2.0.0"),
                UpgradeSpec.parse("org.example:lib-b=3.0.0"));
    }

    @Test
    void bareProjectPomFilenameNormalizesToCurrentDirectory() {
        Path bareProjectPom = Path.of("pom.xml");
        assumeTrue(Files.isRegularFile(bareProjectPom), "expected a pom.xml in the working directory");

        assertThat(UpgradeCommand.normalizeProjectDir(bareProjectPom)).isEqualTo(Path.of("."));
    }

    @Test
    void projectDirectoryIsPassedThroughUnchanged(@TempDir Path tempDir) {
        assertThat(UpgradeCommand.normalizeProjectDir(tempDir)).isEqualTo(tempDir);
    }

    @Test
    void projectPomFileWithParentNormalizesToItsParentDirectory(@TempDir Path tempDir) throws IOException {
        Path pomFile = Files.createFile(tempDir.resolve("pom.xml"));

        assertThat(UpgradeCommand.normalizeProjectDir(pomFile)).isEqualTo(tempDir);
    }

    private static void assertExitCodeTwo(UpgradeCommand command, String expectedMessageFragment) {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            Integer exitCode = command.call();

            assertThat(exitCode).isEqualTo(2);
            assertThat(captured.toString()).containsIgnoringCase(expectedMessageFragment);
        } finally {
            System.setErr(originalErr);
        }
    }
}
