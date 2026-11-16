package in.vidyalai.claude.sdk.internal;

import static in.vidyalai.claude.sdk.internal.PipeTransport.awaitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.MirrorErrorMessage;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionStore;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;

/**
 * A {@code mirror_error} reaches the consumer ahead of the {@code result}
 * whose turn it belongs to, and ahead of the error a failed read ends the
 * stream with. {@link TranscriptMirrorBatcher#flush()} waits for every drain
 * scheduled so far and its {@code onError} reports, while a slow
 * {@code onError} still never holds up a later append.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@SuppressWarnings("null")
class MirrorErrorOrderingTest {

    private static final long WAIT_SECONDS = 5;
    private static final String PROJECTS = "/tmp/proj";
    private static final String FILE = PROJECTS + "/p/s.jsonl";

    /** A store whose appends never complete, so each one times out. */
    private static final SessionStore HANGING = new SessionStore() {
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
            return new CompletableFuture<>();
        }
    };

    private static Map<String, Object> mirrorFrame(String uuid) {
        return Map.of("type", "transcript_mirror", "filePath", FILE,
                "entries", List.of(Map.of("type", "user", "uuid", uuid)));
    }

    private static Map<String, Object> result() {
        return Map.of("type", "result", "subtype", "success", "is_error", false, "duration_ms", 1,
                "duration_api_ms", 1, "num_turns", 1, "session_id", "s", "result", "ok");
    }

    private static QueryHandler start(PipeTransport transport, boolean eager) {
        QueryHandler handler = new QueryHandler(transport, true, null, null, null,
                null, null, null, Duration.ofSeconds(10), 1000);
        // 20 ms per append, not retried after a timeout; eager drains on
        // every frame, batched only at the explicit flush.
        handler.setTranscriptMirrorBatcher(new TranscriptMirrorBatcher(HANGING, PROJECTS,
                handler::reportMirrorError, 20, eager ? 0 : 500, eager ? 0 : 1 << 20,
                ForkJoinPool.commonPool()));
        transport.connect();
        handler.start();
        return handler;
    }

    @Test
    void inEagerModeTheMirrorErrorAlwaysPrecedesTheResult() throws Exception {
        for (int run = 0; run < 100; run++) {
            PipeTransport transport = new PipeTransport();
            QueryHandler handler = start(transport, true);
            try {
                transport.push(mirrorFrame("u" + run));
                // The eager drain is under way when the result arrives.
                awaitUntil("the mirror frame to be read", WAIT_SECONDS, () -> transport.taken() >= 1);
                transport.push(result());
                transport.end();

                List<Class<?>> seen = new ArrayList<>();
                Iterator<Message> messages = handler.receiveMessages();
                while (messages.hasNext()) {
                    seen.add(messages.next().getClass());
                }
                assertThat(seen).as("run %d", run).containsExactly(MirrorErrorMessage.class, ResultMessage.class);
            } finally {
                handler.close();
            }
        }
    }

    @Test
    void aMirrorErrorFromTheFinalFlushPrecedesTheReadError() throws Exception {
        PipeTransport transport = new PipeTransport();
        QueryHandler handler = start(transport, false);
        try {
            // Batched: the entry is still pending when the read fails, so only
            // the end-of-stream flush sends it, and that append fails.
            transport.push(mirrorFrame("u1"));
            transport.fail(new CLIConnectionException("boom"));

            List<Message> seen = new ArrayList<>();
            Iterator<Message> messages = handler.receiveMessages();
            assertThatThrownBy(() -> {
                while (messages.hasNext()) {
                    seen.add(messages.next());
                }
            }).isInstanceOf(CLIConnectionException.class).hasMessage("boom");
            // It used to be queued behind the error, where nobody reads.
            assertThat(seen).hasSize(1);
            assertThat(seen.get(0)).isInstanceOf(MirrorErrorMessage.class);
        } finally {
            handler.close();
        }
    }

    @Test
    void flushWaitsForTheReportsOfEarlierDrainsButASlowReportDelaysNoAppend() throws Exception {
        List<String> appended = new CopyOnWriteArrayList<>();
        SessionStore store = new SessionStore() {
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
                String uuid = entries.get(0).uuid();
                if ("bad".equals(uuid)) {
                    return new CompletableFuture<>(); // times out
                }
                appended.add(uuid);
                return CompletableFuture.completedFuture(null);
            }
        };
        CountDownLatch reporting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> reported = new CopyOnWriteArrayList<>();
        TranscriptMirrorBatcher batcher = new TranscriptMirrorBatcher(store, PROJECTS, (key, error, drain) -> {
            reporting.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            reported.add(key.sessionId());
        }, 20, 0, 0, ForkJoinPool.commonPool());

        // An eager drain whose append fails; its report is still running.
        batcher.enqueue(FILE, List.of(SessionStoreEntry.of(Map.of("type", "user", "uuid", "bad"))));
        assertThat(reporting.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        // A later drain is not held up by that report.
        batcher.enqueue(FILE, List.of(SessionStoreEntry.of(Map.of("type", "user", "uuid", "good"))));
        awaitUntil("the later append", WAIT_SECONDS, () -> appended.contains("good"));

        // But a flush is: it used to complete once the appends were done.
        CompletableFuture<Void> flushed = batcher.flush();
        assertThat(flushed).isNotDone();
        release.countDown();
        flushed.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(reported).containsExactly("s");
    }

}
