package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.internal.HookOnFailureTest.ScriptedTransport;
import in.vidyalai.claude.sdk.mcp.McpMessageHandler;
import in.vidyalai.claude.sdk.mcp.SdkMcpServer;
import in.vidyalai.claude.sdk.mcp.SdkMcpTool;
import in.vidyalai.claude.sdk.mcp.ToolCallContext;
import in.vidyalai.claude.sdk.mcp.ToolResult;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookMatcher;
import in.vidyalai.claude.sdk.types.hook.output.HookOutput;
import in.vidyalai.claude.sdk.types.permission.PermissionResult;

/**
 * {@code control_cancel_request} stops the handler of the control request it
 * names, and that request is never answered (Python SDK #751).
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ControlCancelRequestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long WAIT_SECONDS = 5;

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

    private QueryHandler start(ScriptedTransport transport, ClaudeAgentOptions.@Nullable CanUseTool canUseTool,
            HookMatcher.@Nullable HookCallback hook, @Nullable Map<String, McpMessageHandler> servers)
            throws Exception {
        Map<HookEvent, List<HookMatcher>> hooks = ((hook != null)
                ? Map.of(HookEvent.PRE_TOOL_USE, List.<HookMatcher>of(new HookMatcher(null, List.of(hook))))
                : null);
        QueryHandler handler = new QueryHandler(transport, true, canUseTool, hooks, servers,
                null, null, null, Duration.ofSeconds(10), null);
        handlers.add(handler);
        transport.connect();
        handler.start();
        if (hook != null) {
            // Registers the callback as hook_0.
            handler.initialize();
        }
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

    private static Map<String, Object> cancel(String requestId) {
        return Map.of("type", "control_cancel_request", "request_id", requestId);
    }

    /** The control responses written for {@code requestId}. */
    @SuppressWarnings({ "unchecked", "null" })
    private static List<Map<String, Object>> responsesTo(ScriptedTransport transport, String requestId)
            throws Exception {
        List<Map<String, Object>> responses = new ArrayList<>();
        for (String written : transport.writes()) {
            Map<String, Object> frame = MAPPER.readValue(written, Map.class);
            if ("control_response".equals(frame.get("type"))) {
                Map<String, Object> response = (Map<String, Object>) frame.get("response");
                if (requestId.equals(response.get("request_id"))) {
                    responses.add(response);
                }
            }
        }
        return responses;
    }

    @FunctionalInterface
    private interface Condition {
        boolean holds() throws Exception;
    }

    /** Polls until {@code condition} holds, failing after {@link #WAIT_SECONDS}. */
    private static void awaitUntil(String what, Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.holds()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    private static void awaitResponse(ScriptedTransport transport, String requestId) throws Exception {
        awaitUntil("a response to " + requestId, () -> !responsesTo(transport, requestId).isEmpty());
    }

    private static void awaitNoInflight(QueryHandler handler) throws Exception {
        awaitUntil("no handlers in flight", () -> handler.inflightControlRequestCount() == 0);
    }

    @Test
    void cancelStopsAHookWaitingOnItsFutureAndSendsNoResponse() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<HookOutput> pending = new CompletableFuture<>();
        ScriptedTransport transport = new ScriptedTransport(Map.of());
        QueryHandler handler = start(transport, null, (input, context) -> {
            started.countDown();
            return pending;
        }, null);

        transport.push(hookRequest("hook_1"));
        assertThat(started.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        transport.push(cancel("hook_1"));

        // The callback's own future is cancelled, so work composed on it can stop.
        awaitUntil("the hook's future to be cancelled", pending::isCancelled);
        awaitNoInflight(handler);
        assertThat(responsesTo(transport, "hook_1")).isEmpty();
    }

    @Test
    void cancelInterruptsAHookThatBlocksItsThread() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        ScriptedTransport transport = new ScriptedTransport(Map.of());
        QueryHandler handler = start(transport, null, (input, context) -> {
            started.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        }, null);

        transport.push(hookRequest("hook_1"));
        assertThat(started.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        transport.push(cancel("hook_1"));

        // The reader untracks the request before it cancels it, so wait on the
        // effect itself rather than on the in-flight count.
        awaitUntil("the hook to be interrupted", interrupted::get);
        awaitNoInflight(handler);
        // Even though the hook went on to return an answer.
        assertThat(responsesTo(transport, "hook_1")).isEmpty();
    }

    @Test
    void cancelStopsAPermissionCallback() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<PermissionResult> pending = new CompletableFuture<>();
        ScriptedTransport transport = new ScriptedTransport(Map.of());
        QueryHandler handler = start(transport, (tool, input, context) -> {
            started.countDown();
            return pending;
        }, null, null);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("subtype", "can_use_tool");
        request.put("tool_name", "Bash");
        request.put("input", Map.of("command", "ls"));
        transport.push(Map.of("type", "control_request", "request_id", "perm_1", "request", request));
        assertThat(started.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        transport.push(cancel("perm_1"));

        awaitUntil("the permission future to be cancelled", pending::isCancelled);
        awaitNoInflight(handler);
        assertThat(responsesTo(transport, "perm_1")).isEmpty();
    }

    @Test
    void cancelReachesTheSdkMcpToolBeingCalled() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<ToolCallContext> seen = new AtomicReference<>();
        CountDownLatch toolCancelled = new CountDownLatch(1);
        SdkMcpTool<Map<String, Object>> tool = SdkMcpTool.create("slow", "Never finishes", Map.of(),
                (args, ctx) -> {
                    seen.set(ctx);
                    ctx.onCancel(toolCancelled::countDown);
                    started.countDown();
                    return new CompletableFuture<ToolResult>();
                });
        ScriptedTransport transport = new ScriptedTransport(Map.of());
        QueryHandler handler = start(transport, null, null,
                Map.of("srv", SdkMcpServer.create("srv", List.of(tool))));

        Map<String, Object> call = new LinkedHashMap<>();
        call.put("jsonrpc", "2.0");
        call.put("id", 1);
        call.put("method", "tools/call");
        call.put("params", Map.of("name", "slow", "arguments", Map.of()));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("subtype", "mcp_message");
        request.put("server_name", "srv");
        request.put("message", call);
        transport.push(Map.of("type", "control_request", "request_id", "mcp_1", "request", request));
        assertThat(started.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        transport.push(cancel("mcp_1"));

        assertThat(toolCancelled.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get().isCancelled()).isTrue();
        awaitNoInflight(handler);
        assertThat(responsesTo(transport, "mcp_1")).isEmpty();
    }

    @Test
    void cancelForAnUnknownIdIsANoOp() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(Map.of());
        start(transport, null,
                (input, context) -> CompletableFuture.completedFuture(HookOutput.empty()), null);

        transport.push(cancel("nonexistent"));
        transport.push(Map.of("type", "control_cancel_request"));
        transport.push(hookRequest("hook_1"));

        awaitResponse(transport, "hook_1");
        assertThat(responsesTo(transport, "hook_1")).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("subtype", "success"));
    }

    @Test
    void aCompletedRequestIsUntrackedSoALateCancelChangesNothing() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(Map.of());
        QueryHandler handler = start(transport, null,
                (input, context) -> CompletableFuture.completedFuture(HookOutput.empty()), null);

        transport.push(hookRequest("fast_1"));
        awaitResponse(transport, "fast_1");
        awaitNoInflight(handler);

        transport.push(cancel("fast_1"));
        // A request after the cancel is still served, so the reader got past it.
        transport.push(hookRequest("fast_2"));
        awaitResponse(transport, "fast_2");

        assertThat(responsesTo(transport, "fast_1")).hasSize(1);
    }

    @Test
    void cancellingOneRequestLeavesTheOthersRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        Map<String, CompletableFuture<HookOutput>> pending = new ConcurrentHashMap<>();
        ScriptedTransport transport = new ScriptedTransport(Map.of());
        QueryHandler handler = start(transport, null, (input, context) -> {
            CompletableFuture<HookOutput> future = new CompletableFuture<>();
            pending.put(String.valueOf(context.toolUseId()), future);
            started.countDown();
            return future;
        }, null);

        Map<String, Object> first = new LinkedHashMap<>(hookRequest("hook_1"));
        Map<String, Object> second = new LinkedHashMap<>(hookRequest("hook_2"));
        @SuppressWarnings("unchecked")
        Map<String, Object> secondRequest = new LinkedHashMap<>((Map<String, Object>) second.get("request"));
        secondRequest.put("tool_use_id", "toolu_2");
        second.put("request", secondRequest);
        transport.push(first);
        transport.push(second);
        assertThat(started.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        transport.push(cancel("hook_1"));
        CompletableFuture<HookOutput> cancelled = Objects.requireNonNull(pending.get("toolu_1"));
        awaitUntil("the first hook's future to be cancelled", cancelled::isCancelled);
        CompletableFuture<HookOutput> other = Objects.requireNonNull(pending.get("toolu_2"));
        assertThat(other).isNotDone();
        other.complete(HookOutput.empty());

        awaitResponse(transport, "hook_2");
        awaitNoInflight(handler);
        assertThat(responsesTo(transport, "hook_1")).isEmpty();
    }
}
