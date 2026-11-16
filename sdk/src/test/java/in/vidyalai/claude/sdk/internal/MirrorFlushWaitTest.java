package in.vidyalai.claude.sdk.internal;

import static in.vidyalai.claude.sdk.internal.PipeTransport.awaitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionStore;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;
import in.vidyalai.claude.sdk.types.session.SessionStoreFlushMode;

/**
 * The mirror flush before a result has no outer cap, as in the Python SDK:
 * the consumer sees the result only once the turn is in the store, however
 * long the store takes (the batcher bounds each append itself).
 *
 * <p>
 * The reader no longer waits for that flush itself: it holds the result back
 * and reads on, so frames after the result (control responses included) are
 * read while the store is slow. It used to park in the flush, reading
 * nothing until the store returned.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("null")
class MirrorFlushWaitTest {

    private static final long WAIT_SECONDS = 5;

    @Test
    void theResultWaitsForASlowStoreWhileTheReaderReadsOn() throws Exception {
        CountDownLatch appendStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Map<SessionKey, List<SessionStoreEntry>> stored = new ConcurrentHashMap<>();
        SessionStore slow = new SessionStore() {
            @Override
            public void append(SessionKey key, List<SessionStoreEntry> entries) {
                appendStarted.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                stored.put(key, entries);
            }

            @Override
            public List<SessionStoreEntry> load(SessionKey key) {
                return stored.get(key);
            }
        };

        Path projects = Sessions.getProjectsDirForEnv(Map.of());
        String sessionId = UUID.randomUUID().toString();
        String filePath = projects.resolve("p").resolve(sessionId + ".jsonl").toString();
        Map<String, Object> result = new HashMap<>();
        result.put("type", "result");
        result.put("subtype", "success");
        result.put("duration_ms", 1);
        result.put("duration_api_ms", 1);
        result.put("is_error", false);
        result.put("num_turns", 1);
        result.put("session_id", sessionId);

        PipeTransport transport = new PipeTransport();
        transport.push(Map.of("type", "transcript_mirror", "filePath", filePath,
                "entries", List.of(Map.of("type", "user", "uuid", "u1"))));
        transport.push(result);
        transport.push(Map.of("type", "user", "message", Map.of("role", "user", "content", "after")));

        try (QueryHandler handler = new QueryHandler(transport, true, null, null, Duration.ofSeconds(10))) {
            handler.setTranscriptMirrorBatcher(SessionResume.buildMirrorBatcher(
                    slow, null, Map.of(), (k, e, d) -> {
                    }, SessionStoreFlushMode.BATCHED));
            transport.connect();
            handler.start();

            CompletableFuture<List<Message>> consumer = CompletableFuture.supplyAsync(() -> {
                List<Message> seen = new ArrayList<>();
                var messages = handler.receiveMessages();
                while (messages.hasNext()) {
                    Message message = messages.next();
                    seen.add(message);
                    if (message instanceof ResultMessage) {
                        break;
                    }
                }
                return seen;
            });

            assertThat(appendStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            // The reader reads past the result while the store is still busy:
            // the next frame is taken, and a control call made now gets its
            // response (it used to wait behind the flush).
            awaitUntil("the reader to read past the result", WAIT_SECONDS, () -> transport.taken() == 3);
            assertTimeoutPreemptively(Duration.ofSeconds(5), handler::interrupt);
            assertThat(transport.requestsWritten("interrupt")).hasSize(1);
            // The result itself is withheld until the store has the turn.
            assertThat(consumer).isNotDone();
            assertThat(stored).isEmpty();

            release.countDown();
            List<Message> seen = consumer.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertThat(seen).last().isInstanceOf(ResultMessage.class);
            // The turn was in the store before the result reached the consumer.
            assertThat(stored).hasSize(1);
        }
    }

}
