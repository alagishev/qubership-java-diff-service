package org.qubership.jdiff.cli;

import org.qubership.jdiff.cli.mcp.JdiffMcpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Runs jdiff as a local MCP server over stdio, exposing {@code generate_api_report},
 * {@code generate_api_diff} and {@code upgrade_impact} tools.
 */
@Command(name = "mcp-server", description = "Run jdiff as a local MCP server.")
public class McpServerCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(McpServerCommand.class);

    @Option(names = "--repo", description = "Maven repository as URL or id=url (repeatable). Default: Maven Central. "
            + "Ids must match <server><id> in --settings.")
    List<String> repo = new ArrayList<>();

    @Option(names = "--settings", description = "Path to a Maven settings.xml (credentials/local repo for the "
            + "whole MCP process).")
    Path settings;

    @Override
    public Integer call() {
        try {
            new JdiffMcpServer(settings, repo).run();
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("MCP server interrupted");
            return 0;
        } catch (Exception e) {
            LOG.error("MCP server failed: {}", e.getMessage());
            LOG.debug("Unexpected error running mcp-server", e);
            return 1;
        }
    }
}
