package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionStore;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;
import in.vidyalai.claude.sdk.types.session.SessionStoreFlushMode;

/**
 * A {@code transcript_mirror} frame with entries that are not JSON objects:
 * the object entries are still mirrored, and the others are skipped with a
 * warning (a {@link SessionStoreEntry} wraps an object, so they cannot reach
 * the adapter as Python's would).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("null")
class MirrorNonObjectEntriesTest {

    @Test
    void objectEntriesAreMirroredAndOthersAreSkippedWithAWarning() throws Exception {
        Map<SessionKey, List<SessionStoreEntry>> stored = new ConcurrentHashMap<>();
        SessionStore store = new SessionStore() {
            @Override
            public void append(SessionKey key, List<SessionStoreEntry> entries) {
                stored.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).addAll(entries);
            }

            @Override
            public List<SessionStoreEntry> load(SessionKey key) {
                return stored.get(key);
            }
        };

        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger(QueryHandler.class.getName());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);

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
                "entries", List.of(
                        Map.of("type", "user", "uuid", "u1"),
                        "not an object",
                        42,
                        List.of("nested"),
                        Map.of("type", "assistant", "uuid", "a1"))));
        transport.push(result);

        try (QueryHandler handler = new QueryHandler(transport, true, null, null, Duration.ofSeconds(10))) {
            handler.setTranscriptMirrorBatcher(SessionResume.buildMirrorBatcher(
                    store, null, Map.of(), (k, e, d) -> {
                    }, SessionStoreFlushMode.BATCHED));
            transport.connect();
            handler.start();

            List<Message> seen = new ArrayList<>();
            var messages = handler.receiveMessages();
            while (messages.hasNext()) {
                Message message = messages.next();
                seen.add(message);
                if (message instanceof ResultMessage) {
                    break;
                }
            }
            assertThat(seen).last().isInstanceOf(ResultMessage.class);
        } finally {
            logger.removeHandler(capture);
        }

        // The flush before the result put the object entries in the store.
        assertThat(stored).hasSize(1);
        List<SessionStoreEntry> entries = stored.values().iterator().next();
        assertThat(entries).extracting(e -> e.asMap().get("uuid")).containsExactly("u1", "a1");
        assertThat(warnings).anySatisfy(r -> assertThat(r.getMessage())
                .contains("Skipping 3 transcript_mirror entries")
                .contains("not JSON objects"));
    }

}
