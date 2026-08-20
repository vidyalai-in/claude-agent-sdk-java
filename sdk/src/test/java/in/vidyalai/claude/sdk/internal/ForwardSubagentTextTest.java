package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.transport.Transport;

/**
 * The {@code forwardSubagentText} option reaches the CLI on the
 * {@code initialize} control request (Python SDK #1206).
 *
 * <p>
 * There is no CLI flag for it: like {@code agents} and {@code skills}, it rides
 * on the initialize request, and it is sent only when enabled so older CLIs
 * see exactly the request they saw before.
 */
class ForwardSubagentTextTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<QueryHandler> handlersToClose = new ArrayList<>();
    private final List<Transport> transportsToClose = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (QueryHandler h : handlersToClose) {
            try {
                h.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
        handlersToClose.clear();
        for (Transport t : transportsToClose) {
            try {
                t.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
        transportsToClose.clear();
    }

    @SuppressWarnings({ "unchecked", "null" })
    private Map<String, Object> initializeRequestFor(boolean forwardSubagentText) throws Exception {
        RespondingTransport transport = new RespondingTransport();
        transportsToClose.add(transport);

        QueryHandler handler = new QueryHandler(
                transport, true, null, null, null, null, null, null,
                forwardSubagentText, Duration.ofSeconds(10), null);
        handlersToClose.add(handler);
        transport.connect();
        handler.start();
        handler.initialize();

        String written = transport.firstWrite();
        assertThat(written).as("initialize must be written to stdin").isNotNull();
        Map<String, Object> frame = MAPPER.readValue(written, Map.class);
        return (Map<String, Object>) frame.get("request");
    }

    @Test
    void enabled_sendsForwardSubagentTextOnInitialize() throws Exception {
        Map<String, Object> request = initializeRequestFor(true);

        assertThat(request).containsEntry("subtype", "initialize");
        assertThat(request).containsEntry("forwardSubagentText", true);
    }

    @Test
    void disabled_omitsTheFieldEntirely() throws Exception {
        Map<String, Object> request = initializeRequestFor(false);

        assertThat(request).containsEntry("subtype", "initialize");
        // Omitted rather than sent as false: the CLI's default is "off", and
        // older CLIs should see the request unchanged.
        assertThat(request).doesNotContainKey("forwardSubagentText");
    }

    /**
     * Drives the option end to end through {@link ClaudeSDK#query} rather than
     * through the {@code QueryHandler} constructor, so that a facade that
     * forgets to pass it through is caught.
     */
    @SuppressWarnings({ "unchecked", "null" })
    private Map<String, Object> initializeRequestForQuery(boolean forwardSubagentText) throws Exception {
        RespondingTransport transport = new RespondingTransport();
        transportsToClose.add(transport);

        ClaudeSDK.query("hi", ClaudeAgentOptions.builder()
                .forwardSubagentText(forwardSubagentText)
                .build(), transport);

        Map<String, Object> frame = MAPPER.readValue(transport.firstWrite(), Map.class);
        return (Map<String, Object>) frame.get("request");
    }

    @Test
    void optionReachesInitializeThroughQuery() throws Exception {
        assertThat(initializeRequestForQuery(true))
                .containsEntry("subtype", "initialize")
                .containsEntry("forwardSubagentText", true);
        assertThat(initializeRequestForQuery(false))
                .containsEntry("subtype", "initialize")
                .doesNotContainKey("forwardSubagentText");
    }

    /** The same, through {@link ClaudeSDKClient#connect()}. */
    @SuppressWarnings({ "unchecked", "null" })
    private Map<String, Object> initializeRequestForClient(boolean forwardSubagentText) throws Exception {
        RespondingTransport transport = new RespondingTransport();
        transportsToClose.add(transport);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .forwardSubagentText(forwardSubagentText)
                .build();
        try (ClaudeSDKClient client = new ClaudeSDKClient(options, transport)) {
            client.connect();
        }

        Map<String, Object> frame = MAPPER.readValue(transport.firstWrite(), Map.class);
        return (Map<String, Object>) frame.get("request");
    }

    @Test
    void optionReachesInitializeThroughTheClient() throws Exception {
        assertThat(initializeRequestForClient(true))
                .containsEntry("subtype", "initialize")
                .containsEntry("forwardSubagentText", true);
        assertThat(initializeRequestForClient(false))
                .containsEntry("subtype", "initialize")
                .doesNotContainKey("forwardSubagentText");
    }

    /**
     * Transport that records what the handler writes and answers the first
     * control request with a success response so {@code initialize()} returns.
     */
    static class RespondingTransport implements Transport {

        private final LinkedBlockingQueue<Map<String, Object>> inbound = new LinkedBlockingQueue<>();
        private final List<String> writes = new ArrayList<>();
        private final AtomicBoolean ready = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        String firstWrite() {
            synchronized (writes) {
                return writes.isEmpty() ? null : writes.get(0);
            }
        }

        @Override
        public void connect() {
            ready.set(true);
        }

        @Override
        public Iterator<Map<String, Object>> readMessages() {
            return new Iterator<>() {
                private Map<String, Object> pending;

                private boolean done = false;

                @Override
                public boolean hasNext() {
                    while (pending == null && !done && !closed.get()) {
                        try {
                            pending = inbound.poll(100, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                    if (pending != null && "result".equals(pending.get("type"))) {
                        // The CLI exits after the run-ending result.
                        done = true;
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
                    // A prompt: answer it so a facade-level query() terminates.
                    emitTurn();
                    return;
                }
                Map<String, Object> response = new HashMap<>();
                response.put("subtype", "success");
                response.put("request_id", frame.get("request_id"));
                response.put("response", Map.of("commands", List.of()));

                Map<String, Object> envelope = new HashMap<>();
                envelope.put("type", "control_response");
                envelope.put("response", response);
                inbound.add(envelope);
            } catch (Exception e) {
                throw new CLIConnectionException("Bad frame: " + e.getMessage(), e);
            }
        }

        private void emitTurn() {
            Map<String, Object> assistant = new HashMap<>();
            assistant.put("type", "assistant");
            assistant.put("session_id", "s");
            assistant.put("message", Map.of(
                    "role", "assistant",
                    "model", "claude-sonnet-4-5",
                    "content", List.of(Map.of("type", "text", "text", "ok"))));
            inbound.add(assistant);

            Map<String, Object> result = new HashMap<>();
            result.put("type", "result");
            result.put("subtype", "success");
            result.put("is_error", false);
            result.put("num_turns", 1);
            result.put("session_id", "s");
            result.put("duration_ms", 1);
            result.put("duration_api_ms", 1);
            result.put("total_cost_usd", 0.0);
            inbound.add(result);
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
