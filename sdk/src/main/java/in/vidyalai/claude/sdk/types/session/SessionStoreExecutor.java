package in.vidyalai.claude.sdk.types.session;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Configurable executor used by {@link SessionStore} async default methods.
 *
 * <p>Defaults to {@code Executors.newThreadPerTaskExecutor(...)} backed by a
 * named virtual-thread factory (each call spawns a fresh {@code Thread.ofVirtual()}
 * named {@code session-store-<n>}). Callers that need to cap concurrency, share
 * an executor with other async work, or enforce a different thread name pattern
 * can override the default via {@link #setDefault(Executor)} once at startup.
 *
 * <p>The configured executor is used by every {@code SessionStore.*Async}
 * default method that doesn't take an explicit {@code Executor} argument.
 * Adapters that override {@code *Async} directly bypass this entirely — the
 * executor only applies to the sync→async wrapping path.
 *
 * <p>Thread-safe: the default executor reference is held in an
 * {@link AtomicReference}, so the change is visible to subsequent calls.
 *
 * <h2>Examples</h2>
 *
 * <p>Use a bounded virtual-thread pool:
 *
 * <pre>{@code
 * ExecutorService bounded = Executors.newThreadPerTaskExecutor(
 *     Thread.ofVirtual().name("my-session-store-", 0).factory());
 * SessionStoreExecutor.setDefault(bounded);
 * }</pre>
 *
 * <p>Use a platform-thread pool (when virtual threads aren't desired):
 *
 * <pre>{@code
 * SessionStoreExecutor.setDefault(Executors.newFixedThreadPool(8));
 * }</pre>
 */
public final class SessionStoreExecutor {

    /**
     * Built-in default — a thread-per-task executor whose underlying factory
     * spawns named virtual threads. Each {@code execute(...)} call creates a
     * fresh virtual thread named {@code session-store-<n>}.
     */
    private static final Executor DEFAULT_VIRTUAL_THREAD_EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("session-store-", 0).factory());

    private static final AtomicReference<Executor> DEFAULT = new AtomicReference<>(DEFAULT_VIRTUAL_THREAD_EXECUTOR);

    private SessionStoreExecutor() {
    }

    /**
     * Returns the executor used by {@link SessionStore} async default methods.
     */
    public static Executor getDefault() {
        return DEFAULT.get();
    }

    /**
     * Override the default executor. Must be set once at startup before any
     * SessionStore async calls; subsequent calls are honored but in-flight
     * tasks already submitted to the previous executor continue on it.
     *
     * <p>Pass {@code null} to reset to the built-in thread-per-task executor
     * backed by named virtual threads.
     */
    public static void setDefault(Executor executor) {
        DEFAULT.set(executor != null ? executor : DEFAULT_VIRTUAL_THREAD_EXECUTOR);
    }

    /**
     * Reset to the built-in thread-per-task executor backed by named virtual
     * threads (useful in tests).
     */
    public static void reset() {
        DEFAULT.set(DEFAULT_VIRTUAL_THREAD_EXECUTOR);
    }

}
