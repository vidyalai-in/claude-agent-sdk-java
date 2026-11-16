package in.vidyalai.claude.sdk.internal;

import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.WAIT_SECONDS;
import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.attach;
import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.mirror;
import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.result;
import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.user;
import static in.vidyalai.claude.sdk.internal.PipeTransport.awaitUntil;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.Collector;
import in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.ScriptedStore;

/**
 * Release order of held segments when their flushes complete out of order.
 *
 * <p>
 * The batcher's drains are FIFO, so a later flush practically never
 * completes before an earlier one, but nothing forbids it (the completions
 * run on whichever thread finishes each drain). These tests use
 * {@code QueryHandler.setBarrierHookForTest} to put a test-controlled gate behind
 * each real flush, so the barriers can complete in any order. They use that
 * seam, so they do not compile against the code before the barrier.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class MirrorBarrierReleaseOrderTest {

    private static final long QUIET_MS = 250;

    private final List<QueryHandler> handlers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (QueryHandler handler : handlers) {
            handler.close();
        }
    }

    /** Gates the first {@code gated} barriers behind test-completed futures. */
    private static List<CompletableFuture<Void>> gateBarriers(QueryHandler handler, int gated) {
        List<CompletableFuture<Void>> gates = new CopyOnWriteArrayList<>();
        for (int i = 0; i < gated; i++) {
            gates.add(new CompletableFuture<>());
        }
        AtomicInteger calls = new AtomicInteger();
        handler.setBarrierHookForTest(flush -> {
            int call = calls.getAndIncrement();
            return (call < gated) ? flush.thenCompose(v -> gates.get(call)) : flush;
        });
        return gates;
    }

    private QueryHandler start(PipeTransport transport, ScriptedStore store, long sendTimeoutMs, boolean eager) {
        QueryHandler handler = MirrorBarrierFixtures.handler(transport, null, null, 1000);
        handlers.add(handler);
        attach(handler, store, sendTimeoutMs, eager);
        return handler;
    }

    private static void go(PipeTransport transport, QueryHandler handler) {
        transport.connect();
        handler.start();
    }

    @Test
    void aLaterBarrierCompletingFirstWaitsForTheEarlierOne() throws Exception {
        PipeTransport transport = new PipeTransport();
        ScriptedStore store = new ScriptedStore();
        QueryHandler handler = start(transport, store, 30_000, false);
        List<CompletableFuture<Void>> gates = gateBarriers(handler, 2);
        go(transport, handler);
        transport.push(mirror("s1", "ok-1"));
        transport.push(result("r1"));
        transport.push(user("x"));
        transport.push(mirror("s2", "ok-2"));
        transport.push(result("r2"));
        transport.push(user("y"));
        Collector consumer = new Collector(handler);
        awaitUntil("both results to be read", WAIT_SECONDS, () -> transport.taken() == 6);
        awaitUntil("both appends", WAIT_SECONDS, () -> store.appended.size() == 2);

        // The second barrier completes first: nothing may overtake r1.
        gates.get(1).complete(null);
        Thread.sleep(QUIET_MS);
        assertThat(consumer.seen).isEmpty();

        gates.get(0).complete(null);
        consumer.awaitSeen(4);
        assertThat(consumer.seen).containsExactly("r:r1", "u:x", "r:r2", "u:y");
        transport.end();
        assertThat(consumer.awaitEnd()).containsExactly("r:r1", "u:x", "r:r2", "u:y");
    }

    @Test
    void aMirrorErrorFromALaterDrainNeverOvertakesAHeldResult() throws Exception {
        PipeTransport transport = new PipeTransport();
        ScriptedStore store = new ScriptedStore();
        QueryHandler handler = MirrorBarrierFixtures.handler(transport, null, null, 1000);
        handlers.add(handler);
        CountDownLatch reported = new CountDownLatch(1);
        // EAGER, 200 ms append timeout; the report is routed by the handler as in
        // production, the latch only observes it.
        handler.setTranscriptMirrorBatcher(new TranscriptMirrorBatcher(store, MirrorBarrierFixtures.PROJECTS,
                (key, error, drain) -> {
                    handler.reportMirrorError(key, error, drain);
                    reported.countDown();
                }, 200, 0, 0, ForkJoinPool.commonPool()));
        List<CompletableFuture<Void>> gates = gateBarriers(handler, 1);
        go(transport, handler);
        transport.push(mirror("s1", "ok-1"));
        transport.push(result("r1")); // held by the gate
        transport.push(user("u2"));
        transport.push(mirror("s2", "hang-2")); // a later drain, which fails
        Collector consumer = new Collector(handler);

        // The error is reported while r1 is still held.
        assertThat(reported.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        awaitUntil("every frame to be read", WAIT_SECONDS, () -> transport.taken() == 4);
        Thread.sleep(QUIET_MS);
        assertThat(consumer.seen).isEmpty();

        gates.get(0).complete(null);
        consumer.awaitSeen(3);
        // Not ahead of r1, which a report arriving while it is held could
        // otherwise be mistaken for: it takes its place behind u2.
        assertThat(consumer.seen).containsExactly("r:r1", "u:u2", "me:s2");
    }

    @Test
    void aFlushsMirrorErrorWaitsBehindAnEarlierHeldSegmentAndPrecedesItsOwnResult() throws Exception {
        PipeTransport transport = new PipeTransport();
        ScriptedStore store = new ScriptedStore();
        QueryHandler handler = start(transport, store, 200, false);
        List<CompletableFuture<Void>> gates = gateBarriers(handler, 1);
        go(transport, handler);
        transport.push(mirror("s1", "ok-1"));
        transport.push(result("r1")); // held by the gate
        transport.push(user("x"));
        transport.push(mirror("s2", "hang-2")); // batched: sent by r2's flush, fails
        transport.push(result("r2"));
        transport.push(user("y"));
        Collector consumer = new Collector(handler);
        awaitUntil("both results to be read", WAIT_SECONDS, () -> transport.taken() == 6);
        awaitUntil("r2's append to time out", WAIT_SECONDS, () -> store.started.contains("hang-2"));
        Thread.sleep(QUIET_MS);
        assertThat(consumer.seen).isEmpty();

        gates.get(0).complete(null);
        consumer.awaitSeen(5);
        assertThat(consumer.seen).containsExactly("r:r1", "u:x", "me:s2", "r:r2", "u:y");
    }

    /**
     * A mirror error reported while the reader is parked on back-pressure,
     * with an older segment held, still lands right before its own result.
     */
    @Test
    void aMirrorErrorReportedWhileTheReaderIsParkedLandsBeforeItsOwnResult() throws Exception {
        PipeTransport transport = new PipeTransport();
        ScriptedStore store = new ScriptedStore();
        QueryHandler handler = MirrorBarrierFixtures.handler(transport, null, null, 4);
        handlers.add(handler);
        CountDownLatch reported = new CountDownLatch(1);
        handler.setTranscriptMirrorBatcher(new TranscriptMirrorBatcher(store, MirrorBarrierFixtures.PROJECTS,
                (key, error, drain) -> {
                    handler.reportMirrorError(key, error, drain);
                    reported.countDown();
                }, 200, 500, 1 << 20, ForkJoinPool.commonPool()));
        List<CompletableFuture<Void>> gates = gateBarriers(handler, 1);
        go(transport, handler);
        transport.push(mirror("s1", "ok-1"));
        transport.push(result("r1")); // held by the gate
        transport.push(user("x"));
        transport.push(mirror("s2", "hang-2")); // batched: r2's flush sends it, and it fails
        transport.push(result("r2"));
        transport.push(user("y1"));
        transport.push(user("y2"));
        transport.push(user("y3"));

        // r1, x, r2 and y1 fill the limit of 4; the reader holds y2 and waits.
        awaitUntil("the reader to park", WAIT_SECONDS,
                () -> (transport.taken() == 7) && waitingForRoom(transport.readerThread()));
        assertThat(reported.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        Collector consumer = new Collector(handler);
        Thread.sleep(QUIET_MS);
        assertThat(consumer.seen).isEmpty();

        gates.get(0).complete(null);
        consumer.awaitSeen(7);
        assertThat(consumer.seen).containsExactly("r:r1", "u:x", "me:s2", "r:r2", "u:y1", "u:y2", "u:y3");
    }

    /** Whether {@code reader} is parked in the queue's wait for room. */
    private static boolean waitingForRoom(@Nullable Thread reader) {
        if (reader == null) {
            return false;
        }
        Thread.State state = reader.getState();
        if ((state != Thread.State.TIMED_WAITING) && (state != Thread.State.WAITING)) {
            return false;
        }
        for (StackTraceElement frame : reader.getStackTrace()) {
            if ("enqueueBlocking".equals(frame.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Seeded: random gate delays complete the barriers in random order; the
     * stream must stay in stdout order with each error before its result.
     */
    @Test
    void randomBarrierCompletionOrderKeepsStdoutOrder() throws Exception {
        Random random = new Random(0x5EEDL);
        for (int iteration = 0; iteration < 40; iteration++) {
            PipeTransport transport = new PipeTransport();
            ScriptedStore store = new ScriptedStore();
            QueryHandler handler = start(transport, store, 200, false);
            long[] delays = new long[16];
            for (int i = 0; i < delays.length; i++) {
                delays[i] = random.nextInt(15);
            }
            AtomicInteger calls = new AtomicInteger();
            handler.setBarrierHookForTest(flush -> {
                long delay = delays[Math.min(calls.getAndIncrement(), delays.length - 1)];
                return flush.thenCompose(v -> CompletableFuture.runAsync(() -> {
                }, CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)));
            });
            go(transport, handler);
            List<String> expected = new ArrayList<>();
            int turns = 2 + random.nextInt(5);
            for (int t = 1; t <= turns; t++) {
                boolean failing = random.nextInt(6) == 0;
                transport.push(mirror("s" + t, (failing ? "hang-" : "ok-") + t));
                int extra = random.nextInt(3);
                for (int k = 0; k < extra; k++) {
                    transport.push(user("u" + t + "_" + k));
                    expected.add("u:u" + t + "_" + k);
                }
                if (failing) {
                    expected.add("me:s" + t);
                }
                transport.push(result("t" + t));
                expected.add("r:t" + t);
            }
            transport.end();
            try {
                assertThat(new Collector(handler).awaitEnd()).as("iteration %d", iteration)
                        .containsExactlyElementsOf(expected);
            } finally {
                handler.close();
            }
        }
    }

}
