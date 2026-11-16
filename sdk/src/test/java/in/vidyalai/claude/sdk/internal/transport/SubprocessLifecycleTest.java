package in.vidyalai.claude.sdk.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.exceptions.CLIJSONDecodeException;
import in.vidyalai.claude.sdk.exceptions.CLINotFoundException;
import in.vidyalai.claude.sdk.exceptions.ProcessException;

/**
 * The transport against stand-in CLIs written as shell scripts: spawn errors,
 * stderr handling, stdout framing, the exit-code check and bounded close().
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "spawns shell scripts")
@SuppressWarnings("null")
class SubprocessLifecycleTest {

    @TempDir
    Path tmp;

    private Path script(String name, String body) throws IOException {
        Path path = tmp.resolve(name);
        Files.writeString(path, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        return path;
    }

    private static List<Map<String, Object>> drain(Iterator<Map<String, Object>> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        while (messages.hasNext()) {
            out.add(messages.next());
        }
        return out;
    }

    // ---- #39: spawn error mapping --------------------------------------------

    @Test
    void missingCliIsNotFoundWithCause() {
        Path missing = tmp.resolve("no-such-claude");
        SubprocessCLITransport transport = new SubprocessCLITransport(
                ClaudeAgentOptions.builder().cliPath(missing).build());
        try {
            assertThatThrownBy(transport::connect)
                    .isInstanceOf(CLINotFoundException.class)
                    .hasMessage("Claude Code not found at: " + missing)
                    .hasCauseInstanceOf(IOException.class);
        } finally {
            transport.close();
        }
    }

    @Test
    void cliThatCannotBeRunIsAConnectionErrorWithCause() throws IOException {
        Path notExecutable = tmp.resolve("claude");
        Files.writeString(notExecutable, "#!/bin/sh\n");
        Files.setPosixFilePermissions(notExecutable, PosixFilePermissions.fromString("rw-r--r--"));
        SubprocessCLITransport transport = new SubprocessCLITransport(
                ClaudeAgentOptions.builder().cliPath(notExecutable).build());
        try {
            assertThatThrownBy(transport::connect)
                    .isExactlyInstanceOf(CLIConnectionException.class)
                    .hasMessageStartingWith("Failed to start Claude Code: ")
                    .hasMessageContaining("Permission denied")
                    .hasCauseInstanceOf(IOException.class);
        } finally {
            transport.close();
        }
    }

    @Test
    void missingWorkingDirectoryIsReported() throws IOException {
        Path cli = script("claude", "exit 0");
        Path cwd = tmp.resolve("gone");
        SubprocessCLITransport transport = new SubprocessCLITransport(
                ClaudeAgentOptions.builder().cliPath(cli).cwd(cwd).build());
        try {
            assertThatThrownBy(transport::connect)
                    .isExactlyInstanceOf(CLIConnectionException.class)
                    .hasMessage("Working directory does not exist: " + cwd);
        } finally {
            transport.close();
        }
    }

    // ---- #18: user ----------------------------------------------------------

    @Test
    void userOptionFailsBeforeSpawning() throws IOException {
        Path marker = tmp.resolve("spawned");
        Path cli = script("claude", "touch '" + marker + "'");
        @SuppressWarnings("deprecation")
        ClaudeAgentOptions options = ClaudeAgentOptions.builder().cliPath(cli).user("nobody").build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        try {
            assertThatThrownBy(transport::connect)
                    .isExactlyInstanceOf(CLIConnectionException.class)
                    .hasMessageContaining("'user' option")
                    .hasMessageContaining("not supported by the Java SDK");
        } finally {
            transport.close();
        }
        assertThat(marker).doesNotExist();
    }

    // ---- #4: stderr ------------------------------------------------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void stderrIsInheritedWithoutACallback() throws Exception {
        // A pipe nobody drains blocks the CLI once it fills; the Python SDK
        // passes stderr=None, so the CLI writes to this process's stderr.
        Path cli = script("claude", "printf '{\"type\":\"fd2\",\"target\":\"%s\"}\\n' \"$(readlink /proc/$$/fd/2)\"");
        String ours = Files.readSymbolicLink(Path.of("/proc/self/fd/2")).toString();
        SubprocessCLITransport transport = new SubprocessCLITransport(
                ClaudeAgentOptions.builder().cliPath(cli).build());
        try {
            transport.connect();
            List<Map<String, Object>> messages = drain(transport.readMessages());
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).get("target")).isEqualTo(ours);
        } finally {
            transport.close();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void stderrCallbackGetsPythonFramedLines() throws Exception {
        Path cli = script("claude",
                "printf 'a\\r\\nb\\rc\\n\\n   \\n  indented  \\nlast' >&2\nprintf '{\"type\":\"result\"}\\n'");
        List<String> received = new CopyOnWriteArrayList<>();
        SubprocessCLITransport transport = new SubprocessCLITransport(
                ClaudeAgentOptions.builder().cliPath(cli).stderrCallback(received::add).build());
        try {
            transport.connect();
            assertThat(drain(transport.readMessages())).hasSize(1);
        } finally {
            // close() lets the stderr reader finish before it returns
            transport.close();
        }
        assertThat(received).containsExactly("a", "b\rc", "  indented", "last");
    }

    private List<String> stderrLines(List<String> chunks, Integer maxBufferSize) {
        List<String> received = new ArrayList<>();
        ClaudeAgentOptions.Builder builder = ClaudeAgentOptions.builder()
                .cliPath(Path.of("/usr/bin/claude"))
                .stderrCallback(received::add);
        if (maxBufferSize != null) {
            builder.maxBufferSize(maxBufferSize);
        }
        SubprocessCLITransport transport = new SubprocessCLITransport(builder.build());
        transport.handleStderr(new SubprocessBufferingTest.ChunkedReader(chunks));
        transport.close();
        return received;
    }

    @Test
    void stderrLineSplitAcrossChunksIsReassembled() {
        assertThat(stderrLines(List.of("a warning that got ", "split across two reads\nnext line\n"), null))
                .containsExactly("a warning that got split across two reads", "next line");
    }

    @Test
    void stderrLineWithoutNewlineIsFlushedAtBufferLimit() {
        assertThat(stderrLines(List.of("aaaaa", "aaaaa", "aaaaa", "bbb\n"), 10))
                .containsExactly("a".repeat(15), "bbb");
    }

    @Test
    void stderrPendingLineIsFlushedWhenTheReadFails() {
        List<String> received = new ArrayList<>();
        SubprocessCLITransport transport = new SubprocessCLITransport(ClaudeAgentOptions.builder()
                .cliPath(Path.of("/usr/bin/claude"))
                .stderrCallback(received::add)
                .build());
        Reader failing = new Reader() {
            private boolean sent;

            @Override
            public int read(char[] cbuf, int off, int len) throws IOException {
                if (sent) {
                    throw new IOException("Stream closed");
                }
                sent = true;
                "Error: model overloaded".getChars(0, 23, cbuf, off);
                return 23;
            }

            @Override
            public void close() {
            }
        };
        transport.handleStderr(failing);
        transport.close();
        assertThat(received).containsExactly("Error: model overloaded");
    }

    // ---- #11: one malformed line ---------------------------------------------

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void malformedLineRaisesInsteadOfSwallowingTheNextMessage() throws Exception {
        // The CLI stays up (reading stdin) after the bad line, as a live one would.
        Path cli = script("claude", "printf '{\"type\":\"assistant\",\"broken\\n{\"type\":\"result\"}\\n'\ncat >/dev/null");
        SubprocessCLITransport transport = new SubprocessCLITransport(
                ClaudeAgentOptions.builder().cliPath(cli).build());
        try {
            transport.connect();
            Iterator<Map<String, Object>> messages = transport.readMessages();
            assertThatThrownBy(messages::hasNext)
                    .isInstanceOf(CLIJSONDecodeException.class)
                    .hasMessageContaining("broken");
        } finally {
            transport.close();
        }
    }

    // ---- #16: exit-error race and bounded close ---------------------------------

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void nonZeroExitIsReportedEvenWhenTheExitLagsEndOfStream() throws Exception {
        // stdout ends a second before the process exits: the reader must not
        // signal the end of the stream before it has the exit code.
        Path cli = script("claude", "printf '{\"type\":\"x\"}\\n'\nexec 1>&-\nsleep 1\nexit 3");
        SubprocessCLITransport transport = new SubprocessCLITransport(
                ClaudeAgentOptions.builder().cliPath(cli).build());
        try {
            transport.connect();
            Iterator<Map<String, Object>> messages = transport.readMessages();
            assertThat(messages.next()).containsEntry("type", "x");
            assertThatThrownBy(messages::hasNext)
                    .isInstanceOf(ProcessException.class)
                    .satisfies(e -> assertThat(((ProcessException) e).getExitCode()).isEqualTo(3));
        } finally {
            transport.close();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void closeReturnsWhileAnotherProcessHoldsStdoutOpen() throws Exception {
        // A grandchild that inherited stdout keeps the pipe open after the CLI
        // exits, so the reader never sees end of stream. close() used to block
        // forever in BufferedReader.close() on the lock that reader holds.
        Path pidFile = tmp.resolve("grandchild.pid");
        Path cli = script("claude",
                "sleep 30 &\necho $! > '" + pidFile + "'\nprintf '{\"type\":\"init\"}\\n'\ncat >/dev/null\nexit 0");
        SubprocessCLITransport transport = new SubprocessCLITransport(
                ClaudeAgentOptions.builder().cliPath(cli).build());
        try {
            transport.connect();
            Iterator<Map<String, Object>> messages = transport.readMessages();
            assertThat(messages.next()).containsEntry("type", "init");
            CompletableFuture<Boolean> pending = CompletableFuture.supplyAsync(messages::hasNext);

            long started = System.nanoTime();
            transport.close();

            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(10);
            assertThat(pending.get(10, TimeUnit.SECONDS)).isFalse();
        } finally {
            transport.close();
            if (Files.exists(pidFile)) {
                ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim()))
                        .ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void closeEscalatesToKillWhenTheCliIgnoresEofAndSigterm() throws Exception {
        Path cli = script("claude",
                "trap '' TERM\nprintf '{\"type\":\"init\"}\\n'\nwhile :; do sleep 0.05; done");
        SubprocessCLITransport transport = new SubprocessCLITransport(
                ClaudeAgentOptions.builder().cliPath(cli).build());
        transport.closeStepTimeoutMillis = 300;
        transport.connect();
        Iterator<Map<String, Object>> messages = transport.readMessages();
        assertThat(messages.next()).containsEntry("type", "init");

        long started = System.nanoTime();
        transport.close();

        // grace, SIGTERM, SIGKILL: three bounded steps
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(8_000);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void closeIsNotHeldUpByAWriteBlockedOnAFullStdinPipe() throws Exception {
        // The CLI never reads stdin, so a large write fills the pipe and blocks
        // holding the write lock. close() used to wait for that lock forever.
        Path cli = script("claude", "printf '{\"type\":\"init\"}\\n'\nexec sleep 30");
        SubprocessCLITransport transport = new SubprocessCLITransport(
                ClaudeAgentOptions.builder().cliPath(cli).build());
        transport.closeStepTimeoutMillis = 300;
        transport.connect();
        Iterator<Map<String, Object>> messages = transport.readMessages();
        assertThat(messages.next()).containsEntry("type", "init");
        String big = "x".repeat(4 * 1024 * 1024);
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> transport.write(big));
        Thread.sleep(200); // let the writer fill the pipe and block

        long started = System.nanoTime();
        transport.close();

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(8_000);
        // The CLI is gone, so the blocked write fails rather than hanging.
        assertThatThrownBy(() -> writer.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(CLIConnectionException.class);
    }

}
