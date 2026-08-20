package in.vidyalai.claude.sdk.mcp;

import static java.util.Objects.requireNonNull;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * {@code notifications/cancelled}, end to end inside the server.
 *
 * <p>
 * The CLI does send this — verified live: with a short {@code MCP_TOOL_TIMEOUT}
 * and a sleeping tool, an SDK MCP server receives
 * {@code [initialize, notifications/initialized, tools/list, tools/call,
 * notifications/cancelled]}. It used to be answered with a {@code -32601} (a
 * response to a notification, which JSON-RPC forbids) and cancelled nothing,
 * so a tool the CLI had given up on ran on with its side effects.
 *
 * <p>
 * A {@code CompletableFuture} cannot be interrupted from outside — {@code
 * cancel(true)} completes the future and leaves the work running — so the
 * handler has to be able to see the cancellation. That is what
 * {@link ToolCallContext} is for, and most of what these tests check.
 */
class SdkMcpCancellationTest {

    private static final int TIMEOUT_SECONDS = 5;

    /** A tool that blocks until released, reporting what it observed. */
    private static final class BlockingTool {

        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<ToolCallContext> context = new AtomicReference<>();

        SdkMcpTool<Map<String, Object>> tool(String name) {
            return SdkMcpTool.create(name, "Blocks until released", Map.of(),
                    (args, ctx) -> {
                        context.set(ctx);
                        started.countDown();
                        return CompletableFuture.supplyAsync(() -> {
                            await(release);
                            return ToolResult.text("finished");
                        });
                    });
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> callToolMessage(Object id, String tool) {
        Map<String, Object> message = new HashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", "tools/call");
        message.put("params", Map.of("name", tool, "arguments", Map.of()));
        return message;
    }

    private static Map<String, Object> cancelMessage(Object requestId) {
        Map<String, Object> message = new HashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("method", "notifications/cancelled");
        message.put("params", Map.of("requestId", requestId, "reason", "timed out"));
        return message;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorOf(Map<String, Object> response) {
        assertThat(response).as("expected an error, got: %s", response).containsKey("error");
        return (Map<String, Object>) response.get("error");
    }

    @Test
    void cancellationSettlesThePendingCallAndSendsNoReplyOfItsOwn() throws Exception {
        BlockingTool blocking = new BlockingTool();
        SdkMcpServer server = SdkMcpServer.create("test", List.of(blocking.tool("slow")));

        CompletableFuture<Map<String, Object>> pending = server.handleMessage(callToolMessage(7, "slow"));
        await(blocking.started);
        assertThat(pending).isNotDone();

        // The notification itself must produce nothing at all.
        assertThat(server.handleMessage(cancelMessage(7)).get()).isNull();

        Map<String, Object> response = pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(response).containsEntry("id", 7);
        assertThat(errorOf(response))
                .containsEntry("code", -32800)
                .containsEntry("message", "Request cancelled");

        blocking.release.countDown();
    }

    @Test
    void theHandlerCanSeeThatItWasCancelled() throws Exception {
        BlockingTool blocking = new BlockingTool();
        SdkMcpServer server = SdkMcpServer.create("test", List.of(blocking.tool("slow")));

        server.handleMessage(callToolMessage(7, "slow"));
        await(blocking.started);
        assertThat(blocking.context.get().isCancelled()).isFalse();

        server.handleMessage(cancelMessage(7)).get();

        assertThat(blocking.context.get().isCancelled()).isTrue();
        blocking.release.countDown();
    }

    @Test
    void onCancelRunsOnceWhenTheCallIsCancelled() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        CountDownLatch registered = new CountDownLatch(1);
        AtomicReference<ToolCallContext> seen = new AtomicReference<>();

        SdkMcpTool<Map<String, Object>> tool = SdkMcpTool.create(
                "slow", "Registers a cancellation listener", Map.of(),
                (args, ctx) -> {
                    seen.set(ctx);
                    ctx.onCancel(fired::incrementAndGet);
                    registered.countDown();
                    return new CompletableFuture<>();
                });
        SdkMcpServer server = SdkMcpServer.create("test", List.of(tool));

        server.handleMessage(callToolMessage(7, "slow"));
        await(registered);
        assertThat(fired).hasValue(0);

        server.handleMessage(cancelMessage(7)).get();
        assertThat(fired).hasValue(1);

        // Registering afterwards still fires, and does not re-run the first.
        seen.get().onCancel(fired::incrementAndGet);
        assertThat(fired).hasValue(2);
    }

    @Test
    void aResultThatArrivesAfterCancellationIsDiscarded() throws Exception {
        BlockingTool blocking = new BlockingTool();
        SdkMcpServer server = SdkMcpServer.create("test", List.of(blocking.tool("slow")));

        CompletableFuture<Map<String, Object>> pending = server.handleMessage(callToolMessage(7, "slow"));
        await(blocking.started);
        server.handleMessage(cancelMessage(7)).get();

        blocking.release.countDown();

        // The handler runs to completion — nothing can stop it — but the call
        // was already answered, so its result goes nowhere.
        Map<String, Object> response = pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(errorOf(response)).containsEntry("code", -32800);
        assertThat(response).doesNotContainKey("result");
    }

    @Test
    void aHandlerThatThrowsAfterCancellationDoesNotBreakTheServer() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SdkMcpTool<Map<String, Object>> tool = SdkMcpTool.create(
                "slow", "Unwinds when cancelled", Map.of(),
                (args, ctx) -> {
                    started.countDown();
                    return CompletableFuture.supplyAsync(() -> {
                        await(release);
                        ctx.throwIfCancelled();
                        return ToolResult.text("never");
                    });
                });
        SdkMcpServer server = SdkMcpServer.create("test", List.of(tool));

        CompletableFuture<Map<String, Object>> pending = server.handleMessage(callToolMessage(7, "slow"));
        await(started);
        server.handleMessage(cancelMessage(7)).get();
        release.countDown();

        assertThat(errorOf(pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)))
                .containsEntry("code", -32800);
        // ...and the server carries on.
        assertThat(server.handleMessage(Map.of(
                "jsonrpc", "2.0", "id", 8, "method", "tools/list")).get())
                .containsKey("result");
    }

