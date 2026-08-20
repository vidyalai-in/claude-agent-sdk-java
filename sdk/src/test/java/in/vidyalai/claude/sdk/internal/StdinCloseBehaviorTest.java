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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.permission.PermissionResultAllow;

/**
 * When {@link QueryHandler#streamInput} may close stdin (Python SDK #1204).
 *
 * <p>
 * A {@code canUseTool} callback is served over the control protocol exactly
 * like hooks and SDK MCP servers: the CLI writes a {@code control_request} to
 * stdout and blocks until the SDK writes the matching {@code control_response}
 * to stdin. Closing stdin at end of input therefore has to wait for a
 * run-ending result when a callback is set, or every permission prompt after
 * the close fails CLI-side with "Stream closed".
 *
 * <p>
 * The mirror image also has to hold: when nothing was written, no result can
 * arrive to release the hold, so stdin must close at once — including when the
 * caller's prompt iterator failed.
 */
class StdinCloseBehaviorTest {

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

    private static List<Map<String, Object>> onePrompt() {
        return List.of(Map.of(
                "type", "user",
                "session_id", "",
                "message", Map.of("role", "user", "content", "hi")));
    }

    private QueryHandler start(FeedableTransport transport, boolean withCanUseTool) {
        QueryHandler handler = new QueryHandler(
                transport,
                true,
                withCanUseTool
                        ? (toolName, input, context) ->
                                CompletableFuture.completedFuture(new PermissionResultAllow())
                        : null,
                null,
                Duration.ofSeconds(60));
        handlersToClose.add(handler);
        transport.connect();
        handler.start();
        return handler;
    }

    @Test
    void canUseToolAlone_holdsStdinOpenUntilResult() throws Exception {
        FeedableTransport transport = new FeedableTransport();
        transportsToClose.add(transport);
        QueryHandler handler = start(transport, true);

        Thread.startVirtualThread(() -> handler.streamInput(onePrompt().iterator()));

        // The prompt has been written but no result has arrived: a permission
        // request could still come back over the control protocol.
        assertThat(transport.awaitEndInput(SETTLE_MS)).isFalse();

        transport.feed(result());
        assertThat(transport.awaitEndInput(EXPECT_MS)).isTrue();
    }

    @Test
    void noBidirectionalNeeds_closesStdinWithoutWaitingForAResult() throws Exception {
        FeedableTransport transport = new FeedableTransport();
        transportsToClose.add(transport);
        QueryHandler handler = start(transport, false);

        Thread.startVirtualThread(() -> handler.streamInput(onePrompt().iterator()));

        // No callback, no hooks, no SDK MCP servers: nothing needs stdin after
        // the prompt, so it closes at end of input.
        assertThat(transport.awaitEndInput(EXPECT_MS)).isTrue();
    }

    @Test
    void emptyPromptIterator_closesStdinImmediately() throws Exception {
        FeedableTransport transport = new FeedableTransport();
        transportsToClose.add(transport);
        QueryHandler handler = start(transport, true);

        // Nothing was sent, so no result will arrive to release the hold.
        Thread.startVirtualThread(
                () -> handler.streamInput(List.<Map<String, Object>>of().iterator()));

        assertThat(transport.awaitEndInput(EXPECT_MS)).isTrue();
    }

    @Test
    void promptIteratorThatThrowsImmediately_closesStdin() throws Exception {
        FeedableTransport transport = new FeedableTransport();
        transportsToClose.add(transport);
        QueryHandler handler = start(transport, true);

        Thread.startVirtualThread(() -> handler.streamInput(new ThrowingIterator(0)));

        // The caller's iterator failed before sending anything. Leaving stdin
        // open would make the CLI wait for input forever and the consumer's
        // iteration never finish.
        assertThat(transport.awaitEndInput(EXPECT_MS)).isTrue();
    }

    @Test
    void promptIteratorThatThrowsAfterAMessage_stillClosesStdin() throws Exception {
        FeedableTransport transport = new FeedableTransport();
        transportsToClose.add(transport);
        QueryHandler handler = start(transport, true);

        Thread.startVirtualThread(() -> handler.streamInput(new ThrowingIterator(1)));

        // One message was written, so the bidirectional hold still applies:
        // the CLI may answer that turn with a permission request.
        assertThat(transport.awaitEndInput(SETTLE_MS)).isFalse();
        assertThat(transport.writeCount()).isEqualTo(1);

        // The run still ends, and its result releases the hold.
        transport.feed(result());
        assertThat(transport.awaitEndInput(EXPECT_MS)).isTrue();
    }

    /** Yields {@code okMessages} prompts, then throws from {@code next()}. */
    static class ThrowingIterator implements Iterator<Map<String, Object>> {

        private final int okMessages;
        private int served = 0;

        ThrowingIterator(int okMessages) {
            this.okMessages = okMessages;
        }

        @Override
        public boolean hasNext() {
            return true;
        }

        @Override
        public Map<String, Object> next() {
            if (served++ < okMessages) {
                return onePrompt().get(0);
            }
            throw new IllegalStateException("prompt source failed");
        }
    }

    /**
     * Transport whose {@code readMessages()} iterator blocks until the test
     * feeds a frame, and which records writes and the first {@code endInput()}.
     */
    static class FeedableTransport implements Transport {

        private final LinkedBlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
        private final CountDownLatch endInputCalled = new CountDownLatch(1);
        private final AtomicInteger writes = new AtomicInteger();
        private final AtomicBoolean ready = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        void feed(Map<String, Object> message) {
            queue.add(message);
        }

        boolean awaitEndInput(long millis) throws InterruptedException {
            return endInputCalled.await(millis, TimeUnit.MILLISECONDS);
        }

        int writeCount() {
            return writes.get();
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
                    if (pending == null) {
                        throw new NoSuchElementException();
                    }
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
            writes.incrementAndGet();
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
