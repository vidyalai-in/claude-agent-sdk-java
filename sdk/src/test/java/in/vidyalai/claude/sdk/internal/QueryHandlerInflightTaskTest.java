package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookMatcher;

/**
 * Regression tests for in-flight task tracking around stdin closure
 * (Python SDK #1103 / issue #1088).
 *
 * <p>
 * A {@code result} frame marks the end of one <b>turn</b>, not the end of the
 * <b>run</b>: a background task keeps running past it and still needs stdin for
 * hook and SDK-MCP control responses. Closing stdin on the first result frame
 * made a still-running subagent's SDK-MCP tool calls fail with "Stream closed"
 * and silently bypassed its PreToolUse hooks.
 *
 * <p>
 * These tests drive {@link QueryHandler#streamInput} with hooks registered (so
 * the bidirectional wait applies) and assert when {@code endInput()} lands.
 */
class QueryHandlerInflightTaskTest {

    /** Bound for waiting on something that should happen. */
    private static final long EXPECT_MS = 5000;

    /**
     * Bound for confirming something has not happened. Only lengthens the test
     * when it is already going to fail, so it can afford to be generous.
     */
    private static final long SETTLE_MS = 400;

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

    private static Map<String, Object> taskStarted(String taskId, String taskType) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "system");
        msg.put("subtype", "task_started");
        msg.put("session_id", "s");
        msg.put("task_id", taskId);
        msg.put("task_type", taskType);
        return msg;
    }

    private static Map<String, Object> taskNotification(String taskId) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "system");
        msg.put("subtype", "task_notification");
        msg.put("session_id", "s");
        msg.put("task_id", taskId);
        return msg;
    }

    private static Map<String, Object> taskUpdated(String taskId, String status) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "system");
        msg.put("subtype", "task_updated");
        msg.put("session_id", "s");
        msg.put("task_id", taskId);
        msg.put("patch", Map.of("status", status));
        return msg;
    }

    /**
     * Starts a handler with a hook registered (so {@code streamInput} performs
     * the bidirectional wait) and drives {@code streamInput} on its own thread.
     */
    private FeedableTransport startHandlerWithHooks() {
        FeedableTransport transport = new FeedableTransport();
        transportsToClose.add(transport);

        // The hook never fires here; its presence is what makes streamInput
        // wait for a run-ending result before closing stdin.
        Map<HookEvent, List<HookMatcher>> hooks = Map.of(
                HookEvent.PRE_TOOL_USE,
                List.of(new HookMatcher(null, List.of(
                        (input, context) -> CompletableFuture.completedFuture(null)))));

        QueryHandler handler = new QueryHandler(
                transport, true, null, hooks, Duration.ofSeconds(60));
        handlersToClose.add(handler);
        transport.connect();
        handler.start();

        // Empty input iterator: streamInput falls straight through to the
        // bidirectional wait, which is what these tests exercise.
        Thread.startVirtualThread(
                () -> handler.streamInput(Collections.<Map<String, Object>>emptyList().iterator()));
        return transport;
    }

    // --- tests ------------------------------------------------------------

    @Test
    void resultWithInflightTask_keepsStdinOpen_clearedByNotification() throws Exception {
        FeedableTransport transport = startHandlerWithHooks();

        transport.feed(taskStarted("t1", "local_agent"));
        transport.feed(result());

        // The turn ended but the task is still running: stdin must stay open.
        assertThat(transport.awaitEndInput(SETTLE_MS)).isFalse();

        transport.feed(taskNotification("t1"));
        transport.feed(result());

        assertThat(transport.awaitEndInput(EXPECT_MS)).isTrue();
    }

    @Test
    void resultWithInflightTask_keepsStdinOpen_clearedByTerminalUpdate() throws Exception {
        FeedableTransport transport = startHandlerWithHooks();

        transport.feed(taskStarted("t1", "local_agent"));
        transport.feed(result());
        assertThat(transport.awaitEndInput(SETTLE_MS)).isFalse();

        // Terminal completion can arrive as a task_updated patch instead of a
        // notification, so both must clear the task.
        transport.feed(taskUpdated("t1", "completed"));
        transport.feed(result());

        assertThat(transport.awaitEndInput(EXPECT_MS)).isTrue();
    }

    @Test
    void nonTerminalTaskUpdate_leavesTaskInFlight() throws Exception {
        FeedableTransport transport = startHandlerWithHooks();

        transport.feed(taskStarted("t1", "local_agent"));
        transport.feed(taskUpdated("t1", "running"));
        transport.feed(result());

        assertThat(transport.awaitEndInput(SETTLE_MS)).isFalse();
    }

    @Test
    void resultWithNoTasks_closesStdin() throws Exception {
        FeedableTransport transport = startHandlerWithHooks();

        transport.feed(result());

        assertThat(transport.awaitEndInput(EXPECT_MS)).isTrue();
    }

    @Test
    void backgroundShellTask_isNotTracked() throws Exception {
        FeedableTransport transport = startHandlerWithHooks();

        // A backgrounded shell (dev server, `tail -f`) is reported through the
        // same frames but can run indefinitely. The CLI in stream-json mode
        // exits only on stdin EOF, so tracking one would withhold the close
        // forever rather than briefly — the reader's finally never runs either.
        transport.feed(taskStarted("shell-1", "local_bash"));
        transport.feed(result());

        assertThat(transport.awaitEndInput(EXPECT_MS)).isTrue();
    }

    @Test
    void monitorAndTeammateTasks_areNotTracked() throws Exception {
        FeedableTransport transport = startHandlerWithHooks();

        // Monitors run indefinitely by design and teammates stay "running" for
        // their whole lifetime, so none of them ever settles the ledger.
        transport.feed(taskStarted("monitor_mcp-1", "monitor_mcp"));
        transport.feed(taskStarted("monitor_ws-1", "monitor_ws"));
        transport.feed(taskStarted("teammate-1", "in_process_teammate"));
        transport.feed(result());

        assertThat(transport.awaitEndInput(EXPECT_MS)).isTrue();
    }

    @Test
    void backgroundTasksChangedSnapshot_doesNotAddToTheLedger() throws Exception {
        FeedableTransport transport = startHandlerWithHooks();

        // The snapshot spans every background task type and carries nothing
        // marking an observer agent, whose start and terminal frames are both
        // suppressed — so it could admit an id no later frame ever clears.
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("type", "system");
        snapshot.put("subtype", "background_tasks_changed");
        snapshot.put("tasks", List.of(
                Map.of("task_id", "task-1", "task_type", "local_agent"),
                Map.of("task_id", "task-2", "task_type", "local_agent")));
        transport.feed(snapshot);
        transport.feed(result());

        assertThat(transport.awaitEndInput(EXPECT_MS)).isTrue();
    }

    @Test
    void backgroundTasksChangedSnapshot_doesNotClearTheLedger() throws Exception {
        FeedableTransport transport = startHandlerWithHooks();

        // A subagent is registered in the foreground and only flips to
        // backgrounded later, without a second task_started — so a tracked
        // agent that is still running can be absent from the snapshot
        // entirely. Narrowing against it would drop exactly the agent this
        // tracking exists to protect.
        transport.feed(taskStarted("task-1", "local_agent"));

        Map<String, Object> otherTask = new HashMap<>();
        otherTask.put("type", "system");
        otherTask.put("subtype", "background_tasks_changed");
        otherTask.put("tasks", List.of(Map.of("task_id", "shell-1", "task_type", "local_bash")));
        transport.feed(otherTask);

        Map<String, Object> emptySnapshot = new HashMap<>();
        emptySnapshot.put("type", "system");
        emptySnapshot.put("subtype", "background_tasks_changed");
        emptySnapshot.put("tasks", List.of());
        transport.feed(emptySnapshot);

        transport.feed(result());
        // Not even an empty snapshot clears it; only a terminal frame does.
        assertThat(transport.awaitEndInput(SETTLE_MS)).isFalse();

        transport.feed(taskNotification("task-1"));
        transport.feed(result());
        assertThat(transport.awaitEndInput(EXPECT_MS)).isTrue();
    }

    @Test
    void terminalUpdateForUnknownTaskId_isHarmless() throws Exception {
        FeedableTransport transport = startHandlerWithHooks();

        transport.feed(taskStarted("t1", "local_agent"));
        transport.feed(taskNotification("never-started"));
        transport.feed(result());

        // Clearing an id that was never tracked must not clear t1.
        assertThat(transport.awaitEndInput(SETTLE_MS)).isFalse();
    }

    @Test
    void lifecycleFrameWithoutTaskId_isIgnored() throws Exception {
        FeedableTransport transport = startHandlerWithHooks();

        Map<String, Object> started = taskStarted("t1", "local_agent");
        started.remove("task_id");
        transport.feed(started);
        transport.feed(result());

        assertThat(transport.awaitEndInput(EXPECT_MS)).isTrue();
    }

    @Test
    void chainedBackgroundTasks_closeOnlyAfterTheLastSettles() throws Exception {
        FeedableTransport transport = startHandlerWithHooks();

        transport.feed(taskStarted("t1", "local_agent"));
        transport.feed(result());
        assertThat(transport.awaitEndInput(SETTLE_MS)).isFalse();

        // t1's completion spawns t2 before the follow-up turn's result.
        transport.feed(taskNotification("t1"));
        transport.feed(taskStarted("t2", "local_workflow"));
        transport.feed(result());
        assertThat(transport.awaitEndInput(SETTLE_MS)).isFalse();

        transport.feed(taskNotification("t2"));
        transport.feed(result());
        assertThat(transport.awaitEndInput(EXPECT_MS)).isTrue();
    }

    /**
     * Transport whose {@code readMessages()} iterator blocks until the test
     * feeds a frame, and which records the first {@code endInput()} call.
     */
    static class FeedableTransport implements Transport {

        private final LinkedBlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
        private final CountDownLatch endInputCalled = new CountDownLatch(1);
        private final AtomicBoolean ready = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        void feed(Map<String, Object> message) {
            queue.add(message);
        }

        boolean awaitEndInput(long millis) throws InterruptedException {
            return endInputCalled.await(millis, TimeUnit.MILLISECONDS);
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
                            pending = queue.poll(100, TimeUnit.MILLISECONDS);
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
                    return next;
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
            endInputCalled.countDown();
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