    @Test
    void cancellingSomethingThatIsNotRunningIsIgnored() throws Exception {
        SdkMcpServer server = SdkMcpServer.create("test", List.of());

        assertThat(server.handleMessage(cancelMessage(999)).get()).isNull();

        Map<String, Object> noRequestId = new HashMap<>();
        noRequestId.put("jsonrpc", "2.0");
        noRequestId.put("method", "notifications/cancelled");
        noRequestId.put("params", Map.of("reason", "no id"));
        assertThat(server.handleMessage(noRequestId).get()).isNull();
    }

    @Test
    void aCancellationMatchesItsCallEvenWhenTheIdsAreBoxedDifferently() throws Exception {
        BlockingTool blocking = new BlockingTool();
        SdkMcpServer server = SdkMcpServer.create("test", List.of(blocking.tool("slow")));

        CompletableFuture<Map<String, Object>> pending = server.handleMessage(callToolMessage(7, "slow"));
        await(blocking.started);
        // The CLI's requestId need not decode to the same numeric type as the
        // id it is cancelling.
        server.handleMessage(cancelMessage(7L)).get();

        assertThat(errorOf(pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)))
                .containsEntry("code", -32800);
        blocking.release.countDown();
    }

    @Test
    void reusingAnInFlightRequestIdIsRefused() throws Exception {
        BlockingTool blocking = new BlockingTool();
        SdkMcpServer server = SdkMcpServer.create("test", List.of(blocking.tool("slow")));

        server.handleMessage(callToolMessage(7, "slow"));
        await(blocking.started);

        Map<String, Object> second = server.handleMessage(callToolMessage(7, "slow"))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(errorOf(second)).containsEntry("code", -32603);
        assertThat(String.valueOf(errorOf(second).get("message"))).contains("already in flight");

        blocking.release.countDown();
    }

    @Test
    void closeSettlesEveryCallStillRunning() throws Exception {
        BlockingTool blocking = new BlockingTool();
        SdkMcpServer server = SdkMcpServer.create("test", List.of(blocking.tool("slow")));

        CompletableFuture<Map<String, Object>> pending = server.handleMessage(callToolMessage(7, "slow"));
        await(blocking.started);

        server.close();

        assertThat(errorOf(pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)))
                .containsEntry("code", -32800);
        assertThat(blocking.context.get().isCancelled()).isTrue();
        blocking.release.countDown();
    }

    @Test
    void closeIsIdempotentAndLeavesTheServerUsable() throws Exception {
        // close() means "the connection using you is going away", not "shut
        // down": one server can be registered with more than one client, and
        // has to keep working for the next one.
        BlockingTool blocking = new BlockingTool();
        SdkMcpServer server = SdkMcpServer.create("test", List.of(blocking.tool("slow")));

        server.close();
        server.close();

        blocking.release.countDown();
        Map<String, Object> response = server.handleMessage(callToolMessage(1, "slow"))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(response).containsKey("result");
    }

    // --- the handler API itself

    @Test
    void aHandlerThatTakesOnlyItsArgumentsStillWorks() throws Exception {
        SdkMcpTool<Map<String, Object>> tool = SdkMcpTool.create(
                "plain", "Takes only its arguments", Map.of(),
                args -> CompletableFuture.completedFuture(ToolResult.text("ok")));

        assertThat(tool.invoke(Map.of()).get().content())
                .singleElement()
                .satisfies(block -> assertThat(block).containsEntry("text", "ok"));
    }

    @Test
    void invokingWithoutAContextUsesOneThatIsNeverCancelled() throws Exception {
        AtomicReference<ToolCallContext> seen = new AtomicReference<>();
        SdkMcpTool<Map<String, Object>> tool = SdkMcpTool.create(
                "ctx", "Records its context", Map.of(),
                (args, ctx) -> {
                    seen.set(ctx);
                    return CompletableFuture.completedFuture(ToolResult.text("ok"));
                });

        tool.invoke(Map.of()).get();

        assertThat(seen.get().isCancelled()).isFalse();
        seen.get().onCancel(() -> {
            throw new AssertionError("a context that is never cancelled must not fire");
        });
    }

    // --- the @Tool annotation path

    static class AnnotatedTools {

        @Tool(name = "with_context", description = "Takes a context alongside its arguments")
        public ToolResult withContext(Map<String, Object> args, ToolCallContext context) {
            return ToolResult.text("cancelled=" + context.isCancelled());
        }

        @Tool(name = "typed_with_context", description = "Takes typed arguments and a context")
        public ToolResult typedWithContext(String city, ToolCallContext context) {
            return ToolResult.text(city + ":" + context.isCancelled());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> listedTool(SdkMcpServer server, String name) throws Exception {
        Map<String, Object> response = requireNonNull(server.handleMessage(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "tools/list")).get());
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        return tools.stream()
                .filter(tool -> name.equals(tool.get("name")))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void anAnnotatedMethodMayDeclareAContextParameter() throws Exception {
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("test", new AnnotatedTools());

        Map<String, Object> response = server.handleMessage(
                callToolMessage(1, "with_context")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(response).containsKey("result");
        // The context is injected, not read out of the arguments.
        assertThat(String.valueOf(response)).contains("cancelled=false");
    }

    @Test
    void anInjectedContextIsNotPublishedAsAToolArgument() throws Exception {
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("test", new AnnotatedTools());

        @SuppressWarnings("unchecked")
        Map<String, Object> schema =
                (Map<String, Object>) listedTool(server, "typed_with_context").get("inputSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties).containsOnlyKeys("city");
        assertThat(schema.get("required")).isEqualTo(List.of("city"));
    }

    @Test
    void aContextOnlyMapMethodStillGetsTheWholeArgumentMap() throws Exception {
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("test", new AnnotatedTools());

        @SuppressWarnings("unchecked")
        Map<String, Object> schema =
                (Map<String, Object>) listedTool(server, "with_context").get("inputSchema");

        // The Map parameter is still the "give me everything" shape, and the
        // context beside it does not turn this into a typed signature.
        assertThat(schema).containsEntry("type", "object");
        assertThat(schema.get("properties")).isEqualTo(Map.of());
    }

}
