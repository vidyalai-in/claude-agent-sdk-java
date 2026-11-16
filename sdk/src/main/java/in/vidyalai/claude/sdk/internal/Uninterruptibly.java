package in.vidyalai.claude.sdk.internal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded waits that an interrupt does not cut short.
 *
 * <p>
 * The close paths must finish their graceful steps even when the closing
 * thread is interrupted, then restore the interrupt. Each method here waits up
 * to its timeout, records any interrupt in {@code interrupted} and keeps
 * waiting; the caller re-asserts the flag at the end.
 *
 * <p>
 * Internal: not part of the SDK's API.
 */
public final class Uninterruptibly {

    private Uninterruptibly() {
    }

    /**
     * {@link ExecutorService#awaitTermination}, recording interrupts.
     *
     * @return whether the executor terminated in time
     */
    public static boolean awaitTermination(ExecutorService executor, long timeout, TimeUnit unit,
            AtomicBoolean interrupted) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            try {
                return executor.awaitTermination(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        }
    }

    /**
     * {@link Process#waitFor(long, TimeUnit)}, recording interrupts.
     *
     * @return whether the process exited in time
     */
    public static boolean waitFor(Process process, long timeout, TimeUnit unit, AtomicBoolean interrupted) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            try {
                return process.waitFor(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        }
    }
}
