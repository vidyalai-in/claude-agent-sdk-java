package in.vidyalai.claude.sdk.types.session;

import in.vidyalai.claude.sdk.internal.Threads;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Configurable executor used by {@link SessionStore} async default methods.
 *
 * <p>Defaults to an unbounded thread-per-task executor whose threads are named
 * {@code session-store-<n>}. On a Java 21+ runtime those are virtual threads; on
 * Java 17-20 they are daemon platform threads from a cached pool. The SDK
 * compiles against Java 17 and selects the better option at runtime, so this
 * default is runtime-dependent rather than fixed. Callers that need to cap
 * concurrency, share an executor with other async work, or enforce a different
 * thread name pattern can override the default via {@link #setDefault(Executor)}
 * once at startup.
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
 * <p>Use your own virtual-thread executor (requires Java 21+ at compile time in
 * <em>your</em> project; the SDK itself does not require it):
 *
 * <pre>{@code
 * ExecutorService mine = Executors.newThreadPerTaskExecutor(
 *     Thread.ofVirtual().name("my-session-store-", 0).factory());
 * SessionStoreExecutor.setDefault(mine);
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
     * Built-in default — an unbounded thread-per-task executor producing threads
     * named {@code session-store-<n>}: virtual threads on Java 21+, daemon
     * platform threads otherwise.
     */
    private static final Executor DEFAULT_VIRTUAL_THREAD_EXECUTOR = Threads.newThreadPerTaskExecutor("session-store-");

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
     * <p>Pass {@code null} to reset to the built-in thread-per-task executor.
     */
    public static void setDefault(Executor executor) {
        DEFAULT.set(executor != null ? executor : DEFAULT_VIRTUAL_THREAD_EXECUTOR);
    }

    /**
     * Reset to the built-in thread-per-task executor (useful in tests).
     */
    public static void reset() {
        DEFAULT.set(DEFAULT_VIRTUAL_THREAD_EXECUTOR);
    }

}
