package org.qubership.jdiff.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class McpServerCommandTest {

    @Test
    void parsesSettingsAndNamedRepoOptions() {
        CommandLine root = new CommandLine(new JdiffMain());

        root.parseArgs(
                "mcp-server",
                "--settings", "demo/work/github-settings.xml",
                "--repo", "central=https://repo1.maven.org/maven2/",
                "--repo", "github=https://maven.pkg.github.com/Netcracker/*");

        McpServerCommand command = root.getSubcommands().get("mcp-server").getCommand();
        assertThat(command.settings).isEqualTo(Path.of("demo/work/github-settings.xml"));
        assertThat(command.repo).containsExactly(
                "central=https://repo1.maven.org/maven2/",
                "github=https://maven.pkg.github.com/Netcracker/*");
    }
}
