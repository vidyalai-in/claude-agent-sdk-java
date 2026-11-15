package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookMatcher;
import in.vidyalai.claude.sdk.types.hook.HookOnFailure;
import in.vidyalai.claude.sdk.types.hook.output.HookOutput;

/**
 * {@link HookMatcher#onFailure()} reaches the CLI as {@code onFailure}, and a
 * {@link HookOnFailure#BLOCK} guard refuses a CLI that would let it fail open
 * (Python SDK #1460).
 */
class HookOnFailureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HookMatcher.HookCallback NOOP = (input, context) -> CompletableFuture
            .completedFuture(HookOutput.empty());

    private final List<QueryHandler> handlers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (QueryHandler h : handlers) {
            try {
                h.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    private QueryHandler handlerFor(ScriptedTransport transport, HookMatcher matcher) {
        QueryHandler handler = new QueryHandler(transport, true, null,
                Map.of(HookEvent.PRE_TOOL_USE, List.of(matcher)), Duration.ofSeconds(10));
        handlers.add(handler);
        transport.connect();
        handler.start();
        return handler;
    }

    @SuppressWarnings({ "unchecked", "null" })
    private static Map<String, Object> firstMatcherSent(ScriptedTransport transport) throws Exception {
        Map<String, Object> frame = MAPPER.readValue(transport.writes().get(0), Map.class);
        Map<String, Object> request = (Map<String, Object>) frame.get("request");
        Map<String, Object> hooks = (Map<String, Object>) request.get("hooks");
        return ((List<Map<String, Object>>) hooks.get("PreToolUse")).get(0);
    }

    @ParameterizedTest
    @EnumSource(HookOnFailure.class)
    void initializeSendsOnFailure(HookOnFailure onFailure) throws Exception {
        ScriptedTransport transport = new ScriptedTransport(Map.of("hook_on_failure_supported", true));
        handlerFor(transport, new HookMatcher(null, List.of(NOOP), null, onFailure)).initialize();

        assertThat(firstMatcherSent(transport)).containsEntry("onFailure", onFailure.getValue());
    }

    @Test
    void initializeOmitsOnFailureWhenUnset() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(Map.of());
        handlerFor(transport, new HookMatcher(null, List.of(NOOP))).initialize();

        assertThat(firstMatcherSent(transport)).doesNotContainKey("onFailure");
    }

    @SuppressWarnings("null")
    @Test
    void blockIsRefusedWhenTheCliDoesNotReportSupport() {
        ScriptedTransport transport = new ScriptedTransport(Map.of("commands", List.of()));
        QueryHandler handler = handlerFor(transport,
                new HookMatcher(null, List.of(NOOP), null, HookOnFailure.BLOCK));

        assertThatThrownBy(handler::initialize)
                .isInstanceOf(CLIConnectionException.class)
                .hasMessageContaining("onFailure");
    }

    @Test
    void refusalClosesTheTransportAndDropsTheCallbacks() {
        ScriptedTransport transport = new ScriptedTransport(Map.of());
        QueryHandler handler = handlerFor(transport,
                new HookMatcher(null, List.of(NOOP), null, HookOnFailure.BLOCK));

        assertThatThrownBy(handler::initialize).isInstanceOf(CLIConnectionException.class);

        // Nothing is left running with a guard that would fail open.
        assertThat(transport.isReady()).isFalse();
        assertThat(handler.getInitializationResult()).isNull();
    }

    @SuppressWarnings("null")
    @Test
    void oneBlockMatcherAmongContinueMatchersStillNeedsSupport() {
        ScriptedTransport transport = new ScriptedTransport(Map.of());
        QueryHandler handler = new QueryHandler(transport, true, null,
                Map.of(HookEvent.PRE_TOOL_USE, List.of(
                        new HookMatcher("Read", List.of(NOOP), null, HookOnFailure.CONTINUE),
                        new HookMatcher("Bash", List.of(NOOP), null, HookOnFailure.BLOCK),
                        new HookMatcher("Write", List.of(NOOP))),
                        HookEvent.POST_TOOL_USE, List.of(new HookMatcher(null, List.of(NOOP), null,
                                HookOnFailure.CONTINUE))),
                Duration.ofSeconds(10));
        handlers.add(handler);
        transport.connect();
        handler.start();

        assertThatThrownBy(handler::initialize)
                .isInstanceOf(CLIConnectionException.class)
                .hasMessageContaining("onFailure");
    }

    @Test
    void truthySupportFlagCounts() throws Exception {
        // Python checks `not response.get("hook_on_failure_supported")`.
        ScriptedTransport transport = new ScriptedTransport(Map.of("hook_on_failure_supported", 1));
        handlerFor(transport, new HookMatcher(null, List.of(NOOP), null, HookOnFailure.BLOCK)).initialize();

        assertThat(firstMatcherSent(transport)).containsEntry("onFailure", "block");
    }

    @SuppressWarnings("null")
    @Test
    void blockIsRefusedWhenTheHooksWereNotApplied() {
        ScriptedTransport transport = new ScriptedTransport(
                Map.of("hook_on_failure_supported", true, "hooks_applied", false));
        QueryHandler handler = handlerFor(transport,
                new HookMatcher(null, List.of(NOOP), null, HookOnFailure.BLOCK));

        assertThatThrownBy(handler::initialize)
                .isInstanceOf(CLIConnectionException.class)
                .hasMessageContaining("did not register");
    }

    @Test
    void continueIsAllowedWhenTheCliLacksSupport() throws Exception {
        // Only BLOCK needs CLI support; CONTINUE matches an older CLI's behavior.
        ScriptedTransport transport = new ScriptedTransport(Map.of());
        handlerFor(transport, new HookMatcher(null, List.of(NOOP), null, HookOnFailure.CONTINUE)).initialize();

        assertThat(firstMatcherSent(transport)).containsEntry("onFailure", "continue");
    }

    /**
     * A hook that fails must answer with an error frame, never success: the
     * CLI blocks on an error reply only, so a success would let the action run.
     */
    @SuppressWarnings({ "unchecked", "null" })
    @ParameterizedTest
    @ValueSource(strings = { "throws", "fails_future", "completes_null", "null_future" })
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void failedHookSendsErrorResponse(String outcome) throws Exception {
        HookMatcher.HookCallback failing = (input, context) -> {
            switch (outcome) {
                case "throws":
                    throw new IllegalStateException("policy service down");
                case "fails_future":
                    return CompletableFuture.failedFuture(new IllegalStateException("policy service down"));
                case "completes_null":
                    return CompletableFuture.completedFuture(null);
                default:
                    return null;
            }
        };
        ScriptedTransport transport = new ScriptedTransport(Map.of("hook_on_failure_supported", true));
        QueryHandler handler = handlerFor(transport,
                new HookMatcher(null, List.of(failing), null, HookOnFailure.BLOCK));
        handler.initialize();

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
        request.put("callback_id", ((List<String>) firstMatcherSent(transport).get("hookCallbackIds")).get(0));
        request.put("input", input);
        request.put("tool_use_id", "toolu_1");
        transport.push(Map.of("type", "control_request", "request_id", "test-failed-hook", "request", request));

        Map<String, Object> response = null;
        while (response == null) {
            for (String written : transport.writes()) {
                Map<String, Object> frame = MAPPER.readValue(written, Map.class);
                if ("control_response".equals(frame.get("type"))) {
                    response = (Map<String, Object>) frame.get("response");
                }
            }
            if (response == null) {
                Thread.sleep(20);
            }
        }
        assertThat(response).containsEntry("subtype", "error").containsEntry("request_id", "test-failed-hook");
    }

    /** Answers every control request with a success carrying {@code reply}. */
    static class ScriptedTransport implements Transport {

        private final Map<String, Object> reply;
        private final LinkedBlockingQueue<Map<String, Object>> inbound = new LinkedBlockingQueue<>();
        private final List<String> writes = new ArrayList<>();
        private final AtomicBoolean ready = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        ScriptedTransport(Map<String, Object> reply) {
            this.reply = reply;
        }

        List<String> writes() {
            synchronized (writes) {
                return List.copyOf(writes);
            }
        }

        void push(Map<String, Object> frame) {
            inbound.add(frame);
        }

        @Override
        public void connect() {
            ready.set(true);
        }

        @Override
        public Iterator<Map<String, Object>> readMessages() {
            return new Iterator<>() {
                private Map<String, Object> pending;

                @Override
                public boolean hasNext() {
                    while (pending == null && !closed.get()) {
                        try {
                            pending = inbound.poll(100, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                    return pending != null;
                }

                @Override
                public Map<String, Object> next() {
                    if (pending == null) {
                        throw new NoSuchElementException();
                    }
                    Map<String, Object> next = pending;
                    pending = null;
                    return next;
                }
            };
        }

        @SuppressWarnings({ "unchecked", "null" })
        @Override
        public void write(String data) throws CLIConnectionException {
            if (closed.get()) {
                throw new CLIConnectionException("Transport closed");
            }
            synchronized (writes) {
                writes.add(data);
            }
            try {
                Map<String, Object> frame = MAPPER.readValue(data, Map.class);
                if (!"control_request".equals(frame.get("type"))) {
                    return;
                }
                Map<String, Object> response = new HashMap<>();
                response.put("subtype", "success");
                response.put("request_id", frame.get("request_id"));
                response.put("response", reply);
                inbound.add(Map.of("type", "control_response", "response", response));
            } catch (Exception e) {
                throw new CLIConnectionException("Bad frame: " + e.getMessage(), e);
            }
        }

        @Override
        public void endInput() {
            // no-op
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
