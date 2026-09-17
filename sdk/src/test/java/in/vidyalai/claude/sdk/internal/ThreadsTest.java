package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Covers both halves of {@link Threads}: the virtual-thread path taken on a
 * Java 21+ runtime, and the platform fallback the SDK uses on 17-20.
 *
 * <p>The fallback is reachable on any JDK because {@code Threads} reads
 * {@code claude.sdk.virtualThreads} once, in its static initializer. These
 * tests reload the class in a throwaway loader with that property set, which is
 * what lets the 17 code path be exercised on a modern JDK instead of only in a
 * JDK 17 CI leg.
 */
class ThreadsTest {

    // AssertJ's assertThat(boolean) returns a wildcard-captured self type, so a
    // chained .as(...).isTrue() reads as a potential null dereference to JDT's
    // null analysis. Asserting on the value as an Object picks ObjectAssert,
    // whose self type is concrete. Keep the cast if you touch these.

    /** Whether this JVM has {@code Thread.ofVirtual()} at all. */
    private static boolean runtimeHasVirtualThreads() {
        try {
            Thread.class.getMethod("ofVirtual");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** Loads a private copy of {@link Threads}, isolated from the app loader. */
    private static final class ReloadingLoader extends ClassLoader {
        private static final String TARGET = "in.vidyalai.claude.sdk.internal.Threads";

        ReloadingLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!TARGET.equals(name)) {
                return super.loadClass(name, resolve);
            }
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                String resource = name.replace('.', '/') + ".class";
                try (InputStream in = getParent().getResourceAsStream(resource)) {
                    if (in == null) {
                        throw new ClassNotFoundException(name);
                    }
                    byte[] bytes = in.readAllBytes();
                    loaded = defineClass(name, bytes, 0, bytes.length);
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    /** Reloads {@code Threads} with virtual threads forced off. */
    private static Class<?> fallbackThreads() throws Exception {
        String previous = System.getProperty("claude.sdk.virtualThreads");
        System.setProperty("claude.sdk.virtualThreads", "false");
        try {
            // Class.forName(..., initialize=true): loadClass alone would defer the
            // static initializer until after the property is restored below, and
            // the reloaded copy would silently pick up the virtual-thread path.
            return Class.forName("in.vidyalai.claude.sdk.internal.Threads", true,
                    new ReloadingLoader(ThreadsTest.class.getClassLoader()));
        } finally {
            if (previous == null) {
                System.clearProperty("claude.sdk.virtualThreads");
            } else {
                System.setProperty("claude.sdk.virtualThreads", previous);
            }
        }
    }

    private static Object invoke(Class<?> type, String method, Class<?>[] sig, Object... args)
            throws Exception {
        Method m = type.getDeclaredMethod(method, sig);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    // ==================== Runtime detection ====================

    @Test
    void detectionMatchesTheRuntime() {
        assertThat(Threads.virtualThreadsAvailable()).isEqualTo(runtimeHasVirtualThreads());
    }

    @Test
    void systemPropertyForcesThePlatformPath() throws Exception {
        Class<?> fallback = fallbackThreads();
        // invoke(...) already hands back an Object, so this is ObjectAssert and the
        // chained .as(...) stays clear of JDT's wildcard-capture null warning.
        assertThat(invoke(fallback, "virtualThreadsAvailable", new Class<?>[0]))
                .as("claude.sdk.virtualThreads=false must disable virtual threads")
                .isEqualTo(false);
    }

    // ==================== Naming ====================

    @Test
    void factoryUsesVirtualThreadsWhenAvailable() throws Exception {
        Thread thread = Threads.factory("virtual-test-").newThread(() -> { });
        assertThat(isVirtual(thread)).isEqualTo(runtimeHasVirtualThreads());
    }

    @Test
    void factoryNamesThreadsWithACounter() {
        ThreadFactory factory = Threads.factory("naming-test-");
        assertThat(factory.newThread(() -> { }).getName()).isEqualTo("naming-test-0");
        assertThat(factory.newThread(() -> { }).getName()).isEqualTo("naming-test-1");
    }

    @Test
    void fallbackFactoryUsesTheSameNames() throws Exception {
        Class<?> fallback = fallbackThreads();
        ThreadFactory factory = (ThreadFactory)
                invoke(fallback, "factory", new Class<?>[] {String.class}, "naming-test-");
        assertThat(factory.newThread(() -> { }).getName()).isEqualTo("naming-test-0");
        assertThat(factory.newThread(() -> { }).getName()).isEqualTo("naming-test-1");
    }

    // ==================== Daemon status ====================

    @Test
    void fallbackThreadsAreDaemons() throws Exception {
        // Virtual threads are always daemons. If the platform fallback were not,
        // every SDK thread would keep the host JVM alive after main() returned.
        Class<?> fallback = fallbackThreads();
        ThreadFactory factory = (ThreadFactory)
                invoke(fallback, "factory", new Class<?>[] {String.class}, "daemon-test-");
        Thread thread = factory.newThread(() -> { });
        assertThat(thread.isDaemon()).isTrue();
        assertThat((Object) isVirtual(thread))
                .as("fallback must produce platform threads")
                .isEqualTo(false);
    }

    /** {@code Thread.isVirtual()} is Java 21, so reach it reflectively. */
    private static boolean isVirtual(Thread thread) throws Exception {
        if (!runtimeHasVirtualThreads()) {
            return false;
        }
        return (Boolean) Thread.class.getMethod("isVirtual").invoke(thread);
    }

    @Test
    void fallbackStartedThreadsAreDaemons() throws Exception {
        Class<?> fallback = fallbackThreads();
        CountDownLatch running = new CountDownLatch(1);
        Thread thread = (Thread) invoke(fallback, "start",
                new Class<?>[] {String.class, Runnable.class},
                "daemon-start-", (Runnable) running::countDown);
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(thread.isDaemon()).isTrue();
        assertThat(thread.getName()).isEqualTo("daemon-start-0");
    }

    // ==================== Unboundedness ====================

    /**
     * The control executor parks a thread for the whole duration of an SDK MCP
     * tool call, and the cancellation that ends it arrives as a separate task.
     * If the executor were bounded, that cancellation would queue behind the
     * call it exists to cancel. Both paths must run every submitted task
     * concurrently, so this asserts the property directly rather than trusting
     * the factory type.
     */
    @Test
    void threadPerTaskExecutorRunsEveryTaskConcurrently() throws Exception {
        assertUnbounded(Threads.newThreadPerTaskExecutor("unbounded-test-"));
    }

    @Test
    void fallbackThreadPerTaskExecutorRunsEveryTaskConcurrently() throws Exception {
        Class<?> fallback = fallbackThreads();
        assertUnbounded((ExecutorService) invoke(fallback, "newThreadPerTaskExecutor",
                new Class<?>[] {String.class}, "unbounded-fallback-"));
    }

    private static void assertUnbounded(ExecutorService executor) throws Exception {
        int tasks = 64;
        CountDownLatch allStarted = new CountDownLatch(tasks);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        try {
            for (int i = 0; i < tasks; i++) {
                executor.submit(() -> {
                    synchronized (threads) {
                        threads.add(Thread.currentThread());
                    }
                    allStarted.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            // Generous on purpose: a bounded pool never starts all 64 regardless
            // of how long we wait, so a long timeout costs nothing in detection
            // power and stops a loaded CI box from failing the build spuriously.
            assertThat((Object) allStarted.await(60, TimeUnit.SECONDS))
                    .as("all %d tasks must run at once; a bounded pool would deadlock here", tasks)
                    .isEqualTo(true);
            synchronized (threads) {
                assertThat(threads).doesNotHaveDuplicates();
            }
        } finally {
            release.countDown();
            executor.shutdown();
            executor.awaitTermination(60, TimeUnit.SECONDS);
        }
    }

    // ==================== Single-thread executor ====================

    @Test
    void singleThreadExecutorUsesTheGivenName() throws Exception {
        ExecutorService executor = Threads.newSingleThreadExecutor("single-test-");
        try {
            assertThat(executor.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS))
                    .isEqualTo("single-test-0");
        } finally {
            executor.shutdownNow();
        }
    }
}
