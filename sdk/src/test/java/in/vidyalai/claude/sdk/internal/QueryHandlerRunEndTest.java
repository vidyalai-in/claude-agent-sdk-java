package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookMatcher;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.SystemMessage;

/**
 * When {@link QueryHandler#streamInput} closes stdin on a run that serves
 * control requests (Python SDK #1279, issue #1190).
 *
 * <p>
 * A background agent that finishes just before the turn's result leaves
 * nothing in flight at that result, yet its completion still wakes the parent
 * for a follow-up turn whose hook, permission and SDK MCP requests need stdin.
 * A CLI asked for session state (CLAUDE_CODE_SDK_READS_SESSION_STATE) reports
 * "running" until no such turn is owed, then "idle"; the wait for "idle" is
 * bounded between turns by CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS.
 */
class QueryHandlerRunEndTest {

    /** Bound for waiting on something that should happen. */
    private static final long EXPECT_MS = 5000;

    /**
     * How long a check that stdin is still open waits after the reader has
     * processed every frame, so a waiter that was about to close it has had
     * the chance to.
     */
    private static final long SETTLE_MS = 150;

    /** A short ceiling for the tests that exercise it. */
    private static final long CEILING_MS = 300;

    /** Comfortably past {@link #CEILING_MS}. */
    private static final long PAST_CEILING_MS = 900;

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

    // --- frame builders ---------------------------------------------------

