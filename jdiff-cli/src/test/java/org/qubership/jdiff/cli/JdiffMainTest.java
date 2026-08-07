package org.qubership.jdiff.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class JdiffMainTest {

    @Test
    void helpListsAllSubcommandsAndReturnsZero() {
        CommandLine commandLine = new CommandLine(new JdiffMain());
        StringWriter out = new StringWriter();
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("--help");

        assertThat(exitCode).isZero();
        String usage = out.toString();
        assertThat(usage).contains("upgrade");
        assertThat(usage).contains("api-report");
        assertThat(usage).contains("api-diff");
        assertThat(usage).contains("mcp-server");
    }

    @Test
    void apiReportWithoutJapicmpJarReturnsExitCodeTwo() {
        CommandLine commandLine = new CommandLine(new JdiffMain());

        int exitCode = commandLine.execute("api-report", "--gav", "g:a:1.0");

        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void apiDiffWithoutRequiredOptionsFailsWithUsageError() {
        CommandLine commandLine = new CommandLine(new JdiffMain());
        StringWriter err = new StringWriter();
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute("api-diff", "--gav", "g:a");

        assertThat(exitCode).isNotZero();
        assertThat(err.toString()).containsIgnoringCase("--old");
    }
}
