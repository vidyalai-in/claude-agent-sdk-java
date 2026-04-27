package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;
import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionStore;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;

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

}
