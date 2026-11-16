package in.vidyalai.claude.sdk;

import static in.vidyalai.claude.sdk.internal.PipeTransport.awaitUntil;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import in.vidyalai.claude.sdk.internal.PipeTransport;
import in.vidyalai.claude.sdk.internal.SessionStores;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.permission.PermissionResult;
import in.vidyalai.claude.sdk.types.permission.PermissionResultAllow;
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;
import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionStore;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;

/**
 * {@code connect()}, {@code disconnect()} and {@code close()} are serialized:
 * a second disconnect cannot remove the materialized {@code CLAUDE_CONFIG_DIR}
 * while the first is still closing the CLI that uses it, a disconnect cannot
 * slip into a connect, and a callback that disconnects while another thread
 * is disconnecting returns promptly instead of deadlocking.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@SuppressWarnings("null")
class ClientLifecycleConcurrencyTest {

    private static final long WAIT_SECONDS = 5;

    @TempDir
    Path configDir;

    /** Options that resume a stored session, so connect materializes a temp dir. */
    private ClaudeAgentOptions resumeOptions() throws Exception {
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

    /** A pipe whose close() blocks until released, like a CLI slow to exit. */
    private static final class SlowClosingTransport implements Transport {
        final PipeTransport pipe = new PipeTransport();
        final CountDownLatch closing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<@Nullable Path> dir = new AtomicReference<>();
        final AtomicReference<@Nullable Boolean> dirExistedWhenClosed = new AtomicReference<>();

        @Override
        public void connect() {
            pipe.connect();
        }

        @Override
        public void write(String data) {
            pipe.write(data);
        }

        @Override
        public Iterator<Map<String, Object>> readMessages() {
            return pipe.readMessages();
        }

        @Override
        public void endInput() {
        }

        @Override
        public boolean isReady() {
            return pipe.isReady();
        }

        @Override
        public void close() {
            closing.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            pipe.close();
            Path d = dir.get();
            dirExistedWhenClosed.compareAndSet(null, (d != null) && Files.exists(d));
        }
    }

    private static boolean parkedOrDone(Thread thread) {
        Thread.State state = thread.getState();
        return (state == Thread.State.WAITING) || (state == Thread.State.TERMINATED);
    }

    @Test
    void aConcurrentDisconnectRemovesTheTempDirOnlyAfterTheTransportClosed() throws Exception {
        SlowClosingTransport transport = new SlowClosingTransport();
        ClaudeSDKClient client = ClaudeSDKClient.withTransportFactory(resumeOptions(), effective -> {
            transport.dir.set(Path.of(effective.env().get("CLAUDE_CONFIG_DIR")));
            return transport;
        });
        client.connect();
        Path tempDir = transport.dir.get();
        assertThat(tempDir).isDirectory();

        Thread first = new Thread(client::disconnect, "first-disconnect");
        first.start();
        assertThat(transport.closing.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        Thread second = new Thread(client::disconnect, "second-disconnect");
        second.start();
        awaitUntil("the second disconnect to wait or finish", WAIT_SECONDS, () -> parkedOrDone(second));

        // The first is still closing the CLI: the dir it uses must still be there.
        assertThat(tempDir).isDirectory();

        transport.release.countDown();
        first.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS * 3));
        second.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS * 3));
        assertThat(first.isAlive()).isFalse();
        assertThat(second.isAlive()).isFalse();
        assertThat(transport.dirExistedWhenClosed.get()).isTrue();
        assertThat(tempDir).doesNotExist();
        assertThat(client.isConnected()).isFalse();
    }

    @Test
    void aDisconnectDuringConnectWaitsForItAndThenDisconnects() throws Exception {
        PipeTransport transport = new PipeTransport();
        AtomicReference<@Nullable Map<String, Object>> heldInit = new AtomicReference<>();
        CountDownLatch initSent = new CountDownLatch(1);
        transport.replier = request -> {
            heldInit.set(request);
            initSent.countDown();
            return null; // answered below, once the disconnect is waiting
        };
        ClaudeSDKClient client = ClaudeSDKClient.withTransportFactory(ClaudeAgentOptions.defaults(),
                effective -> transport);

        AtomicReference<@Nullable Throwable> connectFailure = new AtomicReference<>();
        Thread connector = new Thread(() -> {
            try {
                client.connect();
            } catch (Throwable t) {
                connectFailure.set(t);
            }
        }, "connector");
        connector.start();
        assertThat(initSent.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        Thread disconnector = new Thread(client::disconnect, "disconnector");
        disconnector.start();
        awaitUntil("the disconnect to wait or finish", WAIT_SECONDS, () -> parkedOrDone(disconnector));
        transport.push(PipeTransport.success(heldInit.get()));

        connector.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS * 3));
        disconnector.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS * 3));
        // The disconnect used to close the handler under the running connect,
        // whose initialize then failed with "QueryHandler is closed".
        assertThat(connectFailure.get()).isNull();
        assertThat(client.isConnected()).isFalse();
        assertThat(transport.closeCalls()).isPositive();
        client.close();
    }

    @Test
    void aCallbackThatDisconnectsWhileAnotherThreadDisconnectsReturnsPromptly() throws Exception {
        CountDownLatch appending = new CountDownLatch(1);
        CountDownLatch releaseStore = new CountDownLatch(1);
        SessionStore slowStore = new SessionStore() {
            @Override
            public void append(SessionKey key, List<SessionStoreEntry> entries) {
            }

            @Override
            public List<SessionStoreEntry> load(SessionKey key) {
                return List.of();
            }

            @Override
            public CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries,
                    Executor executor) {
                appending.countDown();
                return CompletableFuture.runAsync(() -> {
                    try {
                        releaseStore.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        };
        AtomicReference<ClaudeSDKClient> ref = new AtomicReference<>();
        AtomicReference<@Nullable Thread> callbackThread = new AtomicReference<>();
        CountDownLatch inCallback = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch callbackDisconnected = new CountDownLatch(1);
        AtomicBoolean callbackInterruptedAfter = new AtomicBoolean();
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sessionStore(slowStore)
                .env(Map.of("CLAUDE_CONFIG_DIR", configDir.toString()))
                .canUseTool((toolName, input, context) -> {
                    callbackThread.set(Thread.currentThread());
                    inCallback.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    ref.get().disconnect();
                    callbackInterruptedAfter.set(Thread.currentThread().isInterrupted());
                    callbackDisconnected.countDown();
                    return CompletableFuture.<PermissionResult>completedFuture(new PermissionResultAllow());
                })
                .build();
        PipeTransport transport = new PipeTransport();
        ClaudeSDKClient client = ClaudeSDKClient.withTransportFactory(options, effective -> transport);
        ref.set(client);
        client.connect();

        // A pending mirror entry: the closing thread's final flush blocks on it
        // while holding the lifecycle lock.
        String file = configDir.resolve("projects").resolve("p").resolve("s.jsonl").toString();
        transport.push(Map.of("type", "transcript_mirror", "filePath", file,
                "entries", List.of(Map.of("type", "user", "uuid", "u1"))));
        transport.push(Map.of("type", "control_request", "request_id", "cli_1", "request",
                Map.of("subtype", "can_use_tool", "tool_name", "Bash", "input", Map.of("command", "ls"))));
        assertThat(inCallback.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        Thread closer = new Thread(client::disconnect, "closer");
        closer.start();
        assertThat(appending.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        // The callback disconnects too, and waits for the closer.
        go.countDown();
        Thread inCallbackThread = callbackThread.get();
        awaitUntil("the callback to wait for the closer", WAIT_SECONDS,
                () -> (callbackDisconnected.getCount() == 1)
                        && (inCallbackThread.getState() == Thread.State.WAITING));

        // The closer cancels the callback, which stops waiting, so neither
        // sits out the control-handler join timeout (5 s).
        long t0 = System.nanoTime();
        releaseStore.countDown();
        closer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS * 3));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertThat(closer.isAlive()).isFalse();
        assertThat(callbackDisconnected.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(elapsedMs).isLessThan(4_000);
        // Returned at once on the interrupt, keeping the flag.
        assertThat(callbackInterruptedAfter.get()).isTrue();
        assertThat(client.isConnected()).isFalse();
        assertThat(transport.responsesTo("cli_1")).isEmpty();
        client.close();
    }

}
