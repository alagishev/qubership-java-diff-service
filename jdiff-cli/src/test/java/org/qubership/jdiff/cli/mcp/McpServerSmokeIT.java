package org.qubership.jdiff.cli.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.qubership.jdiff.model.JsonSupport;
import org.qubership.jdiff.tools.JdkTools;

/**
 * Starts the fat jar as a real subprocess in {@code mcp-server} mode and speaks minimal JSON-RPC over
 * its stdin/stdout: {@code initialize}, then {@code tools/list}. Needs no network. Run once with
 * {@code -Djdiff.it=true} after {@code mvn -q package -DskipTests}.
 */
@EnabledIfSystemProperty(named = "jdiff.it", matches = "true")
class McpServerSmokeIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private Process process;

    @AfterEach
    void tearDown() {
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }

    @Test
    @Timeout(30)
    void initializeAndToolsListSucceedOverRealStdio() throws Exception {
        Path fatJar = findFatJar();
        assumeTrue(fatJar != null, "Fat jar not found under jdiff-cli/target; run 'mvn -q package -DskipTests' first");

        process = new ProcessBuilder(JdkTools.binary("java").toString(), "-jar", fatJar.toString(), "mcp-server")
                .redirectErrorStream(false)
                .start();

        LinkedBlockingQueue<String> stdoutLines = new LinkedBlockingQueue<>();
        Thread stdoutReader = startLineReader(process.getInputStream(), stdoutLines);
        // Drain stderr so the process never blocks on a full pipe; logging there is expected.
        Thread stderrDrain = startLineReader(process.getErrorStream(), new LinkedBlockingQueue<>());

        try {
            // Nothing must reach stdout before the client sends its first request.
            assertThat(pollLine(stdoutLines, Duration.ofMillis(500)))
                    .as("no stray output must be written to stdout before the first JSON-RPC response")
                    .isNull();

            send(process.getOutputStream(), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                    + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"jdiff-smoke-it\",\"version\":\"1.0.0\"}}}");

            String initializeResponseLine = pollLine(stdoutLines, TIMEOUT);
            assertThat(initializeResponseLine)
                    .as("stdout's first line must be the initialize JSON-RPC response, not stray output")
                    .isNotNull();
            JsonNode initializeResponse = JsonSupport.mapper().readTree(initializeResponseLine);
            assertThat(initializeResponse.path("id").asInt()).isEqualTo(1);
            assertThat(initializeResponse.has("error")).isFalse();
            assertThat(initializeResponse.path("result").path("serverInfo").path("name").asText())
                    .isEqualTo("jdiff");

            send(process.getOutputStream(), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

            send(process.getOutputStream(), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");

            String toolsListResponseLine = pollLine(stdoutLines, TIMEOUT);
            assertThat(toolsListResponseLine).isNotNull();
            JsonNode toolsListResponse = JsonSupport.mapper().readTree(toolsListResponseLine);
            assertThat(toolsListResponse.path("id").asInt()).isEqualTo(2);
            List<String> toolNames = new ArrayList<>();
            StreamSupport.stream(toolsListResponse.path("result").path("tools").spliterator(), false)
                    .forEach(tool -> toolNames.add(tool.path("name").asText()));
            assertThat(toolNames).containsExactlyInAnyOrder(
                    "generate_api_report", "generate_api_diff", "upgrade_impact");
        } finally {
            stdoutReader.interrupt();
            stderrDrain.interrupt();
        }
    }

    private static Path findFatJar() throws IOException {
        Path targetDir = Path.of("target");
        if (!Files.isDirectory(targetDir)) {
            return null;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetDir, "jdiff-cli-*-all.jar")) {
            Iterator<Path> it = stream.iterator();
            return it.hasNext() ? it.next() : null;
        }
    }

    private static Thread startLineReader(java.io.InputStream in, LinkedBlockingQueue<String> lines) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        lines.add(line);
                    }
                }
            } catch (IOException e) {
                // Stream closed because the process exited or was destroyed; nothing to do.
            }
        }, "smoke-it-stream-reader");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String pollLine(LinkedBlockingQueue<String> lines, Duration timeout) throws Exception {
        return lines.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void send(OutputStream out, String json) throws IOException {
        out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
