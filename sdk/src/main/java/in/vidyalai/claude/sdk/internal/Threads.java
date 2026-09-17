package in.vidyalai.claude.sdk.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread and executor factory that uses virtual threads when the runtime
 * provides them and named daemon platform threads otherwise.
 *
 * <p>The SDK compiles against Java 17, but virtual threads ({@code Thread.ofVirtual()},
 * {@code Executors.newThreadPerTaskExecutor}) only exist from Java 21. Both are
 * resolved reflectively <em>once</em> into {@code static final} method handles
 * here; nothing on a hot path ever performs a lookup. On a 21+ runtime the
 * behaviour is identical to calling the APIs directly.
 *
 * <p>Two properties of the fallback are load-bearing rather than cosmetic:
 *
 * <ul>
 *   <li>{@link #newThreadPerTaskExecutor(String)} stays <em>unbounded</em>. Its
 *       caller in {@code QueryHandler} parks a thread for the whole duration of
 *       an SDK MCP tool call, and the {@code notifications/cancelled} that ends
 *       that call arrives as a separate control request. Under a bounded pool
 *       the cancellation queues behind the call it exists to cancel and
 *       deadlocks.
 *   <li>Fallback platform threads are <em>daemon</em> threads. Virtual threads
 *       always are, so anything else would turn every SDK thread into a
 *       JVM-exit blocker for applications on a 17 runtime.
 * </ul>
 *
 * <p>Setting the system property {@code claude.sdk.virtualThreads} to
 * {@code false} forces the platform-thread path on any JDK. It exists so the
 * fallback stays testable on a modern JDK, and so applications that would
 * rather not run SDK work on virtual threads can opt out.
 *
 * <p>Internal API: not part of the SDK's supported surface and may change
 * in any release.
 */
public final class Threads {

    /** {@code Thread.ofVirtual()} — returns a {@code Thread.Builder.OfVirtual}. */
    private static final MethodHandle OF_VIRTUAL;
    /** {@code Thread.Builder.OfVirtual.name(String, long)}. */
    private static final MethodHandle BUILDER_NAME;
    /** {@code Thread.Builder.factory()}. */
    private static final MethodHandle BUILDER_FACTORY;
    /** {@code Executors.newThreadPerTaskExecutor(ThreadFactory)}. */
    private static final MethodHandle NEW_THREAD_PER_TASK;

    private static final boolean VIRTUAL;

    static {
        MethodHandle ofVirtual = null;
        MethodHandle builderName = null;
        MethodHandle builderFactory = null;
        MethodHandle perTask = null;
        boolean virtual = false;

        if (!"false".equalsIgnoreCase(System.getProperty("claude.sdk.virtualThreads"))) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                // Resolve against the public interfaces, not the concrete builder
                // implementation, which is not exported.
                Class<?> ofVirtualType = Class.forName("java.lang.Thread$Builder$OfVirtual");
                Class<?> builderType = Class.forName("java.lang.Thread$Builder");

                ofVirtual = lookup.findStatic(Thread.class, "ofVirtual",
                        MethodType.methodType(ofVirtualType));
                builderName = lookup.findVirtual(ofVirtualType, "name",
                        MethodType.methodType(ofVirtualType, String.class, long.class));
                builderFactory = lookup.findVirtual(builderType, "factory",
                        MethodType.methodType(ThreadFactory.class));
                perTask = lookup.findStatic(Executors.class, "newThreadPerTaskExecutor",
                        MethodType.methodType(ExecutorService.class, ThreadFactory.class));
                virtual = true;
            } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
                // Java 17-20, or a runtime without virtual threads. Fall back.
                virtual = false;
            }
        }

        OF_VIRTUAL = ofVirtual;
        BUILDER_NAME = builderName;
        BUILDER_FACTORY = builderFactory;
        NEW_THREAD_PER_TASK = perTask;
        VIRTUAL = virtual;
    }

    private Threads() {
    }

    /**
     * Whether this runtime supplies virtual threads and the opt-out has not
     * been set. Exposed for diagnostics and tests.
     */
    public static boolean virtualThreadsAvailable() {
        return VIRTUAL;
    }

    /**
     * A thread factory producing threads named {@code namePrefix + n}, counting
     * from zero. Virtual when available, daemon platform threads otherwise.
     *
     * @param namePrefix name prefix including any trailing separator, e.g. {@code "QueryHandler-Reader-"}
     */
    public static ThreadFactory factory(String namePrefix) {
        if (VIRTUAL) {
            try {
                Object builder = OF_VIRTUAL.invoke();
                builder = BUILDER_NAME.invoke(builder, namePrefix, 0L);
                return (ThreadFactory) BUILDER_FACTORY.invoke(builder);
            } catch (Throwable t) {
                // Resolution succeeded but invocation did not; a platform
                // factory is always a correct answer, so degrade rather than
                // fail construction of whatever asked for the factory.
                return platformFactory(namePrefix);
            }
        }
        return platformFactory(namePrefix);
    }

    /**
     * Daemon platform threads named {@code namePrefix + n}. Daemon status
     * mirrors virtual threads, which are always daemon.
     */
    private static ThreadFactory platformFactory(String namePrefix) {
        AtomicLong counter = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, namePrefix + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Single-threaded executor backed by {@link #factory(String)}. */
    public static ExecutorService newSingleThreadExecutor(String namePrefix) {
        return Executors.newSingleThreadExecutor(factory(namePrefix));
    }

    /**
     * Unbounded executor that runs each task on its own thread.
     *
     * <p>On 21+ this is {@code Executors.newThreadPerTaskExecutor}. On 17 it is
     * a cached pool, which is also unbounded and additionally reuses idle
     * threads — the bound is what matters here, not the reuse. Never substitute
     * a fixed pool; see the class javadoc.
     */
    public static ExecutorService newThreadPerTaskExecutor(String namePrefix) {
        ThreadFactory threadFactory = factory(namePrefix);
        if (VIRTUAL) {
            try {
                return (ExecutorService) NEW_THREAD_PER_TASK.invoke(threadFactory);
            } catch (Throwable t) {
                return Executors.newCachedThreadPool(threadFactory);
            }
        }
        return Executors.newCachedThreadPool(threadFactory);
    }

    /**
     * Starts one thread and returns it. Virtual when available, a daemon
     * platform thread otherwise.
     *
     * <p>The thread is named {@code namePrefix + "0"}, matching
     * {@link #factory(String)} — this goes through a fresh single-use factory
     * rather than a shared one, so the counter always starts at zero.
     *
     * @param namePrefix name prefix including any trailing separator, e.g. {@code "Sessions-WorktreeReader-"}
     */
    public static Thread start(String namePrefix, Runnable task) {
        Thread thread;
        if (VIRTUAL) {
            try {
                Object builder = OF_VIRTUAL.invoke();
                builder = BUILDER_NAME.invoke(builder, namePrefix, 0L);
                ThreadFactory threadFactory = (ThreadFactory) BUILDER_FACTORY.invoke(builder);
                thread = threadFactory.newThread(task);
            } catch (Throwable t) {
                thread = newDaemon(namePrefix, task);
            }
        } else {
            thread = newDaemon(namePrefix, task);
        }
        thread.start();
        return thread;
    }

    private static Thread newDaemon(String namePrefix, Runnable task) {
        Thread thread = new Thread(task, namePrefix + "0");
        thread.setDaemon(true);
        return thread;
    }
}
