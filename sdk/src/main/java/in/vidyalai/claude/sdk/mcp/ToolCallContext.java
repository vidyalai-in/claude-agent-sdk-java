package in.vidyalai.claude.sdk.mcp;

import java.util.concurrent.CancellationException;

/**
 * The cancellation signal for one {@code tools/call}.
 *
 * <p>
 * The CLI gives up on a tool that has run too long by sending MCP's
 * {@code notifications/cancelled}. The call is then answered without the
 * handler's result — but the handler itself keeps running unless it looks,
 * because a {@code CompletableFuture} cannot be interrupted from the outside:
 * {@link java.util.concurrent.CompletableFuture#cancel(boolean) cancel(true)}
 * completes the future and leaves the work alone. A tool that does anything
 * long or anything with side effects should therefore check this:
 *
 * <pre>{@code
 * SdkMcpTool<Map<String, Object>> crawl = SdkMcpTool.create(
 *         "crawl", "Fetch every page under a URL", schema,
 *         (args, ctx) -> CompletableFuture.supplyAsync(() -> {
 *             List<String> pages = new ArrayList<>();
 *             for (String url : urlsFrom(args)) {
 *                 if (ctx.isCancelled()) {
 *                     break;
 *                 }
 *                 pages.add(fetch(url));
 *             }
 *             return ToolResult.text(String.join("\n", pages));
 *         }));
 * }</pre>
 *
 * <p>
 * Handlers that take only their arguments keep working unchanged; they simply
 * cannot observe cancellation.
 *
 * @see SdkMcpTool
 */
public interface ToolCallContext {

    /**
     * Whether this call has been cancelled.
     *
     * <p>
     * Once true, always true. A handler that returns after this point has its
     * result discarded — the call was already answered — so the cheapest
     * correct response is to stop and return anything.
     *
     * @return true if the CLI has cancelled the call
     */
    boolean isCancelled();

    /**
     * Registers a callback to run when this call is cancelled.
     *
     * <p>
     * For work that cannot poll {@link #isCancelled()} — a blocking read, a
     * request to another service — this is where to close the resource or
     * interrupt the thread. Runs immediately, on the calling thread, if the
     * call is already cancelled.
     *
     * <p>
     * A callback that throws is logged and ignored; it never affects the
     * cancellation itself or the other callbacks.
     *
     * @param callback the action to run on cancellation
     */
    void onCancel(Runnable callback);

    /**
     * Throws if this call has been cancelled.
     *
     * <p>
     * A checkpoint for a handler that would rather unwind than test a flag at
     * every step. The resulting failure is discarded, not reported — the call
     * was already answered — so this is a clean way out, not an error.
     *
     * @throws CancellationException if the CLI has cancelled the call
     */
    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("The tool call was cancelled");
        }
    }

    /**
     * A context that is never cancelled.
     *
     * <p>
     * For unit-testing a handler directly, and for the paths that invoke a
     * tool outside a live {@code tools/call}.
     *
     * @return a no-op context
     */
    static ToolCallContext notCancelled() {
        return NeverCancelled.INSTANCE;
    }

    /** The {@link #notCancelled()} singleton. */
    final class NeverCancelled implements ToolCallContext {

        private static final NeverCancelled INSTANCE = new NeverCancelled();

        private NeverCancelled() {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void onCancel(Runnable callback) {
            // Never cancelled, so the callback can never be due.
        }

    }

}
