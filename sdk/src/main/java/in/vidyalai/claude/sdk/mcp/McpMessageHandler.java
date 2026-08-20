package in.vidyalai.claude.sdk.mcp;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

/**
 * Serves JSON-RPC for one in-process MCP server.
 *
 * <p>
 * The CLI drives an in-process ("SDK") MCP server over the control protocol:
 * each JSON-RPC message arrives as a decoded map inside an
 * {@code mcp_message} control request, and the reply — when one is due — goes
 * back the same way. This interface is that contract, and
 * {@link SdkMcpServer} is the implementation the SDK ships.
 *
 * <p>
 * Implement it directly to serve parts of MCP that {@link SdkMcpServer} does
 * not: resources, prompts, completions, or an adapter over a third-party MCP
 * library. Register the result the same way a built-in server is registered:
 *
 * <pre>{@code
 * McpMessageHandler handler = new MyMcpServer();
 *
 * var options = ClaudeAgentOptions.builder()
 *         .mcpServers(Map.of("mine", new McpSdkServerConfig("mine", handler)))
 *         .build();
 * }</pre>
 *
 * <p>
 * Whatever the implementation, the CLI decides what to send based on the
 * {@code capabilities} returned from {@code initialize} — a server that
 * advertises only {@code tools} is never asked for resources or prompts.
 *
 * @see SdkMcpServer
 * @see in.vidyalai.claude.sdk.types.mcp.McpSdkServerConfig
 */
@FunctionalInterface
public interface McpMessageHandler {

    /**
     * Handles one JSON-RPC message from the CLI.
     *
     * <p>
     * Return the response for a request, or {@code null} for a message that
     * expects none — a notification, or a JSON-RPC response. JSON-RPC forbids
     * replying to a notification, so returning a value for one is a protocol
     * violation; the caller acknowledges the enclosing control request on the
     * handler's behalf.
     *
     * <p>
     * A failure should be reported as a JSON-RPC error object in the returned
     * response rather than by completing the future exceptionally. An
     * exceptional completion is still handled — it becomes a {@code -32603}
     * for that one message — but the handler knows the better error code.
     *
     * <p>
     * Called concurrently: the CLI can have several messages in flight
     * against one server, each on its own virtual thread.
     *
     * @param message the JSON-RPC message, as a decoded map
     * @return a future with the response, or with {@code null} when no reply
     *         is due
     */
    CompletableFuture<@Nullable Map<String, Object>> handleMessage(Map<String, Object> message);

    /**
     * Releases whatever this handler is holding for the connection that is
     * going away.
     *
     * <p>
     * Called when the {@code ClaudeSDKClient} (or one-shot {@code query})
     * using this handler shuts down. A handler with work in flight should
     * abandon it — nothing it produces afterwards can reach the CLI.
     *
     * <p>
     * This is <b>not</b> a permanent shutdown. One handler can be registered
     * with more than one client, so an implementation must be idempotent here
     * and must stay usable for a later connection.
     *
     * <p>
     * The default does nothing.
     */
    default void close() {
        // Nothing to release by default.
    }

}
