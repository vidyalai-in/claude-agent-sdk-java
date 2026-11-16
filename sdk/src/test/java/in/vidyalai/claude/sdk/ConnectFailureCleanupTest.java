package in.vidyalai.claude.sdk;

import static in.vidyalai.claude.sdk.internal.PipeTransport.awaitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.exceptions.CLINotFoundException;
import in.vidyalai.claude.sdk.exceptions.ClaudeSDKException;
import in.vidyalai.claude.sdk.internal.PipeTransport;
import in.vidyalai.claude.sdk.internal.SessionStores;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;
import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;

/**
 * A connect that fails after a store-backed resume materialized its temporary
 * {@code CLAUDE_CONFIG_DIR} (which holds a copy of the credentials) leaves
 * nothing behind: the directory, the transport and the reader are all
 * released before the failure is rethrown. Ports the Python SDK's
 * {@code test_session_resume.py} connect-failure cases.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("null")
class ConnectFailureCleanupTest {

    private static final long WAIT_SECONDS = 5;

    @TempDir
    Path configDir;

    /** Options that resume a stored session, so connect materializes a temp dir. */
    private ClaudeAgentOptions resumeOptions() throws Exception {
        // An isolated CLAUDE_CONFIG_DIR, so the credentials copy reads nothing real.
        Files.writeString(configDir.resolve(".credentials.json"), "{}");
        InMemorySessionStore store = new InMemorySessionStore();
        String sessionId = UUID.randomUUID().toString();
        String projectKey = SessionStores.projectKeyForDirectory(null);
        store.append(new SessionKey(projectKey, sessionId, null), List.of(SessionStoreEntry.of(Map.of(
                "type", "user", "uuid", "u1", "timestamp", "2026-01-01T00:00:00Z",
                "message", Map.of("role", "user", "content", "hi")))));
        return ClaudeAgentOptions.builder()
                .sessionStore(store)
                .resume(sessionId)
                .env(Map.of("CLAUDE_CONFIG_DIR", configDir.toString()))
                .build();
    }

    /** Records the materialized dir the transport would point the CLI at. */
    private static Path materializedDir(ClaudeAgentOptions effective) {
        Path dir = Path.of(effective.env().get("CLAUDE_CONFIG_DIR"));
        assertThat(dir.resolve("projects")).isDirectory();
        return dir;
    }

    @Test
    void aFailedInitializeReleasesTheTransportTheReaderAndTheTempDir() throws Exception {
        PipeTransport transport = new PipeTransport();
        transport.replier = request -> PipeTransport.error(request, "control timeout");
        AtomicReference<Path> tempDir = new AtomicReference<>();

        ClaudeSDKClient client = ClaudeSDKClient.withTransportFactory(resumeOptions(), effective -> {
            tempDir.set(materializedDir(effective));
            return transport;
        });

        assertThatThrownBy(client::connect).isInstanceOf(ClaudeSDKException.class).hasMessageContaining("control timeout");

        assertThat(tempDir.get()).isNotNull();
        assertThat(tempDir.get()).doesNotExist();
        assertThat(transport.closeCalls()).isPositive();
        // The query handler was closed too: its read loop ended.
        awaitUntil("the read loop to end", WAIT_SECONDS, transport::readEnded);
        assertThat(client.isConnected()).isFalse();
        // disconnect()/close() afterwards are harmless.
        client.close();
    }

    @Test
    void aTransportThatCannotBeBuiltStillRemovesTheTempDir() throws Exception {
        AtomicReference<Path> tempDir = new AtomicReference<>();
        ClaudeSDKClient client = ClaudeSDKClient.withTransportFactory(resumeOptions(), effective -> {
            tempDir.set(materializedDir(effective));
            throw new CLINotFoundException("Claude Code not found");
        });

        assertThatThrownBy(client::connect).isInstanceOf(CLINotFoundException.class);
        assertThat(tempDir.get()).doesNotExist();
        client.close();
    }

    @Test
    void aTransportThatFailsToSpawnStillRemovesTheTempDir() throws Exception {
        AtomicReference<Path> tempDir = new AtomicReference<>();
        PipeTransport failing = new PipeTransport();
        Transport spawnFails = new Transport() {
            @Override
            public void connect() {
                throw new CLIConnectionException("spawn failed");
            }

            @Override
            public void write(String data) {
                failing.write(data);
            }

            @Override
            public java.util.Iterator<Map<String, Object>> readMessages() {
                return failing.readMessages();
            }

            @Override
            public void endInput() {
            }

            @Override
            public boolean isReady() {
                return false;
            }

            @Override
            public void close() {
                failing.close();
            }
        };
        ClaudeSDKClient client = ClaudeSDKClient.withTransportFactory(resumeOptions(), effective -> {
            tempDir.set(materializedDir(effective));
            return spawnFails;
        });

        assertThatThrownBy(client::connect).isInstanceOf(CLIConnectionException.class).hasMessage("spawn failed");
        assertThat(tempDir.get()).doesNotExist();
        assertThat(failing.closeCalls()).isPositive();
    }

    @Test
    void oneShotQueryRemovesTheTempDirWhenTheTransportCannotBeBuilt() throws Exception {
        AtomicReference<Path> tempDir = new AtomicReference<>();
        assertThatThrownBy(() -> ClaudeSDK.queryWithTransportFactory(
                ClaudeSDK.stringPromptMessages("hi").iterator(), resumeOptions(), effective -> {
                    tempDir.set(materializedDir(effective));
                    throw new CLINotFoundException("Claude Code not found");
                })).isInstanceOf(CLINotFoundException.class);
        assertThat(tempDir.get()).isNotNull();
        assertThat(tempDir.get()).doesNotExist();
    }

    @Test
    void connectSendsAnEmptyInitialPrompt() throws Exception {
        PipeTransport transport = new PipeTransport();
        try (ClaudeSDKClient client = ClaudeSDKClient.withTransportFactory(ClaudeAgentOptions.defaults(),
                effective -> transport)) {
            client.connect("");
            List<Map<String, Object>> users = transport.writtenFrames().stream()
                    .filter(frame -> "user".equals(frame.get("type")))
                    .toList();
            assertThat(users).hasSize(1);
            assertThat(users.get(0).get("message")).isEqualTo(Map.of("role", "user", "content", ""));
            assertThat(users.get(0)).containsKey("parent_tool_use_id");
            assertThat(users.get(0).get("parent_tool_use_id")).isNull();
        }
    }

    @Test
    void connectWithoutAPromptSendsNone() throws Exception {
        PipeTransport transport = new PipeTransport();
        try (ClaudeSDKClient client = ClaudeSDKClient.withTransportFactory(ClaudeAgentOptions.defaults(),
                effective -> transport)) {
            client.connect();
            assertThat(transport.writtenFrames()).noneMatch(frame -> "user".equals(frame.get("type")));
        }
    }

    @Test
    void stringPromptsCarryAnExplicitNullParentToolUseId() throws Exception {
        PipeTransport transport = new PipeTransport();
        try (ClaudeSDKClient client = ClaudeSDKClient.withTransportFactory(ClaudeAgentOptions.defaults(),
                effective -> transport)) {
            client.connect();
            client.sendMessage("one");
            client.query("two", "s2");
            List<Map<String, Object>> users = transport.writtenFrames().stream()
                    .filter(frame -> "user".equals(frame.get("type")))
                    .toList();
            assertThat(users).hasSize(2);
            for (Map<String, Object> user : users) {
                assertThat(user).containsKey("parent_tool_use_id");
                assertThat(user.get("parent_tool_use_id")).isNull();
            }
            assertThat(users.get(1)).containsEntry("session_id", "s2");
        }
        // And the one-shot query's message, as the Python SDK's query() builds it.
        Map<String, Object> oneShot = ClaudeSDK.stringPromptMessages("x").get(0);
        assertThat(oneShot).containsKey("parent_tool_use_id").containsEntry("session_id", "");
    }

}
