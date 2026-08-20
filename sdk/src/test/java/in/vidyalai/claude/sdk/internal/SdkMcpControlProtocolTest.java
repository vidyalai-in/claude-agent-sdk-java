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
    void aMissingMessageFailsOnlyThatMcpCall() throws Exception {
        RecordingTransport transport = start(Map.of("calc", serverWith()));

        transport.feed(mcpControlRequest("req_1", "calc", null));

        assertThat(errorOf(mcpResponseFor(transport, "req_1"))).containsEntry("code", -32600);
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
