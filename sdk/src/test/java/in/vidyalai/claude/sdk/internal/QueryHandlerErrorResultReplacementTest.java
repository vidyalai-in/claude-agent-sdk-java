package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.exceptions.ClaudeSDKException;
import in.vidyalai.claude.sdk.exceptions.ProcessException;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.message.Message;

/**
 * Regression tests for the post-error-result {@code ProcessException}
 * replacement in {@link QueryHandler#readMessages()} (Python SDK v0.1.77 #918).
 *
 * <p>
 * When the CLI emits a {@code result} with {@code is_error=true} (e.g.
 * {@code error_max_turns}, {@code error_during_execution}) it then exits
 * non-zero on purpose. The trailing {@code ProcessException} carries no
 * information beyond "exit code 1" — the SDK replaces it with the
 * structured error text the CLI already reported so the exception is
 * actionable.
 *
 * <p>
 * The replacement surfaces to the consumer iterator as a
 * {@link ClaudeSDKException} thrown from {@code hasNext()} with the
 * replaced text as the message.
 */
class QueryHandlerErrorResultReplacementTest {

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

    private Map<String, Object> errorResult(String subtype, List<String> errors) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "result");
        msg.put("subtype", subtype);
        msg.put("is_error", true);
        msg.put("num_turns", 1);
        msg.put("session_id", "s");
        msg.put("duration_ms", 1);
        msg.put("duration_api_ms", 1);
        msg.put("total_cost_usd", 0.0);
        if (errors != null) {
            msg.put("errors", errors);
        }
        return msg;
    }

    private Map<String, Object> successResult() {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "result");
        msg.put("subtype", "success");
        msg.put("is_error", false);
        msg.put("num_turns", 1);
        msg.put("session_id", "s");
        msg.put("duration_ms", 1);
        msg.put("duration_api_ms", 1);
        msg.put("total_cost_usd", 0.0);
        return msg;
    }

    /**
     * Drains the iterator, returning the {@link ClaudeSDKException} thrown by
     * {@code hasNext()} when the synthetic error message is dequeued.
     */
    private ClaudeSDKException drainUntilError(QueryHandler handler) {
        Iterator<Message> it = handler.receiveMessages();
        try {
            while (it.hasNext()) {
                it.next();
            }
        } catch (ClaudeSDKException e) {
            return e;
        }
        return null;
    }

    @Test
    void processExceptionAfterErrorResult_replacesWithJoinedErrors() {
        ScriptedTransport transport = new ScriptedTransport(
                List.of(errorResult("error_max_turns",
                        List.of("Reached maximum number of turns (60)"))),
                new ProcessException("Command failed with exit code 1", 1, ""));
        transportsToClose.add(transport);

        QueryHandler handler = new QueryHandler(
                transport, true, null, null, Duration.ofSeconds(60));
        handlersToClose.add(handler);
        transport.connect();
        handler.start();

        ClaudeSDKException ex = drainUntilError(handler);
        assertThat(ex).isNotNull();
        assertThat(ex.getMessage())
                .isEqualTo("Claude Code returned an error result: "
                        + "Reached maximum number of turns (60)");
    }

    @Test
    void processExceptionAfterErrorResult_fallsBackToSubtype() {
        ScriptedTransport transport = new ScriptedTransport(
                List.of(errorResult("error_during_execution", null)),
                new ProcessException("Command failed with exit code 1", 1, ""));
        transportsToClose.add(transport);

        QueryHandler handler = new QueryHandler(
                transport, true, null, null, Duration.ofSeconds(60));
        handlersToClose.add(handler);
        transport.connect();
        handler.start();

        ClaudeSDKException ex = drainUntilError(handler);
        assertThat(ex).isNotNull();
        assertThat(ex.getMessage())
                .isEqualTo("Claude Code returned an error result: error_during_execution");
    }

    @Test
    void processExceptionAfterErrorResult_joinsMultipleErrorsWithSemicolon() {
        ScriptedTransport transport = new ScriptedTransport(
                List.of(errorResult("error_during_execution",
                        List.of("tool timed out", "ENOENT: missing file"))),
                new ProcessException("Command failed with exit code 1", 1, ""));
        transportsToClose.add(transport);

        QueryHandler handler = new QueryHandler(
                transport, true, null, null, Duration.ofSeconds(60));
        handlersToClose.add(handler);
        transport.connect();
        handler.start();

        ClaudeSDKException ex = drainUntilError(handler);
        assertThat(ex).isNotNull();
        assertThat(ex.getMessage())
                .isEqualTo("Claude Code returned an error result: "
                        + "tool timed out; ENOENT: missing file");
    }

    @Test
    void processExceptionWithoutPriorResult_keepsOriginalMessage() {
        ScriptedTransport transport = new ScriptedTransport(
                List.of(),
                new ProcessException("Command failed with exit code 1", 1, ""));
        transportsToClose.add(transport);

        QueryHandler handler = new QueryHandler(
                transport, true, null, null, Duration.ofSeconds(60));
        handlersToClose.add(handler);
        transport.connect();
        handler.start();

        ClaudeSDKException ex = drainUntilError(handler);
        assertThat(ex).isNotNull();
        // ProcessException's constructor appends " (exit code: N)" — assert the
        // original "Command failed with exit code 1" prefix survives intact.
        assertThat(ex.getMessage()).startsWith("Command failed with exit code 1");
        assertThat(ex.getMessage()).doesNotStartWith("Claude Code returned an error result");
    }

    @Test
    void processExceptionAfterSuccessResult_keepsOriginalMessage() {
        ScriptedTransport transport = new ScriptedTransport(
                List.of(successResult()),
                new ProcessException("Command failed with exit code 1", 1, ""));
        transportsToClose.add(transport);

        QueryHandler handler = new QueryHandler(
                transport, true, null, null, Duration.ofSeconds(60));
        handlersToClose.add(handler);
        transport.connect();
        handler.start();

        ClaudeSDKException ex = drainUntilError(handler);
        assertThat(ex).isNotNull();
        // ProcessException's constructor appends " (exit code: N)" — assert the
        // original "Command failed with exit code 1" prefix survives intact.
        assertThat(ex.getMessage()).startsWith("Command failed with exit code 1");
        assertThat(ex.getMessage()).doesNotStartWith("Claude Code returned an error result");
    }

    @Test
    void sessionStateChangedAfterErrorResult_preservesReplacement() {
        // The CLI emits a post-turn `system: session_state_changed(idle)`
        // marker after the result and before exit. It must NOT reset the
        // tracking flag — the conversation hasn't moved on.
        Map<String, Object> stateChange = new HashMap<>();
        stateChange.put("type", "system");
        stateChange.put("subtype", "session_state_changed");
        stateChange.put("state", "idle");
        stateChange.put("session_id", "s");

        ScriptedTransport transport = new ScriptedTransport(
                List.of(
                        errorResult("error_max_turns",
                                List.of("Reached maximum number of turns (10)")),
                        stateChange),
                new ProcessException("Command failed with exit code 1", 1, ""));
        transportsToClose.add(transport);

        QueryHandler handler = new QueryHandler(
                transport, true, null, null, Duration.ofSeconds(60));
        handlersToClose.add(handler);
        transport.connect();
        handler.start();

        ClaudeSDKException ex = drainUntilError(handler);
        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).startsWith("Claude Code returned an error result:");
    }

    @Test
    void newTurnAfterErrorResult_keepsOriginalMessage() {
        // A new user turn invalidates the 'expecting imminent exit' state from
        // a prior turn's error result; a crash mid-new-turn must surface as-is.
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("type", "user");
        userMsg.put("uuid", "u-1");
        userMsg.put("message", Map.of("role", "user", "content", "next turn"));
        userMsg.put("session_id", "s");

        ScriptedTransport transport = new ScriptedTransport(
                List.of(
                        errorResult("error_during_execution", null),
                        userMsg),
                new ProcessException("Command failed with exit code 1", 1, ""));
        transportsToClose.add(transport);

        QueryHandler handler = new QueryHandler(
                transport, true, null, null, Duration.ofSeconds(60));
        handlersToClose.add(handler);
        transport.connect();
        handler.start();

        ClaudeSDKException ex = drainUntilError(handler);
        assertThat(ex).isNotNull();
        // ProcessException's constructor appends " (exit code: N)" — assert the
        // original "Command failed with exit code 1" prefix survives intact.
        assertThat(ex.getMessage()).startsWith("Command failed with exit code 1");
        assertThat(ex.getMessage()).doesNotStartWith("Claude Code returned an error result");
    }

    @SuppressWarnings("null")
    @Test
    void hasNextThrowsClaudeSDKException_notProcessException() {
        // The replaced exception type should still be raisable as a generic
        // ClaudeSDKException by the consumer iterator.
        ScriptedTransport transport = new ScriptedTransport(
                List.of(errorResult("error_max_turns", List.of("oops"))),
                new ProcessException("Command failed with exit code 1", 1, ""));
        transportsToClose.add(transport);

        QueryHandler handler = new QueryHandler(
                transport, true, null, null, Duration.ofSeconds(60));
        handlersToClose.add(handler);
        transport.connect();
        handler.start();

        Iterator<Message> it = handler.receiveMessages();
        assertThatThrownBy(() -> {
            while (it.hasNext()) {
                it.next();
            }
        })
                .isInstanceOf(ClaudeSDKException.class)
                .hasMessage("Claude Code returned an error result: oops");
    }

    @Test
    void pendingInitializeGetsResultErrorText() {
        // An error result emitted during CLI startup (e.g. a resume refused by
        // --resume-drops-turn) arrives before the initialize response. The
        // in-flight initialize must fail with the same actionable text, not the
        // bare exit code.
        GatedTransport transport = new GatedTransport(
                List.of(errorResult("error_during_execution",
                        List.of("Resume rejected by --resume-drops-turn: nope"))),
                new ProcessException("Command failed with exit code 1", 1, ""));
        transportsToClose.add(transport);

        QueryHandler handler = new QueryHandler(
                transport, true, null, null, Duration.ofSeconds(60));
        handlersToClose.add(handler);
        transport.connect();
        handler.start();

        assertThatThrownBy(handler::initialize)
                .isInstanceOf(ClaudeSDKException.class)
                .hasMessageContaining("Claude Code returned an error result: "
                        + "Resume rejected by --resume-drops-turn: nope")
                .hasMessageNotContaining("Command failed with exit code 1");
    }

    /**
     * A {@link ScriptedTransport} whose read loop is held until the first
     * {@code write()} — i.e. until the control request under test is actually
     * pending. Without the gate the reader can fail before {@code initialize()}
     * registers its future, and the test would pass for the wrong reason.
     */
    static class GatedTransport extends ScriptedTransport {

        private final java.util.concurrent.CountDownLatch written =
                new java.util.concurrent.CountDownLatch(1);

        GatedTransport(List<Map<String, Object>> messages, RuntimeException terminalException) {
            super(messages, terminalException);
        }

        @Override
        public Iterator<Map<String, Object>> readMessages() {
            Iterator<Map<String, Object>> delegate = super.readMessages();
            return new Iterator<>() {
                private boolean gateOpened = false;

                @Override
                public boolean hasNext() {
                    if (!gateOpened) {
                        try {
                            written.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        gateOpened = true;
                    }
                    return delegate.hasNext();
                }

                @Override
                public Map<String, Object> next() {
                    return delegate.next();
                }
            };
        }

        @Override
        public void write(String data) throws CLIConnectionException {
            super.write(data);
            written.countDown();
        }
    }

    /**
     * Mock transport that yields a fixed list of messages then throws on the
     * next {@code hasNext()} call, mirroring how the real
     * {@code SubprocessCLITransport} surfaces a {@code ProcessException}
     * after the CLI process exits.
     */
    static class ScriptedTransport implements Transport {

        private final List<Map<String, Object>> messages;
        private final RuntimeException terminalException;
        private final AtomicBoolean ready = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        ScriptedTransport(List<Map<String, Object>> messages, RuntimeException terminalException) {
            this.messages = messages;
            this.terminalException = terminalException;
        }

        @Override
        public void connect() {
            ready.set(true);
        }

        @Override
        public Iterator<Map<String, Object>> readMessages() {
            return new Iterator<>() {
                private int idx = 0;

                @Override
                public boolean hasNext() {
                    if (idx < messages.size()) {
                        return true;
                    }
                    // Mirror SubprocessCLITransport: throw on the post-message
                    // hasNext() call to surface the CLI's exit error.
                    if (terminalException != null) {
                        throw terminalException;
                    }
                    return false;
                }

                @Override
                public Map<String, Object> next() {
                    if (idx >= messages.size()) {
                        throw new NoSuchElementException();
                    }
                    return messages.get(idx++);
                }
            };
        }

        @Override
        public void write(String data) throws CLIConnectionException {
            if (closed.get()) {
                throw new CLIConnectionException("Transport closed");
            }
        }

        @Override
        public void endInput() {
            // No-op
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
