package in.vidyalai.claude.sdk.internal;

import static java.util.Objects.requireNonNull;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.mcp.McpMessageHandler;
import in.vidyalai.claude.sdk.mcp.SdkMcpServer;
import in.vidyalai.claude.sdk.mcp.SdkMcpTool;
import in.vidyalai.claude.sdk.mcp.ToolCallContext;
import in.vidyalai.claude.sdk.mcp.ToolResult;
import in.vidyalai.claude.sdk.transport.Transport;

/**
 * An {@code mcp_message} control request, driven end to end through
 * {@link QueryHandler}.
 *
 * <p>
 * Nothing exercised this path before, which is how four separate defects
 * survived in it: a hard 60-second cap that turned a slow tool into a failed
 * <i>control request</i> while leaving the tool running; an unknown server or
 * a throwing handler failing the control request rather than the one MCP call;
 * a notification getting a JSON-RPC reply it must never get; and
 * {@code notifications/cancelled} being answered {@code -32601} instead of
 * cancelling anything.
 *
 * <p>
 * {@link #aCancellationSettlesAHungToolCall()} is the one that would have
 * caught all four at once.
 */
class SdkMcpControlProtocolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long EXPECT_MS = 5000;

    private final List<QueryHandler> handlersToClose = new ArrayList<>();
    private final List<Transport> transportsToClose = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (QueryHandler handler : handlersToClose) {
            try {
                handler.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
        handlersToClose.clear();
        for (Transport transport : transportsToClose) {
            try {
                transport.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
        transportsToClose.clear();
    }

    // --- harness ----------------------------------------------------------

    private RecordingTransport start(Map<String, McpMessageHandler> servers) {
        RecordingTransport transport = new RecordingTransport();
        transportsToClose.add(transport);

        QueryHandler handler = new QueryHandler(
                transport, true, null, null, servers, null, null, null, false,
                Duration.ofSeconds(60), null);
        handlersToClose.add(handler);
        transport.connect();
        handler.start();
        return transport;
    }

    private QueryHandler lastHandler() {
        return handlersToClose.get(handlersToClose.size() - 1);
    }

    private static Map<String, Object> mcpControlRequest(
            String requestId, @Nullable String serverName, @Nullable Map<String, Object> message) {
        Map<String, Object> request = new HashMap<>();
        request.put("subtype", "mcp_message");
        request.put("server_name", serverName);
        request.put("message", message);

        Map<String, Object> frame = new HashMap<>();
        frame.put("type", "control_request");
        frame.put("request_id", requestId);
        frame.put("request", request);
        return frame;
    }

    private static Map<String, Object> jsonRpc(
            @Nullable Object id, String method, @Nullable Object params) {
        Map<String, Object> message = new HashMap<>();
        message.put("jsonrpc", "2.0");
        if (id != null) {
            message.put("id", id);
        }
        message.put("method", method);
        if (params != null) {
            message.put("params", params);
        }
        return message;
    }

    private static Map<String, Object> callTool(Object id, String tool) {
        return jsonRpc(id, "tools/call", Map.of("name", tool, "arguments", Map.of()));
    }

    /** The {@code mcp_response} of the control response for {@code requestId}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mcpResponseFor(RecordingTransport transport, String requestId)
            throws Exception {
        Map<String, Object> response = transport.awaitResponse(requestId);
        assertThat(response)
                .as("the control request itself must succeed; an MCP failure belongs in mcp_response")
                .containsEntry("subtype", "success");
        Map<String, Object> payload = (Map<String, Object>) response.get("response");
        assertThat(payload).as("control response payload").isNotNull();
        return (Map<String, Object>) payload.get("mcp_response");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorOf(Map<String, Object> mcpResponse) {
        assertThat(mcpResponse).as("expected a JSON-RPC error, got: %s", mcpResponse).containsKey("error");
        return (Map<String, Object>) mcpResponse.get("error");
    }

    private static SdkMcpServer serverWith(SdkMcpTool<?>... tools) {
        return SdkMcpServer.create("calc", "1.0.0", List.of(tools));
    }

    // --- tests ------------------------------------------------------------

    @Test
    void anMcpMessageIsAnsweredWithTheServersResponse() throws Exception {
        SdkMcpTool<Map<String, Object>> add = SdkMcpTool.create(
                "add", "Adds", Map.of(),
                args -> CompletableFuture.completedFuture(ToolResult.text("3")));
        RecordingTransport transport = start(Map.of("calc", serverWith(add)));

        transport.feed(mcpControlRequest("req_1", "calc", jsonRpc(1, "tools/list", null)));

        Map<String, Object> mcpResponse = mcpResponseFor(transport, "req_1");
        assertThat(mcpResponse).containsEntry("id", 1);
        assertThat(String.valueOf(mcpResponse.get("result"))).contains("add");
    }

    @Test
    void aNotificationIsAcknowledgedWithoutAJsonRpcReply() throws Exception {
        // The notification gets no JSON-RPC response, but the control request
        // that carried it still needs an answer or the CLI waits forever.
        RecordingTransport transport = start(Map.of("calc", serverWith()));

        transport.feed(mcpControlRequest(
                "req_1", "calc", jsonRpc(null, "notifications/initialized", null)));

        assertThat(mcpResponseFor(transport, "req_1"))
                .isEqualTo(Map.of("jsonrpc", "2.0", "result", Map.of()));
    }

    @Test
    void anUnknownServerFailsOnlyThatMcpCall() throws Exception {
        // Used to throw, which failed the whole control request.
        RecordingTransport transport = start(Map.of("calc", serverWith()));

        transport.feed(mcpControlRequest("req_1", "nope", jsonRpc(1, "tools/list", null)));

        assertThat(errorOf(mcpResponseFor(transport, "req_1")))
                .containsEntry("code", -32601)
                .containsEntry("message", "Server 'nope' not found");
    }

    @Test
    void aHandlerThatFailsFailsOnlyThatMcpCall() throws Exception {
        McpMessageHandler broken = message -> CompletableFuture.failedFuture(
                new IllegalStateException("the handler is broken"));
        RecordingTransport transport = start(Map.of("calc", broken));

        transport.feed(mcpControlRequest("req_1", "calc", jsonRpc(1, "tools/list", null)));

        assertThat(errorOf(mcpResponseFor(transport, "req_1")))
                .containsEntry("code", -32603)
                .containsEntry("message", "the handler is broken");
    }

    @Test
    void aMissingMessageIsAControlError() throws Exception {
        // Python raises for this, so the CLI gets a control-level error
        // response, not a JSON-RPC error dressed up as success.
        RecordingTransport transport = start(Map.of("calc", serverWith()));

        transport.feed(mcpControlRequest("req_1", "calc", null));

        assertControlError(transport.awaitResponse("req_1"));
    }

    @Test
    void anEmptyMessageIsAControlError() throws Exception {
        RecordingTransport transport = start(Map.of("calc", serverWith()));

        transport.feed(mcpControlRequest("req_1", "calc", Map.of()));

        assertControlError(transport.awaitResponse("req_1"));
    }

    @Test
    void aMissingServerNameIsAControlError() throws Exception {
        RecordingTransport transport = start(Map.of("calc", serverWith()));

        transport.feed(mcpControlRequest("req_1", null, jsonRpc(1, "tools/list", null)));
        transport.feed(mcpControlRequest("req_2", "", jsonRpc(2, "tools/list", null)));

        assertControlError(transport.awaitResponse("req_1"));
        assertControlError(transport.awaitResponse("req_2"));
    }

    private static void assertControlError(Map<String, Object> response) {
        assertThat(response)
                .containsEntry("subtype", "error")
                .containsEntry("error", "Missing server_name or message for MCP request");
    }

    @Test
    void aCancellationSettlesAHungToolCall() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<ToolCallContext> context = new AtomicReference<>();

        SdkMcpTool<Map<String, Object>> slow = SdkMcpTool.create(
                "slow", "Blocks until released", Map.of(),
                (args, ctx) -> {
                    context.set(ctx);
                    started.countDown();
                    return CompletableFuture.supplyAsync(() -> {
                        try {
                            release.await(EXPECT_MS, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return ToolResult.text("finished");
                    });
                });
        RecordingTransport transport = start(Map.of("calc", serverWith(slow)));

        transport.feed(mcpControlRequest("req_call", "calc", callTool(7, "slow")));
        assertThat(started.await(EXPECT_MS, TimeUnit.MILLISECONDS)).isTrue();
        // Nothing has been answered yet: no 60-second cap fired, and the tool
        // is still running.
        assertThat(transport.responseFor("req_call")).isNull();

        transport.feed(mcpControlRequest("req_cancel", "calc",
                jsonRpc(null, "notifications/cancelled", Map.of("requestId", 7))));

        // The cancellation's own control request is acknowledged...
        assertThat(mcpResponseFor(transport, "req_cancel"))
                .isEqualTo(Map.of("jsonrpc", "2.0", "result", Map.of()));
        // ...and it arrives while the tool is still blocked, which is only
        // possible because each control request gets its own thread.
        assertThat(release.getCount()).isEqualTo(1);

        // The hung call is settled, and the handler can see why.
        assertThat(errorOf(mcpResponseFor(transport, "req_call")))
                .containsEntry("code", -32800)
                .containsEntry("message", "Request cancelled");
        assertThat(context.get().isCancelled()).isTrue();

        release.countDown();
    }

    @Test
    void closeSettlesAHungToolCallPromptly() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<ToolCallContext> context = new AtomicReference<>();

        SdkMcpTool<Map<String, Object>> slow = SdkMcpTool.create(
                "slow", "Never finishes on its own", Map.of(),
                (args, ctx) -> {
                    context.set(ctx);
                    started.countDown();
                    return CompletableFuture.supplyAsync(() -> {
                        try {
                            release.await(EXPECT_MS, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return ToolResult.text("finished");
                    });
                });
        RecordingTransport transport = start(Map.of("calc", serverWith(slow)));

        transport.feed(mcpControlRequest("req_call", "calc", callTool(7, "slow")));
        assertThat(started.await(EXPECT_MS, TimeUnit.MILLISECONDS)).isTrue();

        // Without the close-time drain, this would sit out the control
        // executor's full join timeout waiting on a tool nothing can interrupt.
        long before = System.nanoTime();
        lastHandler().close();
        long elapsedMs = (System.nanoTime() - before) / 1_000_000;

        assertThat(context.get().isCancelled()).isTrue();
        // Must not wait out the control executor's join timeout.
        assertThat(elapsedMs).isLessThan(4000);

        release.countDown();
    }

    // --- one server shared by two clients ---------------------------------

    /**
     * A tool whose calls stay open until the test finishes them, recorded by
     * the {@code who} argument so each client's call can be told apart.
     */
    static final class HeldCalls {

        final Map<String, CompletableFuture<ToolResult>> results = new ConcurrentHashMap<>();
        final Map<String, ToolCallContext> contexts = new ConcurrentHashMap<>();
        final CountDownLatch bothStarted = new CountDownLatch(2);

        SdkMcpServer server() {
            SdkMcpTool<Map<String, Object>> held = SdkMcpTool.create(
                    "held", "Answers when the test says so", Map.of("who", String.class),
                    (args, ctx) -> {
                        String who = String.valueOf(args.get("who"));
                        CompletableFuture<ToolResult> result = new CompletableFuture<>();
                        results.put(who, result);
                        contexts.put(who, ctx);
                        bothStarted.countDown();
                        return result;
                    });
            return SdkMcpServer.create("shared", "1.0.0", List.of(held));
        }

        ToolCallContext context(String who) {
            return requireNonNull(contexts.get(who));
        }

        void finish(String who) {
            requireNonNull(results.get(who)).complete(ToolResult.text("done for " + who));
        }
    }

    private static Map<String, Object> callHeld(Object id, String who) {
        return jsonRpc(id, "tools/call", Map.of("name", "held", "arguments", Map.of("who", who)));
    }

    @SuppressWarnings("unchecked")
    private static String textOf(Map<String, Object> mcpResponse) {
        assertThat(mcpResponse).as("expected a result, got: %s", mcpResponse).containsKey("result");
        Map<String, Object> result = (Map<String, Object>) mcpResponse.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        return String.valueOf(content.get(0).get("text"));
    }

    @Test
    void twoClientsMayUseTheSameRequestIdAtOnce() throws Exception {
        HeldCalls calls = new HeldCalls();
        SdkMcpServer shared = calls.server();
        RecordingTransport a = start(Map.of("srv", shared));
        RecordingTransport b = start(Map.of("srv", shared));

        // Every CLI numbers its JSON-RPC requests from the start, so two
        // clients reuse ids all the time. One server-wide table used to refuse
        // the second with "Request id 7 is already in flight".
        a.feed(mcpControlRequest("req_a", "srv", callHeld(7, "A")));
        b.feed(mcpControlRequest("req_b", "srv", callHeld(7, "B")));
        assertThat(calls.bothStarted.await(EXPECT_MS, TimeUnit.MILLISECONDS)).isTrue();

        calls.finish("B");
        calls.finish("A");

        assertThat(textOf(mcpResponseFor(a, "req_a"))).isEqualTo("done for A");
        assertThat(textOf(mcpResponseFor(b, "req_b"))).isEqualTo("done for B");
    }

    @Test
    void oneClientsCancellationLeavesTheOthersCallAlone() throws Exception {
        HeldCalls calls = new HeldCalls();
        SdkMcpServer shared = calls.server();
        RecordingTransport a = start(Map.of("srv", shared));
        RecordingTransport b = start(Map.of("srv", shared));

        a.feed(mcpControlRequest("req_a", "srv", callHeld(7, "A")));
        b.feed(mcpControlRequest("req_b", "srv", callHeld(7, "B")));
        assertThat(calls.bothStarted.await(EXPECT_MS, TimeUnit.MILLISECONDS)).isTrue();

        a.feed(mcpControlRequest("req_cancel", "srv",
                jsonRpc(null, "notifications/cancelled", Map.of("requestId", 7))));

        assertThat(errorOf(mcpResponseFor(a, "req_a"))).containsEntry("code", -32800);
        assertThat(calls.context("A").isCancelled()).isTrue();
        // B's request 7 is a different request, and it is still running.
        assertThat(calls.context("B").isCancelled()).isFalse();
        assertThat(b.responseFor("req_b")).isNull();

        calls.finish("B");
        assertThat(textOf(mcpResponseFor(b, "req_b"))).isEqualTo("done for B");
    }

    @Test
    void oneClientClosingLeavesTheOthersCallAlone() throws Exception {
        HeldCalls calls = new HeldCalls();
        SdkMcpServer shared = calls.server();
        RecordingTransport a = start(Map.of("srv", shared));
        QueryHandler clientA = lastHandler();
        RecordingTransport b = start(Map.of("srv", shared));

        a.feed(mcpControlRequest("req_a", "srv", callHeld(1, "A")));
        b.feed(mcpControlRequest("req_b", "srv", callHeld(2, "B")));
        assertThat(calls.bothStarted.await(EXPECT_MS, TimeUnit.MILLISECONDS)).isTrue();

        clientA.close();

        assertThat(calls.context("A").isCancelled()).isTrue();
        assertThat(calls.context("B").isCancelled()).isFalse();

        calls.finish("B");
        assertThat(textOf(mcpResponseFor(b, "req_b"))).isEqualTo("done for B");
    }

    @Test
    void aHandlerWithoutSessionsIsStillSharedAsBefore() throws Exception {
        // The SPI's default openSession() returns the handler itself, so a
        // hand-written handler sees every client's messages, as it always did.
        List<Object> seen = new ArrayList<>();
        McpMessageHandler plain = message -> {
            synchronized (seen) {
                seen.add(message.get("id"));
            }
            return CompletableFuture.completedFuture(
                    Map.of("jsonrpc", "2.0", "id", message.get("id"), "result", Map.of()));
        };
        RecordingTransport a = start(Map.of("srv", plain));
        RecordingTransport b = start(Map.of("srv", plain));

        a.feed(mcpControlRequest("req_a", "srv", jsonRpc(1, "ping", null)));
        b.feed(mcpControlRequest("req_b", "srv", jsonRpc(2, "ping", null)));

        assertThat(mcpResponseFor(a, "req_a")).containsEntry("id", 1);
        assertThat(mcpResponseFor(b, "req_b")).containsEntry("id", 2);
        assertThat(seen).containsExactlyInAnyOrder(1, 2);
    }

    // --- transport --------------------------------------------------------

    /** Feeds frames in and records the control responses written back. */
    static class RecordingTransport implements Transport {

        private final LinkedBlockingQueue<Map<String, Object>> inbound = new LinkedBlockingQueue<>();
        private final Map<String, Map<String, Object>> responses = new HashMap<>();
        private final AtomicBoolean ready = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        void feed(Map<String, Object> message) {
            inbound.add(message);
        }

        @Nullable
        Map<String, Object> responseFor(String requestId) {
            synchronized (responses) {
                return responses.get(requestId);
            }
        }

        Map<String, Object> awaitResponse(String requestId) throws InterruptedException {
            long deadline = System.currentTimeMillis() + EXPECT_MS;
            synchronized (responses) {
                while (!responses.containsKey(requestId)) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        throw new AssertionError("no control response for " + requestId);
                    }
                    responses.wait(remaining);
                }
                return requireNonNull(responses.get(requestId));
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

                @Override
                public boolean hasNext() {
                    while ((pending == null) && !closed.get()) {
                        try {
                            pending = inbound.poll(50, TimeUnit.MILLISECONDS);
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

        @SuppressWarnings({ "unchecked", "null" })
        @Override
        public void write(String data) throws CLIConnectionException {
            if (closed.get()) {
                throw new CLIConnectionException("Transport closed");
            }
            Map<String, Object> frame;
            try {
                frame = MAPPER.readValue(data, Map.class);
            } catch (Exception e) {
                throw new CLIConnectionException("Bad frame: " + e.getMessage(), e);
            }
            if (!"control_response".equals(frame.get("type"))) {
                return;
            }
            Map<String, Object> response = (Map<String, Object>) frame.get("response");
            if (response == null) {
                return;
            }
            synchronized (responses) {
                responses.put(String.valueOf(response.get("request_id")), response);
                responses.notifyAll();
            }
        }

        @Override
        public void endInput() {
            // Nothing to do: these tests never stream a prompt.
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
