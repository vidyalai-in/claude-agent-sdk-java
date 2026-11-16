package in.vidyalai.claude.sdk.internal;

import static in.vidyalai.claude.sdk.internal.PipeTransport.awaitUntil;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookMatcher;
import in.vidyalai.claude.sdk.types.hook.output.HookOutput;
import in.vidyalai.claude.sdk.types.permission.PermissionResult;
import in.vidyalai.claude.sdk.types.permission.PermissionResultAllow;

/**
 * {@link QueryHandler#close()} called from inside a callback, or by a thread
 * whose interrupt flag is set, still closes gracefully: the transport is
 * closed by a thread that is not interrupted (so stdin gets its EOF), the
 * closing thread is neither interrupted nor waited for, and the flag is
 * restored.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("null")
class CloseFromCallbackTest {

    private static final long WAIT_SECONDS = 5;

    private final List<QueryHandler> handlers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (QueryHandler h : handlers) {
            h.close();
        }
        Thread.interrupted();
    }

    /** Delegates to a {@link PipeTransport}, recording how close() was called. */
    static final class RecordingTransport implements Transport {
        final PipeTransport pipe = new PipeTransport();
        final AtomicReference<@Nullable Boolean> closedWhileInterrupted = new AtomicReference<>();
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void connect() {
            pipe.connect();
        }

        @Override
        public void write(String data) {
            pipe.write(data);
        }

        @Override
        public Iterator<Map<String, Object>> readMessages() {
            return pipe.readMessages();
        }

        @Override
        public void endInput() {
            pipe.endInput();
        }

        @Override
        public boolean isReady() {
            return pipe.isReady();
        }

        @Override
        public void close() {
            closedWhileInterrupted.compareAndSet(null, Thread.currentThread().isInterrupted());
            pipe.close();
            closed.countDown();
        }
    }

    private QueryHandler start(Transport transport, ClaudeAgentOptions.@Nullable CanUseTool canUseTool,
            HookMatcher.@Nullable HookCallback hook) {
        Map<HookEvent, List<HookMatcher>> hooks = ((hook != null)
                ? Map.of(HookEvent.PRE_TOOL_USE, List.<HookMatcher>of(new HookMatcher(null, List.of(hook))))
                : null);
        QueryHandler handler = new QueryHandler(transport, true, canUseTool, hooks, null,
                null, null, null, Duration.ofSeconds(10), null);
        handlers.add(handler);
        transport.connect();
        handler.start();
        handler.initialize();
        return handler;
    }

    private static Map<String, Object> hookRequest(String requestId) {
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

    private static Map<String, Object> canUseToolRequest(String requestId) {
        return Map.of("type", "control_request", "request_id", requestId, "request",
                Map.of("subtype", "can_use_tool", "tool_name", "Bash", "input", Map.of("command", "ls")));
    }

    @Test
    void closeFromAHookClosesTheTransportUninterruptedAndQuickly() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        AtomicReference<QueryHandler> ref = new AtomicReference<>();
        AtomicLong closeMillis = new AtomicLong(-1);
        AtomicBoolean interruptedAfter = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);
        HookMatcher.HookCallback hook = (input, context) -> {
            long t0 = System.nanoTime();
            ref.get().close();
            closeMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
            interruptedAfter.set(Thread.currentThread().isInterrupted());
            done.countDown();
            return CompletableFuture.completedFuture(HookOutput.empty());
        };
        QueryHandler handler = start(transport, null, hook);
        ref.set(handler);

        transport.pipe.push(hookRequest("cli_hook"));
        assertThat(done.await(WAIT_SECONDS * 3, TimeUnit.SECONDS)).isTrue();

        // close() used to cancel the hook's own request, interrupting the
        // closing thread: the transport then closed interrupted (no stdin
        // EOF for a real CLI, which was killed instead).
        assertThat(transport.closedWhileInterrupted.get()).isFalse();
        assertThat(interruptedAfter.get()).isFalse();
        // Not waiting for its own handler (that took the control join timeout).
        assertThat(closeMillis.get()).isLessThan(3_000);
        // The request was cancelled by close(): it is never answered.
        assertThat(transport.pipe.responsesTo("cli_hook")).isEmpty();
    }

    @Test
    void closeFromCanUseToolStillInterruptsOtherCallbacks() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        AtomicReference<QueryHandler> ref = new AtomicReference<>();
        CountDownLatch otherWaiting = new CountDownLatch(1);
        AtomicBoolean otherInterrupted = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        ClaudeAgentOptions.CanUseTool canUseTool = (toolName, input, context) -> {
            if ("ls".equals(input.get("command"))) {
                // A second request, blocked until close() interrupts it.
                otherWaiting.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    otherInterrupted.set(true);
                    Thread.currentThread().interrupt();
                }
                return CompletableFuture.completedFuture(new PermissionResultAllow());
            }
            ref.get().close();
            done.countDown();
            return CompletableFuture.<PermissionResult>completedFuture(new PermissionResultAllow());
        };
        QueryHandler handler = start(transport, canUseTool, null);
        ref.set(handler);

        transport.pipe.push(canUseToolRequest("cli_other"));
        assertThat(otherWaiting.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        transport.pipe.push(Map.of("type", "control_request", "request_id", "cli_closer", "request",
                Map.of("subtype", "can_use_tool", "tool_name", "Bash", "input", Map.of("command", "rm"))));
        assertThat(done.await(WAIT_SECONDS * 3, TimeUnit.SECONDS)).isTrue();

        assertThat(otherInterrupted.get()).isTrue();
        assertThat(transport.closedWhileInterrupted.get()).isFalse();
        assertThat(transport.pipe.responsesTo("cli_other")).isEmpty();
        assertThat(transport.pipe.responsesTo("cli_closer")).isEmpty();
    }

    @Test
    void closeWithTheInterruptFlagSetClosesGracefullyAndRestoresIt() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        QueryHandler handler = start(transport, null, null);

        Thread.currentThread().interrupt();
        handler.close();

        assertThat(Thread.interrupted()).as("interrupt flag restored").isTrue();
        assertThat(transport.closedWhileInterrupted.get()).isFalse();
        // The reader was joined: its read loop is over.
        awaitUntil("the read loop to end", WAIT_SECONDS, transport.pipe::readEnded);
    }

    @Test
    void anInterruptDuringCloseIsRestoredWithoutCuttingCloseShort() throws Exception {
        CountDownLatch inClose = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean sawInterruptInClose = new AtomicBoolean();
        RecordingTransport transport = new RecordingTransport();
        Transport slowClose = new Transport() {
            @Override
            public void connect() {
                transport.connect();
            }

            @Override
            public void write(String data) {
                transport.write(data);
            }

            @Override
            public Iterator<Map<String, Object>> readMessages() {
                return transport.readMessages();
            }

            @Override
            public void endInput() {
            }

            @Override
            public boolean isReady() {
                return transport.isReady();
            }

            @Override
            public void close() {
                inClose.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    // Like a real transport: restore and finish closing.
                    sawInterruptInClose.set(true);
                    Thread.currentThread().interrupt();
                }
                transport.close();
            }
        };
        QueryHandler handler = start(slowClose, null, null);

        AtomicBoolean flagAfterClose = new AtomicBoolean();
        Thread closer = new Thread(() -> {
            handler.close();
            flagAfterClose.set(Thread.currentThread().isInterrupted());
        });
        closer.start();
        assertThat(inClose.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        closer.interrupt();
        closer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS * 2));

        assertThat(closer.isAlive()).isFalse();
        assertThat(sawInterruptInClose.get()).isTrue();
        assertThat(flagAfterClose.get()).as("interrupt flag restored").isTrue();
        // The rest of close() still ran: the reader was joined.
        assertThat(transport.pipe.readEnded()).isTrue();
    }

}
