package in.vidyalai.claude.sdk.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookMatcher;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.MirrorErrorMessage;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.StreamEvent;
import in.vidyalai.claude.sdk.types.message.TextBlock;
import in.vidyalai.claude.sdk.types.message.UserMessage;
import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionStore;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;

/**
 * Frames, a scripted store and a collecting consumer shared by the
 * transcript-mirror delivery-barrier tests.
 *
 * <p>
 * Every delivered message is reduced to a label ({@code r:tag} for a result,
 * {@code u:text}, {@code se:uuid}, {@code a:text}, {@code me:session} for a
 * mirror error, named after the session of the file whose append failed), so
 * a test can compare the stream against the order it expects.
 */
final class MirrorBarrierFixtures {

    static final long WAIT_SECONDS = 10;
    static final String PROJECTS = "/tmp/mirror-barrier-projects";

    private MirrorBarrierFixtures() {
    }

    static String file(String session) {
        return PROJECTS + "/p/" + session + ".jsonl";
    }

    /** A transcript_mirror frame for {@code session}'s file carrying entries {@code uuids}. */
    static Map<String, Object> mirror(String session, String... uuids) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String uuid : uuids) {
            entries.add(Map.of("type", "user", "uuid", uuid));
        }
        return Map.of("type", "transcript_mirror", "filePath", file(session), "entries", entries);
    }

    static Map<String, Object> result(String tag) {
        Map<String, Object> result = new HashMap<>();
        result.put("type", "result");
        result.put("subtype", "success");
        result.put("duration_ms", 1);
        result.put("duration_api_ms", 1);
        result.put("is_error", false);
        result.put("num_turns", 1);
        result.put("session_id", "s");
        result.put("result", tag);
        return result;
    }

    static Map<String, Object> user(String text) {
        return Map.of("type", "user", "message", Map.of("role", "user", "content", text));
    }

    static Map<String, Object> streamEvent(String uuid) {
        return Map.of("type", "stream_event", "uuid", uuid, "session_id", "s",
                "event", Map.of("type", "content_block_delta"));
    }

    static Map<String, Object> assistant(String text) {
        return Map.of("type", "assistant", "message", Map.of("role", "assistant", "model", "m",
                "content", List.of(Map.of("type", "text", "text", text))));
    }

    static Map<String, Object> canUseTool(String requestId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("subtype", "can_use_tool");
        request.put("tool_name", "Bash");
        request.put("input", Map.of("command", "ls"));
        return Map.of("type", "control_request", "request_id", requestId, "request", request);
    }

    static Map<String, Object> hookCallback(String requestId) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("hook_event_name", "PreToolUse");
        input.put("session_id", "s");
        input.put("transcript_path", "/tmp/t.jsonl");
        input.put("cwd", ".");
        input.put("tool_name", "Bash");
        input.put("tool_input", Map.of());
        input.put("tool_use_id", "toolu_1");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("subtype", "hook_callback");
        request.put("callback_id", "hook_0");
        request.put("input", input);
        request.put("tool_use_id", "toolu_1");
        return Map.of("type", "control_request", "request_id", requestId, "request", request);
    }

    static Map<String, Object> cancel(String requestId) {
        return Map.of("type", "control_cancel_request", "request_id", requestId);
    }

    static String label(Message message) {
        if (message instanceof ResultMessage r) {
            return "r:" + r.result();
        }
        if (message instanceof MirrorErrorMessage e) {
            SessionKey key = e.key();
            return "me:" + ((key != null) ? key.sessionId() : "?");
        }
        if (message instanceof UserMessage u) {
            return "u:" + u.content();
        }
        if (message instanceof StreamEvent se) {
            return "se:" + se.uuid();
        }
        if (message instanceof AssistantMessage a) {
            return "a:" + ((a.content().get(0) instanceof TextBlock t) ? t.text() : "?");
        }
        return "?:" + message.getClass().getSimpleName();
    }

    static QueryHandler handler(PipeTransport transport, ClaudeAgentOptions.@Nullable CanUseTool canUseTool,
            HookMatcher.@Nullable HookCallback hook, int maxMsgQSize) {
        Map<HookEvent, List<HookMatcher>> hooks = ((hook != null)
                ? Map.of(HookEvent.PRE_TOOL_USE, List.<HookMatcher>of(new HookMatcher(null, List.of(hook))))
                : null);
        return new QueryHandler(transport, true, canUseTool, hooks, null,
                null, null, null, Duration.ofSeconds(10), maxMsgQSize);
    }

    /** Attaches a batcher over {@code store}: EAGER drains every frame, BATCHED only at a flush. */
    static TranscriptMirrorBatcher attach(QueryHandler handler, SessionStore store, long sendTimeoutMs,
            boolean eager) {
        TranscriptMirrorBatcher batcher = new TranscriptMirrorBatcher(store, PROJECTS,
                handler::reportMirrorError, sendTimeoutMs, eager ? 0 : 500, eager ? 0 : 1 << 20,
                ForkJoinPool.commonPool());
        handler.setTranscriptMirrorBatcher(batcher);
        return batcher;
    }

    /**
     * A store whose behavior each append picks from its first entry's uuid:
     * {@code hang*} never completes (times out), {@code fail*} fails,
     * {@code gate*} waits for {@link #release}, {@code delay<N>-*} completes
     * after N ms, anything else at once. An append counts as stored
     * ({@link #appended}) just before the future it returned completes.
     */
    static final class ScriptedStore implements SessionStore {

        final List<String> appended = new CopyOnWriteArrayList<>();
        final List<String> started = new CopyOnWriteArrayList<>();
        private final Map<String, CompletableFuture<Void>> gates = new ConcurrentHashMap<>();
        volatile @Nullable Consumer<List<String>> onAppended = null;

        private volatile boolean open = false;

        CompletableFuture<Void> gate(String uuid) {
            CompletableFuture<Void> gate = gates.computeIfAbsent(uuid, k -> new CompletableFuture<>());
            if (open) {
                gate.complete(null);
            }
            return gate;
        }

        void release(String uuid) {
            gate(uuid).complete(null);
        }

        /** Releases every gate, present and future (teardown). */
        void openAll() {
            open = true;
            for (CompletableFuture<Void> gate : gates.values()) {
                gate.complete(null);
            }
        }

        @Override
        public void append(SessionKey key, List<SessionStoreEntry> entries) {
            throw new UnsupportedOperationException("appendAsync only");
        }

        @Override
        public List<SessionStoreEntry> load(SessionKey key) {
            return List.of();
        }

        @Override
        public CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries,
                Executor executor) {
            List<String> uuids = new ArrayList<>();
            for (SessionStoreEntry entry : entries) {
                uuids.add(entry.uuid());
            }
            String first = uuids.get(0);
            started.add(first);
            CompletableFuture<Void> outcome;
            if (first.startsWith("hang")) {
                return new CompletableFuture<>();
            } else if (first.startsWith("fail")) {
                return CompletableFuture.failedFuture(new IllegalStateException(first));
            } else if (first.startsWith("gate")) {
                outcome = gate(first);
            } else if (first.startsWith("delay")) {
                long ms = Long.parseLong(first.substring("delay".length(), first.indexOf('-')));
                outcome = CompletableFuture.runAsync(() -> {
                }, CompletableFuture.delayedExecutor(ms, TimeUnit.MILLISECONDS));
            } else {
                outcome = CompletableFuture.completedFuture(null);
            }
            return outcome.thenRun(() -> {
                Consumer<List<String>> hook = onAppended;
                if (hook != null) {
                    hook.accept(uuids);
                }
                appended.addAll(uuids);
            });
        }
    }

    /**
     * Drains {@code handler}'s stream on its own thread, recording each
     * message's label. {@code onEach} runs before the label is recorded, so a
     * check it makes happens strictly at delivery.
     */
    static final class Collector {

        final List<String> seen = new CopyOnWriteArrayList<>();
        final CompletableFuture<Void> finished = new CompletableFuture<>();
        final Thread thread;

        Collector(QueryHandler handler) {
            this(handler, null);
        }

        Collector(QueryHandler handler, @Nullable Consumer<Message> onEach) {
            thread = new Thread(() -> {
                try {
                    Iterator<Message> messages = handler.receiveMessages();
                    while (messages.hasNext()) {
                        Message message = messages.next();
                        if (onEach != null) {
                            onEach.accept(message);
                        }
                        seen.add(label(message));
                    }
                    finished.complete(null);
                } catch (Throwable t) {
                    finished.completeExceptionally(t);
                }
            }, "barrier-test-consumer");
            thread.setDaemon(true);
            thread.start();
        }

        /** Waits for the stream to end normally and returns every label. */
        List<String> awaitEnd() throws Exception {
            finished.get(WAIT_SECONDS, TimeUnit.SECONDS);
            return List.copyOf(seen);
        }

        void awaitSeen(int count) throws Exception {
            PipeTransport.awaitUntil(count + " messages (seen " + seen + ")", WAIT_SECONDS,
                    () -> seen.size() >= count);
        }
    }

}
