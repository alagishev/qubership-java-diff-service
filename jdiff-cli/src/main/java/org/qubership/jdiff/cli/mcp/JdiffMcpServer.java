package org.qubership.jdiff.cli.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds and runs a synchronous MCP server over stdio, exposing jdiff's three analysis pipelines as
 * MCP tools (see {@link McpTools}). CRITICAL: stdout carries only the MCP JSON-RPC protocol; all
 * logging goes to stderr (see {@code logback.xml}).
 */
public class JdiffMcpServer {

    private static final Logger LOG = LoggerFactory.getLogger(JdiffMcpServer.class);
    private static final String FALLBACK_VERSION = "dev";

    private final McpTools tools;

    public JdiffMcpServer() {
        this(McpTools.createDefault());
    }

    /**
     * @param settingsXml optional Maven {@code settings.xml} for the process; may be {@code null}
     * @param repoTokens  process-wide {@code --repo} tokens ({@code id=url} or bare URL); may be empty
     */
    public JdiffMcpServer(Path settingsXml, List<String> repoTokens) {
        this(McpTools.createDefault(settingsXml, repoTokens));
    }

    JdiffMcpServer(McpTools tools) {
        this.tools = tools;
    }

    /**
     * Builds the server and blocks the calling thread until the process is asked to shut down: either
     * stdin reaches EOF (the client closed its end, e.g. its process exited) or the JVM receives an
     * external termination signal (process kill/Ctrl-C).
     *
     * <p>stdin EOF is detected by decorating the {@link InputStream} handed to
     * {@link StdioServerTransportProvider} (see {@link EofWatchingInputStream}) rather than by racing a
     * separate thread against the transport's own {@code stdio-inbound} reader thread for bytes off
     * {@code System.in}: the transport reads that single stream itself (line by line, on its own
     * thread), so a second, independent reader thread blocked on {@code System.in.read()} would steal
     * bytes out from under it and corrupt the JSON-RPC message framing. Decorating the stream instead
     * observes the exact same reads the transport already performs, so EOF (or a closed-stream
     * {@link IOException}) is detected with no extra thread and no risk of stealing protocol bytes.
     *
     * @throws InterruptedException if interrupted while waiting for shutdown
     */
    public void run() throws InterruptedException {
        CountDownLatch shutdownLatch = new CountDownLatch(1);
        InputStream stdin = new EofWatchingInputStream(System.in, () -> {
            LOG.info("stdin closed; shutting down jdiff MCP server");
            shutdownLatch.countDown();
        });

        StdioServerTransportProvider transportProvider =
                new StdioServerTransportProvider(McpJsonDefaults.getMapper(), stdin, System.out);
        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo("jdiff", toolVersion())
                .capabilities(ServerCapabilities.builder().tools(true).logging().build())
                .tools(tools.generateApiReportTool(), tools.generateApiDiffTool(), tools.upgradeImpactTool())
                .build();

        LOG.info("jdiff MCP server started on stdio (toolVersion={})", toolVersion());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down jdiff MCP server");
            server.closeGracefully();
            shutdownLatch.countDown();
        }, "jdiff-mcp-shutdown"));

        shutdownLatch.await();
        // Idempotent: a no-op if the shutdown hook (or the transport itself, which already closes its
        // own session on EOF) already closed the session; required if stdin EOF was the only signal.
        server.closeGracefully();
    }

    /**
     * Wraps an {@link InputStream} and invokes {@code onEof} exactly once, the first time a read
     * observes end-of-stream (-1) or a closed-stream {@link IOException}, before propagating the
     * result/exception unchanged. All reads are delegated as-is, so wrapping is fully transparent to
     * whatever single reader consumes the stream.
     */
    private static final class EofWatchingInputStream extends FilterInputStream {

        private final Runnable onEof;
        private final AtomicBoolean fired = new AtomicBoolean();

        EofWatchingInputStream(InputStream in, Runnable onEof) {
            super(in);
            this.onEof = onEof;
        }

        @Override
        public int read() throws IOException {
            try {
                int b = super.read();
                if (b == -1) {
                    fireOnce();
                }
                return b;
            } catch (IOException e) {
                fireOnce();
                throw e;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                int n = super.read(b, off, len);
                if (n == -1) {
                    fireOnce();
                }
                return n;
            } catch (IOException e) {
                fireOnce();
                throw e;
            }
        }

        private void fireOnce() {
            if (fired.compareAndSet(false, true)) {
                onEof.run();
            }
        }
    }

    private static String toolVersion() {
        try (InputStream in = JdiffMcpServer.class.getResourceAsStream("/jdiff.properties")) {
            if (in == null) {
                return FALLBACK_VERSION;
            }
            Properties props = new Properties();
            props.load(in);
            String version = props.getProperty("version");
            if (version == null || version.isBlank() || version.contains("${")) {
                return FALLBACK_VERSION;
            }
            return version;
        } catch (IOException e) {
            return FALLBACK_VERSION;
        }
    }
}
