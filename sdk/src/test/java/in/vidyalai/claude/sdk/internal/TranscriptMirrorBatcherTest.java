package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;
import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionStore;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;
import in.vidyalai.claude.sdk.types.session.SessionStoreFlushMode;

class TranscriptMirrorBatcherTest {

    private static SessionStoreEntry entry(String uuid) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "user");
        data.put("uuid", uuid);
        return SessionStoreEntry.of(data);
    }

    /** Builds a transcript path under the given projectsDir for a fresh session. */
    private static String mainTranscriptPath(Path projectsDir) {
        String projectKey = "myproj";
        String sessionId = UUID.randomUUID().toString();
        return projectsDir.resolve(projectKey).resolve(sessionId + ".jsonl").toString();
    }

    @Test
    void enqueueAndFlush_writesToStore() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        Path projects = Path.of("/tmp/projects-x");
        TranscriptMirrorBatcher batcher = new TranscriptMirrorBatcher(
                store, projects.toString(), (k, e) -> {
                });

        String filePath = mainTranscriptPath(projects);
        batcher.enqueue(filePath, List.of(entry("u1"), entry("u2")));
        batcher.flush().get(5, TimeUnit.SECONDS);

        SessionKey key = InMemorySessionStore.filePathToSessionKey(filePath, projects.toString());
        assertThat(key).isNotNull();
        List<SessionStoreEntry> loaded = store.load(key);
        assertThat(loaded).hasSize(2);
    }

    @Test
    void multipleFramesForSamePath_areCoalescedIntoOneAppendCall() throws Exception {
        AtomicInteger appendCalls = new AtomicInteger();
        Map<SessionKey, List<SessionStoreEntry>> received = new ConcurrentHashMap<>();
        SessionStore counting = new SessionStore() {
            @Override
            public void append(SessionKey key, List<SessionStoreEntry> entries) {
                appendCalls.incrementAndGet();
                received.computeIfAbsent(key, k -> new ArrayList<>()).addAll(entries);
            }

            @Override
            public List<SessionStoreEntry> load(SessionKey key) {
                return received.get(key);
            }
        };

        Path projects = Path.of("/tmp/coalesce-test");
        TranscriptMirrorBatcher batcher = new TranscriptMirrorBatcher(
                counting, projects.toString(), (k, e) -> {
                });

        String filePath = mainTranscriptPath(projects);
        batcher.enqueue(filePath, List.of(entry("u1")));
        batcher.enqueue(filePath, List.of(entry("u2")));
        batcher.enqueue(filePath, List.of(entry("u3")));
        batcher.flush().get(5, TimeUnit.SECONDS);

        assertThat(appendCalls.get()).isEqualTo(1);
        assertThat(received.values().iterator().next()).hasSize(3);
    }

    @Test
    void framesNotUnderProjectsDir_areDroppedWithoutAppend() throws Exception {
        AtomicInteger appendCalls = new AtomicInteger();
        SessionStore counting = new SessionStore() {
            @Override
            public void append(SessionKey key, List<SessionStoreEntry> entries) {
                appendCalls.incrementAndGet();
            }

            @Override
            public List<SessionStoreEntry> load(SessionKey key) {
                return null;
            }
        };

        TranscriptMirrorBatcher batcher = new TranscriptMirrorBatcher(
                counting, "/tmp/projects-y", (k, e) -> {
                });

        // Path is under a DIFFERENT projects dir
        batcher.enqueue("/tmp/different/projectKey/abc.jsonl", List.of(entry("u1")));
        batcher.flush().get(5, TimeUnit.SECONDS);

        assertThat(appendCalls.get()).isZero();
    }

    @Test
    void retriesAdapterFailureUpToMaxAttempts_thenReportsViaOnError() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        SessionStore failing = new SessionStore() {
            @Override
            public void append(SessionKey key, List<SessionStoreEntry> entries) {
                attempts.incrementAndGet();
                throw new RuntimeException("simulated adapter failure");
            }

            @Override
            public List<SessionStoreEntry> load(SessionKey key) {
                return null;
            }
        };

        CountDownLatch errorReceived = new CountDownLatch(1);
        List<String> errors = new ArrayList<>();
        Path projects = Path.of("/tmp/retry-test");
        TranscriptMirrorBatcher batcher = new TranscriptMirrorBatcher(
                failing, projects.toString(),
                (k, e) -> {
                    errors.add(e);
                    errorReceived.countDown();
                });

        batcher.enqueue(mainTranscriptPath(projects), List.of(entry("u1")));
        batcher.flush().get(15, TimeUnit.SECONDS);

        assertThat(errorReceived.await(2, TimeUnit.SECONDS)).isTrue();
        // The retry policy is 3 attempts total
        assertThat(attempts.get()).isEqualTo(TranscriptMirrorBatcher.MIRROR_APPEND_MAX_ATTEMPTS);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("simulated adapter failure");
    }

    @Test
    void closeFlushesPendingEntries() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        Path projects = Path.of("/tmp/close-test");
        TranscriptMirrorBatcher batcher = new TranscriptMirrorBatcher(
                store, projects.toString(), (k, e) -> {
                });

        String filePath = mainTranscriptPath(projects);
        batcher.enqueue(filePath, List.of(entry("u1"), entry("u2")));
        batcher.close().get(5, TimeUnit.SECONDS);

        SessionKey key = InMemorySessionStore.filePathToSessionKey(filePath, projects.toString());
        assertThat(store.load(key)).hasSize(2);
    }

    @Test
    void enqueueAfterCloseIsDropped() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        Path projects = Path.of("/tmp/closed-enqueue-test");
        TranscriptMirrorBatcher batcher = new TranscriptMirrorBatcher(
                store, projects.toString(), (k, e) -> {
                });

        batcher.close().get(5, TimeUnit.SECONDS);
        batcher.enqueue(mainTranscriptPath(projects), List.of(entry("u1")));
        // No additional flush should pick it up
        CompletableFuture<Void> postClose = batcher.flush();
        postClose.get(5, TimeUnit.SECONDS);
        // Pending should remain at zero
        assertThat(batcher.pendingEntries()).isZero();
    }

    @SuppressWarnings("null")
    @Test
    void filePathToSessionKey_handlesMainAndSubagentPaths() {
        Path projects = Path.of("/p");
        SessionKey main = InMemorySessionStore.filePathToSessionKey(
                "/p/myproj/abc-123.jsonl", projects.toString());
        assertThat(main).isNotNull();
        assertThat(main.projectKey()).isEqualTo("myproj");
        assertThat(main.sessionId()).isEqualTo("abc-123");
        assertThat(main.subpath()).isNull();

        SessionKey sub = InMemorySessionStore.filePathToSessionKey(
                "/p/myproj/abc-123/subagents/agent-x.jsonl", projects.toString());
        assertThat(sub).isNotNull();
        assertThat(sub.projectKey()).isEqualTo("myproj");
        assertThat(sub.sessionId()).isEqualTo("abc-123");
        assertThat(sub.subpath()).isEqualTo("subagents/agent-x");
    }

    @Test
    void filePathToSessionKey_returnsNullForOutsidePath() {
        SessionKey key = InMemorySessionStore.filePathToSessionKey(
                "/somewhere/else/abc.jsonl", "/p");
        assertThat(key).isNull();
    }

    // -----------------------------------------------------------------------
    // SessionResume.buildMirrorBatcher / SessionStoreFlushMode
    // -----------------------------------------------------------------------

    @Test
    void buildMirrorBatcher_defaultFlushMode_keepsBatchedThresholds() {
        // Default — no flush mode argument.
        TranscriptMirrorBatcher defaultBatcher = SessionResume.buildMirrorBatcher(
                new InMemorySessionStore(), null, Map.of(), (k, e) -> {
                });
        assertThat(defaultBatcher.maxPendingEntries())
                .isEqualTo(TranscriptMirrorBatcher.MAX_PENDING_ENTRIES);
        assertThat(defaultBatcher.maxPendingBytes())
                .isEqualTo(TranscriptMirrorBatcher.MAX_PENDING_BYTES);

        // Explicit BATCHED — same defaults.
        TranscriptMirrorBatcher batched = SessionResume.buildMirrorBatcher(
                new InMemorySessionStore(), null, Map.of(), (k, e) -> {
                }, SessionStoreFlushMode.BATCHED);
        assertThat(batched.maxPendingEntries())
                .isEqualTo(TranscriptMirrorBatcher.MAX_PENDING_ENTRIES);
        assertThat(batched.maxPendingBytes())
                .isEqualTo(TranscriptMirrorBatcher.MAX_PENDING_BYTES);

        // EAGER — thresholds zeroed so every enqueue schedules a drain.
        TranscriptMirrorBatcher eager = SessionResume.buildMirrorBatcher(
                new InMemorySessionStore(), null, Map.of(), (k, e) -> {
                }, SessionStoreFlushMode.EAGER);
        assertThat(eager.maxPendingEntries()).isZero();
        assertThat(eager.maxPendingBytes()).isZero();
    }

    @Test
    void buildMirrorBatcher_batchedMode_doesNotAutoDrainOnSingleFrame() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        TranscriptMirrorBatcher batcher = SessionResume.buildMirrorBatcher(
                store, null, Map.of(), (k, e) -> {
                });
        Path projects = Sessions.getProjectsDirForEnv(Map.of());
        String projectKey = "myproj";
        String sessionId = UUID.randomUUID().toString();
        String filePath = projects.resolve(projectKey).resolve(sessionId + ".jsonl").toString();
        batcher.enqueue(filePath, List.of(entry("u1")));
        // Pending count reflects the buffered entry — no auto-drain in BATCHED mode.
        assertThat(batcher.pendingEntries()).isEqualTo(1);
        batcher.flush().get(5, TimeUnit.SECONDS);
        SessionKey key = InMemorySessionStore.filePathToSessionKey(filePath, projects.toString());
        assertThat(store.load(key)).hasSize(1);
    }

    @Test
    void buildMirrorBatcher_eagerFlushMode_schedulesDrainPerFrame() throws Exception {
        AtomicInteger appendCalls = new AtomicInteger();
        Map<SessionKey, List<SessionStoreEntry>> received = new ConcurrentHashMap<>();
        CountDownLatch firstAppend = new CountDownLatch(1);
        CountDownLatch secondAppend = new CountDownLatch(1);
        SessionStore counting = new SessionStore() {
            @Override
            public void append(SessionKey key, List<SessionStoreEntry> entries) {
                int n = appendCalls.incrementAndGet();
                received.computeIfAbsent(key, k -> new ArrayList<>()).addAll(entries);
                if (n == 1) {
                    firstAppend.countDown();
                } else if (n == 2) {
                    secondAppend.countDown();
                }
            }

            @Override
            public List<SessionStoreEntry> load(SessionKey key) {
                return received.get(key);
            }
        };

        TranscriptMirrorBatcher batcher = SessionResume.buildMirrorBatcher(
                counting, null, Map.of(), (k, e) -> {
                }, SessionStoreFlushMode.EAGER);

        // Enqueue two frames sequentially; each one exceeds the (zeroed)
        // thresholds and schedules its own drain. Wait for the first append
        // to complete before enqueuing the next so the eager drains don't
        // coalesce.
        Path projects = Sessions.getProjectsDirForEnv(Map.of());
        String projectKey = "myproj";
        String sessionId = UUID.randomUUID().toString();
        String filePath = projects.resolve(projectKey).resolve(sessionId + ".jsonl").toString();

        batcher.enqueue(filePath, List.of(entry("u1")));
        assertThat(firstAppend.await(5, TimeUnit.SECONDS)).isTrue();
        batcher.enqueue(filePath, List.of(entry("a1")));
        assertThat(secondAppend.await(5, TimeUnit.SECONDS)).isTrue();
        // Final flush is a no-op (pending already drained) but settles any
        // late-running drainer scheduled by the second enqueue.
        batcher.flush().get(5, TimeUnit.SECONDS);

        assertThat(appendCalls.get()).isEqualTo(2);
        assertThat(received.values().iterator().next()).hasSize(2);
    }

    /**
     * End-to-end through {@link QueryHandler}'s receive loop: with
     * {@link SessionStoreFlushMode#EAGER} each {@code transcript_mirror} frame
     * triggers its own background drain so the store sees one
     * {@code append()} per frame rather than a single coalesced batch when
     * {@code result} arrives. Mirrors Python's
     * {@code test_eager_flush_mode_appends_per_frame_before_result}.
     */
    @Test
    void receiveLoop_eagerFlushMode_appendsPerFrameBeforeResult() throws Exception {
        AtomicInteger appendCalls = new AtomicInteger();
        Map<SessionKey, List<SessionStoreEntry>> received = new ConcurrentHashMap<>();
        // Gate the second frame on the first append completing so the drains
        // can't coalesce. Without this, the reader thread could deliver
        // both frames before drainA scheduled by frame1 has acquired any
        // synchronization, and a single drain could pick up both frames.
        CountDownLatch firstAppendComplete = new CountDownLatch(1);
        SessionStore recording = new SessionStore() {
            @Override
            public void append(SessionKey key, List<SessionStoreEntry> entries) {
                appendCalls.incrementAndGet();
                received.computeIfAbsent(key, k -> new ArrayList<>()).addAll(entries);
                firstAppendComplete.countDown();
            }

            @Override
            public List<SessionStoreEntry> load(SessionKey key) {
                return received.get(key);
            }
        };

        Path projects = Sessions.getProjectsDirForEnv(Map.of());
        String projectKey = "p";
        String sessionId = UUID.randomUUID().toString();
        String filePath = projects.resolve(projectKey).resolve(sessionId + ".jsonl").toString();

        Map<String, Object> frame1 = Map.of(
                "type", "transcript_mirror",
                "filePath", filePath,
                "entries", List.of(Map.of("type", "user", "uuid", "u1")));
        Map<String, Object> frame2 = Map.of(
                "type", "transcript_mirror",
                "filePath", filePath,
                "entries", List.of(Map.of("type", "assistant", "uuid", "a1")));
        Map<String, Object> assistantMsg = new HashMap<>();
        assistantMsg.put("type", "assistant");
        Map<String, Object> assistantInner = new HashMap<>();
        assistantInner.put("role", "assistant");
        assistantInner.put("content", List.of(Map.of("type", "text", "text", "hello")));
        assistantInner.put("model", "claude-sonnet-4-5");
        assistantMsg.put("message", assistantInner);
        Map<String, Object> resultMsg = new HashMap<>();
        resultMsg.put("type", "result");
        resultMsg.put("subtype", "success");
        resultMsg.put("duration_ms", 100);
        resultMsg.put("duration_api_ms", 80);
        resultMsg.put("is_error", false);
        resultMsg.put("num_turns", 1);
        resultMsg.put("session_id", "test-session");
        resultMsg.put("total_cost_usd", 0.0001);

        BarrierTransport transport = new BarrierTransport(
                List.of(frame1, frame2, assistantMsg, resultMsg),
                /* barrierBeforeIndex */ 1,
                firstAppendComplete);

        try (QueryHandler handler = new QueryHandler(
                transport, false, null, null, Duration.ofSeconds(60))) {
            TranscriptMirrorBatcher batcher = SessionResume.buildMirrorBatcher(
                    recording, null, Map.of(), (k, e) -> {
                    }, SessionStoreFlushMode.EAGER);
            handler.setTranscriptMirrorBatcher(batcher);

            handler.start();
            // Drain messages until the result arrives. Both transcript_mirror
            // frames are peeled off by the receive loop and never yielded.
            for (Message msg : (Iterable<Message>) handler::receiveMessages) {
                if (msg instanceof ResultMessage) {
                    break;
                }
            }
            // QueryHandler's receive loop calls batcher.flush() before
            // yielding the result message, so by the time we exit the loop
            // both eager drains scheduled by enqueue() have completed.
        }

        assertThat(appendCalls.get()).isEqualTo(2);
        assertThat(received.values().iterator().next()).hasSize(2);
    }

    /**
     * Mock Transport that delivers a fixed list of messages through
     * {@link #readMessages()}. Holds delivery of the message at
     * {@code barrierBeforeIndex} until the supplied {@link CountDownLatch}
     * fires, so tests can guarantee a side effect (e.g. an append() call)
     * has completed before the next frame arrives.
     */
    static final class BarrierTransport implements Transport {

        private final List<Map<String, Object>> messages;
        private final int barrierBeforeIndex;
        private final CountDownLatch barrier;
        private final AtomicBoolean ready = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        BarrierTransport(
                List<Map<String, Object>> messages,
                int barrierBeforeIndex,
                CountDownLatch barrier) {
            this.messages = messages;
            this.barrierBeforeIndex = barrierBeforeIndex;
            this.barrier = barrier;
        }

        @Override
        public void connect() {
            ready.set(true);
        }

        @Override
        public void write(String data) throws CLIConnectionException {
            // No control protocol support — non-streaming QueryHandler
            // doesn't send initialize, so writes are not expected.
        }

        @Override
        public Iterator<Map<String, Object>> readMessages() {
            return new Iterator<>() {
                int idx = 0;

                @Override
                public boolean hasNext() {
                    return !closed.get() && idx < messages.size();
                }

                @Override
                public Map<String, Object> next() {
                    if (idx == barrierBeforeIndex) {
                        try {
                            // Wait up to 5s for the barrier — the test asserts
                            // appendCalls=2, so the barrier must fire before
                            // we deliver the second frame.
                            barrier.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return messages.get(idx++);
                }
            };
        }

        @Override
        public void endInput() {
            // No-op
        }

        @Override
        public boolean isReady() {
            return ready.get() && !closed.get();
        }

        @Override
        public void close() {
            closed.set(true);
        }

    }

}
