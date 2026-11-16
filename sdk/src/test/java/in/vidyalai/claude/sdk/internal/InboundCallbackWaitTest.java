package in.vidyalai.claude.sdk.internal;

import static in.vidyalai.claude.sdk.internal.PipeTransport.awaitUntil;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookMatcher;
import in.vidyalai.claude.sdk.types.hook.output.HookOutput;
import in.vidyalai.claude.sdk.types.permission.PermissionResult;
import in.vidyalai.claude.sdk.types.permission.PermissionResultAllow;

/**
 * Inbound callbacks (canUseTool, hooks) have no SDK-side deadline, as in the
 * Python SDK: they wait for the callback, a {@code control_cancel_request} or
 * close(), and close() cancels them rather than waiting for them.
 *
 * <p>
 * The no-deadline tests read the handler thread's state rather than waiting
 * out the old 60s cap: a wait with a deadline parks the thread
 * {@code TIMED_WAITING}, a wait without one {@code WAITING}.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class InboundCallbackWaitTest {

    private static final long WAIT_SECONDS = 5;

    private final List<QueryHandler> handlers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (QueryHandler h : handlers) {
            h.close();
        }
    }

    private QueryHandler start(PipeTransport transport, ClaudeAgentOptions.@Nullable CanUseTool canUseTool,
            HookMatcher.@Nullable HookCallback hook) {
        Map<HookEvent, List<HookMatcher>> hooks = ((hook != null)
                ? Map.of(HookEvent.PRE_TOOL_USE, List.<HookMatcher>of(new HookMatcher(null, List.of(hook))))
                : null);
        QueryHandler handler = new QueryHandler(transport, true, canUseTool, hooks, null,
                null, null, null, Duration.ofSeconds(10), null);
        handlers.add(handler);
        transport.connect();
        handler.start();
        if (hook != null) {
            handler.initialize(); // registers the callback as hook_0
        }
        return handler;
    }

    static Map<String, Object> canUseToolRequest(String requestId, Map<String, Object> input) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("subtype", "can_use_tool");
        request.put("tool_name", "Bash");
        request.put("input", input);
        request.put("tool_use_id", "toolu_1");
        return Map.of("type", "control_request", "request_id", requestId, "request", request);
    }

    static Map<String, Object> hookRequest(String requestId, Map<String, Object> input) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("subtype", "hook_callback");
        request.put("callback_id", "hook_0");
        request.put("input", input);
        request.put("tool_use_id", "toolu_1");
        return Map.of("type", "control_request", "request_id", requestId, "request", request);
    }

    static Map<String, Object> preToolUseInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("hook_event_name", "PreToolUse");
        input.put("session_id", "s");
        input.put("transcript_path", "/tmp/t.jsonl");
        input.put("cwd", ".");
        input.put("tool_name", "Bash");
        input.put("tool_input", Map.of("command", "ls"));
        input.put("tool_use_id", "toolu_1");
        return input;
    }

    private static void awaitWaitingWithNoDeadline(AtomicReference<Thread> handlerThread) throws Exception {
        awaitUntil("the handler thread", WAIT_SECONDS, () -> handlerThread.get() != null);
        Thread thread = handlerThread.get();
        awaitUntil("the handler to wait with no deadline (state " + thread.getState() + ")", WAIT_SECONDS,
                () -> thread.getState() == Thread.State.WAITING);
    }

    @Test
    void canUseToolIsAwaitedWithNoDeadline() throws Exception {
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        CompletableFuture<PermissionResult> decision = new CompletableFuture<>();
        PipeTransport transport = new PipeTransport();
        start(transport, (tool, input, context) -> {
            handlerThread.set(Thread.currentThread());
            return decision;
        }, null);

        transport.push(canUseToolRequest("perm_1", Map.of("command", "ls")));
        awaitWaitingWithNoDeadline(handlerThread);
        assertThat(transport.responsesTo("perm_1")).isEmpty();

        decision.complete(new PermissionResultAllow());
        awaitUntil("the answer", WAIT_SECONDS, () -> !transport.responsesTo("perm_1").isEmpty());
        Map<String, Object> response = transport.responsesTo("perm_1").get(0);
        assertThat(response).containsEntry("subtype", "success");
        assertThat(response.get("response")).isEqualTo(Map.of(
                "behavior", "allow", "updatedInput", Map.of("command", "ls")));
    }

    @Test
    void hookCallbackIsAwaitedWithNoDeadline() throws Exception {
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        CompletableFuture<HookOutput> output = new CompletableFuture<>();
        PipeTransport transport = new PipeTransport();
        start(transport, null, (input, context) -> {
            handlerThread.set(Thread.currentThread());
            return output;
        });

        transport.push(hookRequest("hook_req_1", preToolUseInput()));
        awaitWaitingWithNoDeadline(handlerThread);
        assertThat(transport.responsesTo("hook_req_1")).isEmpty();

        output.complete(HookOutput.builder().systemMessage("late but fine").build());
        awaitUntil("the answer", WAIT_SECONDS, () -> !transport.responsesTo("hook_req_1").isEmpty());
        Map<String, Object> response = transport.responsesTo("hook_req_1").get(0);
        assertThat(response).containsEntry("subtype", "success");
        assertThat(response.get("response")).isEqualTo(Map.of("systemMessage", "late but fine"));
    }

    @Test
    void closeCancelsACanUseToolInFlightAtOnce() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<PermissionResult> decision = new CompletableFuture<>();
        PipeTransport transport = new PipeTransport();
        QueryHandler handler = start(transport, (tool, input, context) -> {
            started.countDown();
            return decision;
        }, null);

        transport.push(canUseToolRequest("perm_1", Map.of()));
        assertThat(started.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        long t0 = System.nanoTime();
        handler.close();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        // Cancelled, not waited for: the control executor's 5s grace period
        // used to run out first, and the callback's future was left pending.
        assertThat(decision.isCancelled()).isTrue();
        assertThat(elapsedMs).isLessThan(3_000);
        assertThat(transport.responsesTo("perm_1")).isEmpty();
        assertThat(handler.inflightControlRequestCount()).isZero();
    }

    @Test
    void closeCancelsAHookInFlightAtOnce() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<HookOutput> output = new CompletableFuture<>();
        PipeTransport transport = new PipeTransport();
        QueryHandler handler = start(transport, null, (input, context) -> {
            started.countDown();
            return output;
        });

        transport.push(hookRequest("hook_req_1", preToolUseInput()));
        assertThat(started.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        long t0 = System.nanoTime();
        handler.close();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertThat(output.isCancelled()).isTrue();
        assertThat(elapsedMs).isLessThan(3_000);
        assertThat(transport.responsesTo("hook_req_1")).isEmpty();
    }

}
