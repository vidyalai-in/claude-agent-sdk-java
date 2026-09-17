package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionStore;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Mirroring must keep working when the configured session-store executor is
 * bounded.
 *
 * <p>A drain blocks waiting for the {@code appendAsync} it submits, and the
 * default {@code appendAsync} submits to the configured executor. While drains
 * also ran on that executor, a drain was waiting for a slot it was itself
 * occupying: with an unbounded thread-per-task executor that merely costs a
 * thread, but with a bounded one every append waits out {@code sendTimeoutMs}
 * and the batch is dropped through {@code onError}.
 *
 * <p>These tests use a deliberately short {@code sendTimeoutMs} so the failure
 * mode is a fast, legible assertion failure rather than a 60-second stall.
 */
class TranscriptMirrorBatcherBoundedExecutorTest {

    private static final long SHORT_TIMEOUT_MS = 2_000L;

    private static SessionStoreEntry entry(String uuid) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "user");
        data.put("uuid", uuid);
        return SessionStoreEntry.of(data);
    }

    private static String transcriptPath(Path projectsDir) {
        return projectsDir.resolve("myproj").resolve(UUID.randomUUID() + ".jsonl").toString();
    }

    /** Sync-only adapter: the shape that relies on the default appendAsync wrapper. */
    private static final class RecordingStore implements SessionStore {
        final AtomicInteger appendCalls = new AtomicInteger();
        final List<SessionStoreEntry> received = Collections.synchronizedList(new ArrayList<>());
        final List<String> appendThreads = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void append(SessionKey key, List<SessionStoreEntry> entries) {
            appendCalls.incrementAndGet();
            appendThreads.add(Thread.currentThread().getName());
            received.addAll(entries);
        }

        @Override
        public List<SessionStoreEntry> load(SessionKey key) {
            return List.copyOf(received);
        }
    }

    private static TranscriptMirrorBatcher batcher(
            SessionStore store, Path projects, List<String> errors, java.util.concurrent.Executor executor) {
        return new TranscriptMirrorBatcher(
                store,
                projects.toString(),
                (key, message) -> errors.add(String.valueOf(message)),
                SHORT_TIMEOUT_MS,
                TranscriptMirrorBatcher.MAX_PENDING_ENTRIES,
                TranscriptMirrorBatcher.MAX_PENDING_BYTES,
                executor);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void flush_withSingleThreadedExecutor_stillAppends() throws Exception {
        RecordingStore store = new RecordingStore();
        Path projects = Path.of("/tmp/projects-bounded-1");
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        ExecutorService bounded = Executors.newSingleThreadExecutor();
        try {
            TranscriptMirrorBatcher b = batcher(store, projects, errors, bounded);
            b.enqueue(transcriptPath(projects), List.of(entry("u1"), entry("u2")));
            b.flush().get(25, TimeUnit.SECONDS);

            assertThat(errors).as("a bounded executor must not strand the append").isEmpty();
            assertThat(store.appendCalls.get()).isEqualTo(1);
            assertThat(store.received).extracting(e -> e.asMap().get("uuid"))
                    .containsExactly("u1", "u2");
        } finally {
            bounded.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void repeatedFlushes_withSingleThreadedExecutor_allLand() throws Exception {
        RecordingStore store = new RecordingStore();
        Path projects = Path.of("/tmp/projects-bounded-2");
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        ExecutorService bounded = Executors.newSingleThreadExecutor();
        try {
            TranscriptMirrorBatcher b = batcher(store, projects, errors, bounded);
            String path = transcriptPath(projects);
            for (int i = 0; i < 5; i++) {
                b.enqueue(path, List.of(entry("u" + i)));
                b.flush().get(25, TimeUnit.SECONDS);
            }
            assertThat(errors).isEmpty();
            assertThat(store.appendCalls.get()).isEqualTo(5);
            assertThat(store.received).hasSize(5);
        } finally {
            bounded.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void close_withSingleThreadedExecutor_flushesPending() throws Exception {
        RecordingStore store = new RecordingStore();
        Path projects = Path.of("/tmp/projects-bounded-3");
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        ExecutorService bounded = Executors.newSingleThreadExecutor();
        try {
            TranscriptMirrorBatcher b = batcher(store, projects, errors, bounded);
            b.enqueue(transcriptPath(projects), List.of(entry("only")));
            b.close().get(25, TimeUnit.SECONDS);

            assertThat(errors).isEmpty();
            assertThat(store.appendCalls.get()).isEqualTo(1);
        } finally {
            bounded.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void adapterStillRunsOnTheConfiguredExecutor() throws Exception {
        // Moving drains off the configured executor must not move the adapter
        // off it too — that executor is how callers bound their own store work.
        RecordingStore store = new RecordingStore();
        Path projects = Path.of("/tmp/projects-bounded-4");
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        ExecutorService named = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "caller-store-pool"));
        try {
            TranscriptMirrorBatcher b = batcher(store, projects, errors, named);
            b.enqueue(transcriptPath(projects), List.of(entry("u1")));
            b.flush().get(25, TimeUnit.SECONDS);

            assertThat(errors).isEmpty();
            assertThat(store.appendThreads).containsExactly("caller-store-pool");
        } finally {
            named.shutdownNow();
        }
    }
}
