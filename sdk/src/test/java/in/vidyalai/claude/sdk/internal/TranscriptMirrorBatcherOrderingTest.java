package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionStore;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;
import in.vidyalai.claude.sdk.types.session.SessionStoreExecutor;

/**
 * Drain ordering and bounded completion of {@link TranscriptMirrorBatcher}.
 *
 * <p>Ports Python's {@code test_two_eager_flushes_do_not_interleave_or_duplicate}
 * and {@code test_flush_awaits_in_flight_eager_flush}, plus a stress case for the
 * detach-then-lock race and the {@code flush()} / {@code close()} time bound.
 */
class TranscriptMirrorBatcherOrderingTest {

    private static final Path PROJECTS = Path.of("/tmp/mirror-ordering");

    private static SessionStoreEntry numbered(int n) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "x");
        data.put("n", n);
        return SessionStoreEntry.of(data);
    }

    private static String mainPath() {
        return PROJECTS.resolve("proj").resolve(UUID.randomUUID() + ".jsonl").toString();
    }

    /** Records the {@code n} of every appended entry, in append order. */
    private static class RecordingStore implements SessionStore {
        final List<Integer> appended = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void append(SessionKey key, List<SessionStoreEntry> entries) {
            for (SessionStoreEntry e : entries) {
                Object n = e.asMap().get("n");
                appended.add(((Number) n).intValue());
            }
        }

        @Override
        public List<SessionStoreEntry> load(SessionKey key) {
            return List.of();
        }
    }

    /** Every append waits for {@link #gate}; {@link #started} trips on the first one. */
    private static final class GatedStore extends RecordingStore {
        final CountDownLatch gate = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);

        @Override
        public void append(SessionKey key, List<SessionStoreEntry> entries) {
            started.countDown();
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            super.append(key, entries);
        }
    }

    private static TranscriptMirrorBatcher batcher(SessionStore store, long timeoutMs, int maxEntries) {
        return new TranscriptMirrorBatcher(store, PROJECTS.toString(), (k, e) -> {
        }, timeoutMs, maxEntries, TranscriptMirrorBatcher.MAX_PENDING_BYTES,
                SessionStoreExecutor.getDefault());
    }

    @Test
    @Timeout(20)
    void twoEagerFlushesDoNotInterleaveOrDuplicate() throws Exception {
        GatedStore store = new GatedStore();
        // maxPendingEntries 0: every enqueue triggers an eager flush.
        TranscriptMirrorBatcher b = batcher(store, 10_000L, 0);
        String path = mainPath();

        b.enqueue(path, List.of(numbered(1)));
        assertThat(store.started.await(5, TimeUnit.SECONDS)).isTrue();
        b.enqueue(path, List.of(numbered(2)));

        store.gate.countDown();
        b.flush().get(10, TimeUnit.SECONDS);
        assertThat(store.appended).containsExactly(1, 2);
    }

    @Test
    @Timeout(20)
    void flushAwaitsInFlightEagerFlush() throws Exception {
        GatedStore store = new GatedStore();
        TranscriptMirrorBatcher b = batcher(store, 10_000L, 1);
        String path = mainPath();

        b.enqueue(path, List.of(numbered(1), numbered(2)));
        assertThat(store.started.await(5, TimeUnit.SECONDS)).isTrue();
        b.enqueue(path, List.of(numbered(3)));

        CompletableFuture<Void> flush = b.flush();
        assertThat(flush).isNotDone();
        store.gate.countDown();
        flush.get(10, TimeUnit.SECONDS);
        assertThat(store.appended).containsExactly(1, 2, 3);
    }

    @Test
    @Timeout(60)
    void manyEagerDrainsAppendInEnqueueOrder() throws Exception {
        // Before drains were chained, each one detached the buffer, released
        // the monitor and only then took the drain lock, so a later drain could
        // win the race and append first.
        for (int round = 0; round < 20; round++) {
            RecordingStore store = new RecordingStore();
            TranscriptMirrorBatcher b = batcher(store, 10_000L, 0);
            String path = mainPath();
            List<Integer> expected = new ArrayList<>();
            for (int n = 0; n < 200; n++) {
                b.enqueue(path, List.of(numbered(n)));
                expected.add(n);
            }
            b.flush().get(30, TimeUnit.SECONDS);
            assertThat(store.appended).as("round %d", round).containsExactlyElementsOf(expected);
        }
    }

    @Test
    @Timeout(20)
    void adapterBlockingInsideAppendAsyncIsCutOffAtTheSendTimeout() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        SessionStore blocking = new SessionStore() {
            @Override
            public void append(SessionKey key, List<SessionStoreEntry> entries) {
            }

            @Override
            public CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries,
                    Executor executor) {
                // Blocks the calling thread instead of returning a future.
                try {
                    never.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public List<SessionStoreEntry> load(SessionKey key) {
                return List.of();
            }
        };
        TranscriptMirrorBatcher b = new TranscriptMirrorBatcher(blocking, PROJECTS.toString(),
                (k, e) -> errors.add(e), 200L, TranscriptMirrorBatcher.MAX_PENDING_ENTRIES,
                TranscriptMirrorBatcher.MAX_PENDING_BYTES, SessionStoreExecutor.getDefault());

        b.enqueue(mainPath(), List.of(numbered(1)));
        b.flush().get(5, TimeUnit.SECONDS);
        // The drain timed out (not retried) and reported the drop.
        assertThat(errors).hasSize(1);

        // A later drain is not stuck behind the blocked one.
        b.enqueue(mainPath(), List.of(numbered(2)));
        b.close().get(5, TimeUnit.SECONDS);
        assertThat(errors).hasSize(2);
        never.countDown();
    }

    @Test
    @Timeout(30)
    void flushCompletesWithinFlushTimeoutEvenWhenTheBatchTakesLonger() throws Exception {
        // Each append returns a future that never completes, so every path
        // waits out the full send timeout. Twenty paths at 200 ms is 4 s, well
        // past the 3 × 200 + 1000 = 1600 ms cap.
        SessionStore hanging = new SessionStore() {
            @Override
            public void append(SessionKey key, List<SessionStoreEntry> entries) {
            }

            @Override
            public CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries,
                    Executor executor) {
                return new CompletableFuture<>();
            }

            @Override
            public List<SessionStoreEntry> load(SessionKey key) {
                return List.of();
            }
        };
        CountDownLatch allReported = new CountDownLatch(20);
        TranscriptMirrorBatcher b = new TranscriptMirrorBatcher(hanging, PROJECTS.toString(),
                (k, e) -> allReported.countDown(), 200L, TranscriptMirrorBatcher.MAX_PENDING_ENTRIES,
                TranscriptMirrorBatcher.MAX_PENDING_BYTES, SessionStoreExecutor.getDefault());
        assertThat(b.flushTimeoutMs()).isEqualTo(1_600L);
        for (int i = 0; i < 20; i++) {
            b.enqueue(mainPath(), List.of(numbered(i)));
        }

        long start = System.nanoTime();
        b.flush().get(3_500, TimeUnit.MILLISECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(elapsedMs).isLessThan(3_500L);

        // The drain kept going in the background and still reported every drop.
        assertThat(allReported.await(15, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void defaultFlushTimeoutIsAttemptsTimesSendTimeoutPlusBackoff() {
        TranscriptMirrorBatcher b = new TranscriptMirrorBatcher(new RecordingStore(), PROJECTS.toString(),
                (k, e) -> {
                });
        assertThat(b.flushTimeoutMs()).isEqualTo(3 * 60_000L + 200L + 800L);
    }
}
