package in.vidyalai.claude.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.exceptions.ProcessException;
import in.vidyalai.claude.sdk.exceptions.QueryFailedException;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;

/**
 * The collecting {@link ClaudeSDK#query(String)} family must not lose the
 * messages it already gathered when a run ends in an error result.
 *
 * <p>
 * The CLI reports {@code error_max_turns} / {@code error_max_budget_usd} by
 * emitting a full turn, including a final {@link ResultMessage} carrying the
 * subtype and cost, and only then exiting non-zero. A streaming consumer sees
 * every one of those messages before the raise — as does Python, whose
 * {@code query()} is a generator. The collecting API used to rethrow bare and
 * drop the list, leaving the caller with nothing but an error string.
 */
class ClaudeSDKQueryFailureTest {

    private static final String ERROR_TEXT =
            "Claude Code returned an error result: Reached maximum budget ($0.0001)";

    @SuppressWarnings("null")
    @Test
    void errorResult_carriesCollectedMessagesOnTheException() {
        try (ScriptedTransport transport = new ScriptedTransport("error_max_budget_usd")) {
            QueryFailedException failure = catchThrowableOfType(
                    () -> ClaudeSDK.query("hi", ClaudeAgentOptions.defaults(), transport),
                    QueryFailedException.class);

            assertThat(failure).as("collecting query must surface the error").isNotNull();
            assertThat(failure.getMessage()).isEqualTo(ERROR_TEXT);

            // ...and the turn that preceded it is still available.
            List<Message> partial = failure.partialMessages();
            assertThat(partial).hasSize(2);
            assertThat(partial.get(0)).isInstanceOf(AssistantMessage.class);
            assertThat(((AssistantMessage) partial.get(0)).getTextContent())
                    .isEqualTo("working on it");
            assertThat(partial.get(1)).isInstanceOf(ResultMessage.class);

            // The convenience accessor finds the final result directly.
            ResultMessage result = failure.resultMessage();
            assertThat(result).isNotNull();
            assertThat(result.subtype()).isEqualTo("error_max_budget_usd");
            assertThat(result.isError()).isTrue();
            assertThat(result.totalCostUsd()).isEqualTo(0.25);
        }
    }

    @Test
    void partialMessagesAreUnmodifiable() {
        try (ScriptedTransport transport = new ScriptedTransport("error_max_turns")) {
            QueryFailedException failure = catchThrowableOfType(
                    () -> ClaudeSDK.query("hi", ClaudeAgentOptions.defaults(), transport),
                    QueryFailedException.class);

            assertThat(failure).isNotNull();
            assertThatThrownBy(() -> failure.partialMessages().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @SuppressWarnings("null")
    @Test
    void queryFailedException_isAClaudeSDKException() {
        // Callers that already catch the base type keep working.
        try (ScriptedTransport transport = new ScriptedTransport("error_max_turns")) {
            assertThatThrownBy(
                    () -> ClaudeSDK.query("hi", ClaudeAgentOptions.defaults(), transport))
                            .isInstanceOf(in.vidyalai.claude.sdk.exceptions.ClaudeSDKException.class)
                            .hasMessage(ERROR_TEXT);
        }
    }

    // ------------------------------------------------------------------

    /**
     * Yields one assistant message and one error result, answers the control
     * protocol's {@code initialize}, then throws like the real transport does
     * when the CLI exits non-zero.
     */
    static class ScriptedTransport implements Transport {

        private final ObjectMapper mapper = new ObjectMapper();
        private final BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
        private final AtomicBoolean scriptDelivered = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final String resultSubtype;

        ScriptedTransport(String resultSubtype) {
            this.resultSubtype = resultSubtype;
        }

        @Override
        public void connect() {
            // Nothing to start.
        }

        @Override
        public void write(String data) throws CLIConnectionException {
            if (!data.contains("\"type\":\"control_request\"")) {
                return;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> frame = mapper.readValue(data, Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> request = (Map<String, Object>) frame.get("request");
                Map<String, Object> inner = new HashMap<>();
                inner.put("subtype", "success");
                inner.put("request_id", frame.get("request_id"));
                if ("initialize".equals(request.get("subtype"))) {
                    inner.put("commands", List.of());
                    inner.put("output_style", "default");
                }
                Map<String, Object> response = new HashMap<>();
                response.put("type", "control_response");
                response.put("response", inner);
                queue.offer(response);

                if ("initialize".equals(request.get("subtype"))) {
                    // The turn the CLI produces before it gives up.
                    queue.offer(assistantMessage());
                    queue.offer(errorResult());
                    scriptDelivered.set(true);
                }
            } catch (Exception e) {
                throw new CLIConnectionException("mock write failed", e);
            }
        }

        private Map<String, Object> assistantMessage() {
            Map<String, Object> inner = new HashMap<>();
            inner.put("role", "assistant");
            inner.put("content", List.of(Map.of("type", "text", "text", "working on it")));
            inner.put("model", "claude-sonnet-4-5");
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "assistant");
            msg.put("message", inner);
            return msg;
        }

        private Map<String, Object> errorResult() {
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "result");
            msg.put("subtype", resultSubtype);
            msg.put("is_error", true);
            msg.put("num_turns", 1);
            msg.put("session_id", "s");
            msg.put("duration_ms", 1);
            msg.put("duration_api_ms", 1);
            msg.put("total_cost_usd", 0.25);
            msg.put("errors", List.of("Reached maximum budget ($0.0001)"));
            return msg;
        }

        @Override
        public Iterator<Map<String, Object>> readMessages() {
            return new Iterator<>() {

                private Map<String, Object> next = null;
                private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);

                @Override
                public boolean hasNext() {
                    if (next != null) {
                        return true;
                    }
                    while (System.nanoTime() < deadline) {
                        try {
                            next = queue.poll(50, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                        if (next != null) {
                            return true;
                        }
                        if (closed.get()) {
                            return false;
                        }
                        if (scriptDelivered.get()) {
                            // Mirror the real transport: the CLI exited non-zero
                            // after reporting its error result.
                            throw new ProcessException("Command failed with exit code 1", 1, "");
                        }
                    }
                    return false;
                }

                @Override
                public Map<String, Object> next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    Map<String, Object> value = next;
                    next = null;
                    return value;
                }
            };
        }

        @Override
        public void endInput() {
            // No-op.
        }

        @Override
        public boolean isReady() {
            return !closed.get();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

}
