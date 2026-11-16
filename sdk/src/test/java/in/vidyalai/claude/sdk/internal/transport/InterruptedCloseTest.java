package in.vidyalai.claude.sdk.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.types.permission.PermissionResult;
import in.vidyalai.claude.sdk.types.permission.PermissionResultAllow;

/**
 * Closing the real subprocess transport from an interrupted thread, or from
 * inside a callback, still ends with stdin EOF and a clean exit of the CLI,
 * never with a kill. The stand-in CLIs record which it was: {@code eof} once
 * stdin ends, {@code term} on SIGTERM, nothing at all on SIGKILL.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "spawns shell scripts")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@SuppressWarnings("null")
class InterruptedCloseTest {

    @TempDir
    Path tmp;

    private Path script(String name, String body) throws IOException {
        Path path = tmp.resolve(name);
        Files.writeString(path, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        return path;
    }

    private static String read(Path marker) throws IOException {
        return (Files.exists(marker) ? Files.readString(marker).strip() : "<killed>");
    }

    @Test
    void closeWithTheInterruptFlagSetStillClosesStdinGracefully() throws Exception {
        Path marker = tmp.resolve("marker");
        Path cli = script("claude", "trap 'echo term >> \"$MARK\"; exit 143' TERM\n"
                + "cat > /dev/null\n"
                + "echo eof >> \"$MARK\"\n"
                + "exit 0");
        SubprocessCLITransport transport = new SubprocessCLITransport(ClaudeAgentOptions.builder()
                .cliPath(cli).env(Map.of("MARK", marker.toString())).build());
        transport.connect();

        Thread.currentThread().interrupt();
        try {
            transport.close();
        } finally {
            assertThat(Thread.interrupted()).as("interrupt flag restored").isTrue();
        }

        // close() waited for the exit, so the marker is final. An interrupted
        // close() used to skip stdin EOF and SIGKILL the CLI at once.
        assertThat(read(marker)).isEqualTo("eof");
    }

    @Test
    void disconnectFromACanUseToolCallbackClosesStdinGracefully() throws Exception {
        Path marker = tmp.resolve("marker");
        // Answers initialize, then asks for a permission; exits on stdin EOF.
        Path cli = script("claude", "trap 'echo term >> \"$MARK\"; exit 143' TERM\n"
                + "while IFS= read -r line; do\n"
                + "  case \"$line\" in\n"
                + "    *'\"subtype\":\"initialize\"'*)\n"
                + "      id=$(printf '%s' \"$line\" | sed 's/.*\"request_id\":\"\\([^\"]*\\)\".*/\\1/')\n"
                + "      printf '{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\","
                + "\"request_id\":\"%s\",\"response\":{}}}\\n' \"$id\"\n"
                + "      printf '{\"type\":\"control_request\",\"request_id\":\"cli_1\",\"request\":"
                + "{\"subtype\":\"can_use_tool\",\"tool_name\":\"Bash\",\"input\":{\"command\":\"ls\"}}}\\n'\n"
                + "      ;;\n"
                + "  esac\n"
                + "done\n"
                + "echo eof >> \"$MARK\"\n"
                + "exit 0");
        AtomicReference<ClaudeSDKClient> ref = new AtomicReference<>();
        AtomicBoolean interruptedAfter = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .cliPath(cli)
                .env(Map.of("MARK", marker.toString()))
                .canUseTool((toolName, input, context) -> {
                    ref.get().disconnect();
                    interruptedAfter.set(Thread.currentThread().isInterrupted());
                    done.countDown();
                    return CompletableFuture.<PermissionResult>completedFuture(new PermissionResultAllow());
                })
                .build();
        ClaudeSDKClient client = new ClaudeSDKClient(options);
        ref.set(client);
        try {
            client.connect();
            assertThat(done.await(30, TimeUnit.SECONDS)).as("the callback disconnected").isTrue();

            // disconnect() from the callback used to interrupt its own thread
            // and then SIGKILL the CLI without closing stdin.
            assertThat(read(marker)).isEqualTo("eof");
            assertThat(interruptedAfter.get()).isFalse();
            assertThat(client.isConnected()).isFalse();
        } finally {
            client.close();
        }
    }

}