    /**
     * A session_state_changed frame, marked the way a CLI marks the frames it
     * sends only because the SDK asked for them unless {@code sdkHostOnly} is
     * false.
     */
    private static Map<String, Object> sessionState(String state, boolean sdkHostOnly) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "system");
        msg.put("subtype", "session_state_changed");
        msg.put("state", state);
        msg.put("uuid", "uuid-state-" + state);
        msg.put("session_id", "s");
        if (sdkHostOnly) {
            msg.put("sdk_host_only", true);
        }
        return msg;
    }

    private static Map<String, Object> sessionState(String state) {
        return sessionState(state, true);
    }

    private static Map<String, Object> result() {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "result");
        msg.put("subtype", "success");
        msg.put("is_error", false);
        msg.put("num_turns", 1);
        msg.put("session_id", "s");
        msg.put("duration_ms", 1);
        msg.put("duration_api_ms", 1);
        return msg;
    }

    private static Map<String, Object> assistant(String parentToolUseId) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "assistant");
        msg.put("session_id", "s");
        msg.put("parent_tool_use_id", parentToolUseId);
        msg.put("message", Map.of(
                "role", "assistant",
                "model", "claude-sonnet-5",
                "content", List.of(Map.of("type", "text", "text", "ok"))));
        return msg;
    }

    private static Map<String, Object> taskStarted() {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "system");
        msg.put("subtype", "task_started");
        msg.put("session_id", "s");
        msg.put("task_id", "t1");
        msg.put("task_type", "local_agent");
        msg.put("description", "background agent");
        msg.put("uuid", "uuid-task-started");
        return msg;
    }

    private static Map<String, Object> taskNotification() {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "system");
        msg.put("subtype", "task_notification");
        msg.put("session_id", "s");
        msg.put("task_id", "t1");
        msg.put("status", "completed");
        msg.put("output_file", "/tmp/t1.out");
        msg.put("summary", "done");
        msg.put("uuid", "uuid-task-notification");
        return msg;
    }

    private static Map<String, Object> userPrompt(String text) {
        return Map.of(
                "type", "user",
                "session_id", "",
                "message", Map.of("role", "user", "content", text));
    }

    // --- harness ----------------------------------------------------------

    /**
     * Starts a handler with a hook registered (so {@code streamInput} performs
     * the bidirectional wait) and drives {@code streamInput} over
     * {@code prompt} on its own thread.
     */
    @SuppressWarnings("null")
    private ScriptedTransport start(Iterator<Map<String, Object>> prompt, long ceilingMs) {
        ScriptedTransport transport = new ScriptedTransport();
        transportsToClose.add(transport);

        Map<HookEvent, List<HookMatcher>> hooks = Map.of(
                HookEvent.PRE_TOOL_USE,
                List.of(new HookMatcher(null, List.of(
                        (input, context) -> CompletableFuture.completedFuture(null)))));

        QueryHandler handler = new QueryHandler(
                transport, true, null, hooks, null, null, null, null, null,
                false, false, ceilingMs, Duration.ofSeconds(60), null);
        handlersToClose.add(handler);
        transport.handler = handler;
        transport.connect();
        handler.start();
        Threads.start("QueryHandlerRunEndTest-streamInput-", () -> handler.streamInput(prompt));
        return transport;
    }

    /** One prompt, and the first frame fed only after it was written. */
    private ScriptedTransport startOnePrompt(long ceilingMs) {
        ScriptedTransport transport = start(List.of(userPrompt("Hello")).iterator(), ceilingMs);
        transport.awaitWrites(1);
        return transport;
    }

    private ScriptedTransport startOnePrompt() {
        return startOnePrompt(0);
    }

    private static void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EXPECT_MS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + EXPECT_MS + "ms");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    // --- stdin stays open until "idle" -------------------------------------

    @Nested
    class StdinStaysOpenUntilIdle {

        @Test
        void taskSettledBeforeResult_keepsStdinOpenUntilIdle() throws Exception {
            ScriptedTransport transport = startOnePrompt();

            transport.feed(sessionState("running"));
            transport.feed(taskStarted());
            transport.feed(taskNotification());
            transport.feed(result());
            assertThat(transport.openAfterDrain()).as("open after first result").isTrue();

            transport.feed(result());
            assertThat(transport.openAfterDrain()).as("open after second result").isTrue();

            transport.feed(sessionState("idle"));
            assertThat(transport.awaitClosed()).as("closed at idle").isTrue();

            List<Message> messages = transport.finishAndCollect();
            assertThat(messages).filteredOn(ResultMessage.class::isInstance).hasSize(2);
            // The CLI marked these frames sdk_host_only, so the caller never
            // sees them.
            assertThat(stateFrames(messages)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(booleans = { true, false })
        void onlyMarkedFramesAreDropped(boolean sdkHostOnly) throws Exception {
            // Marked frames were sent only because the SDK asked; unmarked ones
            // mean the caller opted in (CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS),
            // and both drive the run end the same way.
            ScriptedTransport transport = startOnePrompt();

            transport.feed(sessionState("running", sdkHostOnly));
            transport.feed(result());
            assertThat(transport.openAfterDrain()).isTrue();

            transport.feed(sessionState("idle", sdkHostOnly));
            assertThat(transport.awaitClosed()).isTrue();

            assertThat(stateFrames(transport.finishAndCollect()))
                    .isEqualTo(sdkHostOnly ? List.of() : List.of("running", "idle"));
        }

        @Test
        void noStateFrames_closesStdinAtTheFirstResult() throws Exception {
            // A CLI too old to honor CLAUDE_CODE_SDK_READS_SESSION_STATE sends
            // no frames; the result is then all there is to go on.
            ScriptedTransport transport = startOnePrompt();

            transport.feed(assistant(null));
            transport.feed(result());
            assertThat(transport.awaitClosed()).isTrue();
        }

        @Test
        void idleJustBeforeResult_endsTheRunAtTheResult() throws Exception {
            ScriptedTransport transport = startOnePrompt();

            transport.feed(sessionState("running"));
            transport.feed(sessionState("idle"));
            assertThat(transport.openAfterDrain()).as("open before result").isTrue();

            transport.feed(result());
            assertThat(transport.awaitClosed()).as("closed at result").isTrue();
        }

        @Test
        void idleBeforeAnyResult_doesNotEndTheRun() throws Exception {
            ScriptedTransport transport = startOnePrompt();

            transport.feed(sessionState("idle"));
            transport.feed(sessionState("running"));
            transport.feed(result());
            assertThat(transport.openAfterDrain()).isTrue();

            transport.feed(sessionState("idle"));
            assertThat(transport.awaitClosed()).isTrue();
        }

        @Test
        void idleWithATrackedTaskInFlight_keepsStdinOpen() throws Exception {
            // A CLI that reports "idle" at every turn end still defers to the
            // task ledger (#1088).
            ScriptedTransport transport = startOnePrompt();

            transport.feed(sessionState("running"));
            transport.feed(taskStarted());
            transport.feed(result());
            transport.feed(sessionState("idle"));
            assertThat(transport.openAfterDrain()).as("open with task in flight").isTrue();

            transport.feed(taskNotification());
            transport.feed(sessionState("running"));
            transport.feed(result());
            transport.feed(sessionState("idle"));
            assertThat(transport.awaitClosed()).as("closed after task settled").isTrue();
        }

        @Test
        void streamLastPrompt_waitsForItsOwnRun() throws Exception {
            // A prompt written after an earlier prompt's run ended owes a run
            // of its own: its turn's requests need stdin too.
            ControllablePrompt prompt = new ControllablePrompt();
            prompt.offer(userPrompt("first"));
            ScriptedTransport transport = start(prompt, 0);
            transport.awaitWrites(1);

            transport.feed(sessionState("running"));
            transport.feed(result());
            transport.feed(sessionState("idle"));
            transport.awaitDrained();

            prompt.offer(userPrompt("second"));
            prompt.finish();
            transport.awaitWrites(2);
            assertThat(transport.openAfterDrain()).as("open after second prompt").isTrue();

            transport.feed(sessionState("running"));
            transport.feed(result());
            assertThat(transport.openAfterDrain()).as("open after second result").isTrue();

            transport.feed(sessionState("idle"));
            assertThat(transport.awaitClosed()).as("closed at second idle").isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = { "running", "requires_action" })
        void streamReopensForWorkTheCliTakesUpAfterIdle(String wakeState) throws Exception {
            // A background task that wakes the CLI after the run ended reopens
            // it, as long as the caller's input had not ended yet.
            ControllablePrompt prompt = new ControllablePrompt();
            prompt.offer(userPrompt("Hello"));
            ScriptedTransport transport = start(prompt, 0);
            transport.awaitWrites(1);

            transport.feed(sessionState("running"));
            transport.feed(result());
            transport.feed(sessionState("idle"));
            transport.awaitDrained();

            transport.feed(sessionState(wakeState));
            transport.awaitDrained();
            prompt.finish();
            assertThat(transport.openAfterDrain()).as("open after input ended").isTrue();

            transport.feed(result());
            transport.feed(sessionState("idle"));
            assertThat(transport.awaitClosed()).as("closed at next idle").isTrue();
        }
    }

    // --- the ceiling -------------------------------------------------------

    @Nested
    class RunEndCeiling {

        @Test
        void ceilingEndsTheRunWithNoIdle() throws Exception {
            ScriptedTransport transport = startOnePrompt(CEILING_MS);

            transport.feed(sessionState("running"));
            transport.feed(result());
            transport.awaitDrained();
            assertThat(transport.endInputCount()).as("open right after result").isZero();

            assertThat(transport.awaitClosed()).isTrue();
            // A late idle does not close stdin a second time.
            transport.feed(sessionState("idle"));
            assertThat(transport.openAfterDrain()).isFalse();
            assertThat(transport.endInputCount()).isEqualTo(1);
        }

        @Test
        void mainThreadTurnStopsTheCeiling() throws Exception {
            ScriptedTransport transport = startOnePrompt(CEILING_MS);

            transport.feed(sessionState("running"));
            transport.feed(result());
            // The turn the finished background agent woke starts before the
            // ceiling, then runs well past it.
            transport.feed(assistant(null));
            transport.awaitDrained();
            sleep(PAST_CEILING_MS);
            assertThat(transport.endInputCount()).as("open during turn").isZero();

            // Its result starts the wait between turns over.
            transport.feed(result());
            transport.awaitDrained();
            assertThat(transport.endInputCount()).as("open right after second result").isZero();
            assertThat(transport.awaitClosed()).isTrue();
        }

        @Test
        void subagentMessagesDoNotStopTheCeiling() throws Exception {
            // Only main-thread activity is a new turn; a background agent's own
            // messages are the very work the ceiling bounds.
            ScriptedTransport transport = startOnePrompt(CEILING_MS);

            transport.feed(sessionState("running"));
            transport.feed(result());
            transport.feed(assistant("toolu_agent"));
            assertThat(transport.awaitClosed()).isTrue();
        }

        @Test
        void requiresActionStopsTheCeiling() throws Exception {
            ScriptedTransport transport = startOnePrompt(CEILING_MS);

            transport.feed(sessionState("running"));
            transport.feed(result());
            // A background agent's permission prompt waits on the host well
            // past the ceiling.
            transport.feed(sessionState("requires_action"));
            transport.awaitDrained();
            sleep(PAST_CEILING_MS);
            assertThat(transport.endInputCount()).as("open while answering").isZero();

            // Answered: the wait between turns starts over.
            transport.feed(sessionState("running"));
            transport.awaitDrained();
            assertThat(transport.endInputCount()).as("open right after answer").isZero();
            assertThat(transport.awaitClosed()).isTrue();
        }

        @Test
        void ceilingIsNotArmedMidTurn() throws Exception {
            // A "running" after an answered request inside a turn does not
            // start the clock; only the wait between turns counts.
            ScriptedTransport transport = startOnePrompt(CEILING_MS);

            transport.feed(sessionState("running"));
            transport.feed(result());
            // The follow-up turn starts, asks for a permission, gets it, then
            // runs a long tool with no main-thread output.
            transport.feed(assistant(null));
            transport.feed(sessionState("requires_action"));
            transport.feed(sessionState("running"));
            transport.awaitDrained();
            sleep(PAST_CEILING_MS);
            assertThat(transport.endInputCount()).as("open during turn").isZero();

            transport.feed(result());
            assertThat(transport.awaitClosed()).isTrue();
        }

        @Test
        void resultWhileAnsweringARequest_doesNotArm() throws Exception {
            // A background agent's request the SDK is still answering when the
            // turn's result arrives keeps the clock stopped until it is
            // answered.
            ScriptedTransport transport = startOnePrompt(CEILING_MS);

            transport.feed(sessionState("running"));
            transport.feed(sessionState("requires_action"));
            transport.feed(result());
            transport.awaitDrained();
            sleep(PAST_CEILING_MS);
            assertThat(transport.endInputCount()).as("open while answering").isZero();

            transport.feed(sessionState("running"));
            transport.awaitDrained();
            assertThat(transport.endInputCount()).as("open right after answer").isZero();
            assertThat(transport.awaitClosed()).isTrue();
        }

        @Test
        void ceilingLeavesATrackedAgentAlone() throws Exception {
            // A tracked background agent still in flight may still need stdin
            // (#1088), so the ceiling does not cut it off; the wait between
            // turns starts over once it settles.
            ScriptedTransport transport = startOnePrompt(CEILING_MS);

            transport.feed(sessionState("running"));
            transport.feed(taskStarted());
            transport.feed(result());
            transport.awaitDrained();
            sleep(PAST_CEILING_MS);
            assertThat(transport.endInputCount()).as("open with task in flight").isZero();

            transport.feed(taskNotification());
            transport.awaitDrained();
            assertThat(transport.endInputCount()).as("open right after task settled").isZero();
            assertThat(transport.awaitClosed()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = { "0", "2147483648", "huge" })
        void waitsForIdleWithNoOrAHugeCeiling(String ceiling) throws Exception {
            // 0 means no ceiling; a huge one is honored as a very long wait,
            // not one that fires at once or fails.
            String raw = "huge".equals(ceiling) ? "9".repeat(400) : ceiling;
            long ceilingMs = QueryHandler.runEndCeilingMs(
                    Map.of(QueryHandler.RUN_END_CEILING_ENV, raw), Map.of());
            ScriptedTransport transport = startOnePrompt(ceilingMs);

            transport.feed(sessionState("running"));
            transport.feed(result());
            transport.awaitDrained();
            sleep(PAST_CEILING_MS);
            assertThat(transport.endInputCount()).as("open after wait").isZero();

            transport.feed(sessionState("idle"));
            assertThat(transport.awaitClosed()).as("closed at idle").isTrue();
        }

        @Test
        void ceilingEndedStreamReopensForAMainThreadTurn() throws Exception {
            ControllablePrompt prompt = new ControllablePrompt();
            prompt.offer(userPrompt("Hello"));
            ScriptedTransport transport = start(prompt, CEILING_MS);
            transport.awaitWrites(1);

            transport.feed(sessionState("running"));
            transport.feed(result());
            // The ceiling passes while the caller's input is still open.
            transport.awaitDrained();
            sleep(PAST_CEILING_MS);
            // The background agent finishes after all; its turn starts
            // streaming with no state change, and the caller's input ends.
            transport.feed(assistant(null));
            transport.awaitDrained();
            prompt.finish();
            assertThat(transport.openAfterDrain()).as("open after input ended").isTrue();

            transport.feed(result());
            transport.feed(sessionState("idle"));
            assertThat(transport.awaitClosed()).as("closed at idle").isTrue();
        }

        @Test
        void noCeilingIsArmedOnceStdinIsClosed() throws Exception {
            // Frames still arrive while the CLI winds down after stdin closed;
            // a ceiling armed then would outlive the run it bounds.
            ScriptedTransport transport = startOnePrompt(CEILING_MS);

            transport.feed(sessionState("running"));
            transport.feed(result());
            transport.awaitDrained();
            assertThat(transport.handler.runEndCeilingArmed()).isTrue();

            transport.feed(sessionState("idle"));
            assertThat(transport.awaitClosed()).isTrue();
            assertThat(transport.handler.runEndCeilingArmed()).isFalse();

            // Work after stdin closed neither reopens the run nor arms a
            // ceiling for it.
            transport.feed(sessionState("running"));
            transport.feed(result());
            transport.awaitDrained();
            assertThat(transport.handler.runEndCeilingArmed()).isFalse();
            assertThat(transport.endInputCount()).isEqualTo(1);
        }
    }

    // --- CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS -------------------------------

    @Nested
    class RunEndCeilingFromEnv {

        private static final String ENV = QueryHandler.RUN_END_CEILING_ENV;

        private long parse(Map<String, String> optionsEnv, Map<String, String> ambient) {
            return QueryHandler.runEndCeilingMs(optionsEnv, ambient);
        }

        @Test
        void parse() {
            assertThat(parse(Map.of(), Map.of())).as("default").isEqualTo(600_000);
            assertThat(parse(Map.of(ENV, "0"), Map.of())).as("zero").isZero();
            assertThat(parse(Map.of(ENV, " 250 "), Map.of())).as("whitespace").isEqualTo(250);
            assertThat(parse(Map.of(), Map.of(ENV, "1234"))).as("ambient").isEqualTo(1234);
            assertThat(parse(Map.of(ENV, "250"), Map.of(ENV, "1234")))
                    .as("options over ambient").isEqualTo(250);
            assertThat(parse(Map.of(ENV, "soon"), Map.of())).as("not a number").isEqualTo(600_000);
            assertThat(parse(Map.of(ENV, "-1"), Map.of())).as("negative").isEqualTo(600_000);
            assertThat(parse(Map.of(ENV, ""), Map.of(ENV, "1234")))
                    .as("empty option wins").isEqualTo(600_000);
            assertThat(parse(Map.of(ENV, "9".repeat(400)), Map.of()))
                    .as("huge").isEqualTo(Long.MAX_VALUE);
        }
    }

    private static List<String> stateFrames(List<Message> messages) {
        List<String> states = new ArrayList<>();
        for (Message m : messages) {
            if (m instanceof SystemMessage sm && "session_state_changed".equals(sm.subtype())) {
                states.add(String.valueOf(sm.data().get("state")));
            }
        }
        return states;
    }

    /** A prompt iterator the test feeds, which blocks until told to finish. */
    static class ControllablePrompt implements Iterator<Map<String, Object>> {

        private static final Map<String, Object> END = Map.of();
        private final LinkedBlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
        private Map<String, Object> pending;

        void offer(Map<String, Object> message) {
            queue.add(message);
        }

        void finish() {
            queue.add(END);
        }

        @Override
        public boolean hasNext() {
            if (pending == null) {
                try {
                    pending = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return pending != END;
        }

        @Override
        public Map<String, Object> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Map<String, Object> next = pending;
            pending = null;
            return next;
        }
    }

    /**
     * Transport whose {@code readMessages()} iterator blocks until the test
     * feeds a frame. It counts prompt writes and {@code endInput()} calls, and
     * knows when the reader has finished processing every frame fed so far:
     * the reader handles a frame completely before it asks for the next one.
     */
    class ScriptedTransport implements Transport {

        private final LinkedBlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
        private final AtomicInteger fed = new AtomicInteger();
        private final AtomicInteger processed = new AtomicInteger();
        private final AtomicInteger delivered = new AtomicInteger();
        private final AtomicInteger writes = new AtomicInteger();
        private final AtomicInteger endInputs = new AtomicInteger();
        private final AtomicBoolean ready = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        QueryHandler handler;

        void feed(Map<String, Object> message) {
            fed.incrementAndGet();
            queue.add(message);
        }

        void awaitWrites(int count) {
            awaitCondition(() -> writes.get() >= count);
        }

        /** Waits until the reader has processed every frame fed so far. */
        void awaitDrained() {
            awaitCondition(() -> processed.get() == fed.get());
        }

        int endInputCount() {
            return endInputs.get();
        }

        /** Whether stdin is still open once the reader is drained and settled. */
        boolean openAfterDrain() throws InterruptedException {
            awaitDrained();
            Thread.sleep(SETTLE_MS);
            return endInputs.get() == 0;
        }

        boolean awaitClosed() {
            try {
                awaitCondition(() -> endInputs.get() > 0);
                return true;
            } catch (AssertionError e) {
                return false;
            }
        }

        /** Ends the CLI's output and returns what the consumer would see. */
        List<Message> finishAndCollect() {
            close();
            List<Message> messages = new ArrayList<>();
            Iterator<Message> it = handler.receiveMessages();
            while (it.hasNext()) {
                messages.add(it.next());
            }
            return messages;
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
                    // Asking for the next frame means the last one was
                    // handled in full.
                    processed.set(delivered.get());
                    while (pending == null && !closed.get()) {
                        try {
                            pending = queue.poll(20, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                    return pending != null;
                }

                @Override
                public Map<String, Object> next() {
                    Map<String, Object> next = pending;
                    pending = null;
                    delivered.incrementAndGet();
                    return next;
                }
            };
        }

        @Override
        public void write(String data) throws CLIConnectionException {
            if (closed.get()) {
                throw new CLIConnectionException("Transport closed");
            }
            writes.incrementAndGet();
        }

        @Override
        public void endInput() {
            endInputs.incrementAndGet();
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
