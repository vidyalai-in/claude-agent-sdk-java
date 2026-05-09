package in.vidyalai.claude.sdk.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Tests for the JVM shutdown hook that terminates orphaned CLI subprocesses
 * (Python SDK v0.1.74 #916).
 *
 * <p>The hook can't be triggered without exiting the JVM, so we exercise
 * the same cleanup code path manually: register a real long-running
 * subprocess in the tracker set, then verify it can be destroyed and
 * dropped, just as the shutdown hook would do on parent exit.
 */
class SubprocessShutdownHookTest {

    @SuppressWarnings("unchecked")
    private static Set<Process> activeChildren() throws Exception {
        Field field = SubprocessCLITransport.class.getDeclaredField("ACTIVE_CHILDREN");
        field.setAccessible(true);
        return (Set<Process>) field.get(null);
    }

    @Test
    void activeChildrenSetExistsAndIsConcurrent() throws Exception {
        Set<Process> set = activeChildren();
        assertThat(set).isNotNull();
        // ConcurrentHashMap.newKeySet() returns a thread-safe KeySetView; the
        // backing class name confirms we're not using a non-concurrent set.
        assertThat(set.getClass().getName())
                .contains("ConcurrentHashMap");
    }

    @Test
    void destroyTerminatesTrackedSubprocess() throws Exception {
        // Spawn a real long-running subprocess (`sleep 30`) and verify the
        // shutdown-hook-equivalent destroy() flow terminates it. Mirrors
        // Python's TestAtexitChildCleanup.test_kill_active_children_terminates_process.
        ProcessBuilder pb = new ProcessBuilder("sleep", "30");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        Set<Process> set = activeChildren();
        set.add(proc);
        try {
            assertThat(proc.isAlive()).isTrue();

            // The hook does the same: iterate and destroy(), then clear.
            for (Process p : set) {
                if (p == proc) {
                    p.destroy();
                }
            }

            assertThat(proc.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(proc.isAlive()).isFalse();
        } finally {
            set.remove(proc);
            if (proc.isAlive()) {
                proc.destroyForcibly();
                proc.waitFor(2, TimeUnit.SECONDS);
            }
        }
    }

}
