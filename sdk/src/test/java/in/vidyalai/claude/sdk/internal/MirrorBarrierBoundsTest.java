package in.vidyalai.claude.sdk.internal;

import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.WAIT_SECONDS;
import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.attach;
import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.label;
import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.mirror;
import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.result;
import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.user;
import static in.vidyalai.claude.sdk.internal.PipeTransport.awaitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.Collector;
import in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.ScriptedStore;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookMatcher;
import in.vidyalai.claude.sdk.types.hook.output.HookOutput;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.session.SessionKey;

/**
 * Bounds around the transcript-mirror delivery barrier: the hard ceiling on
 * buffered messages, a report that outlives its segment's flush bound,
 * close() racing releases, the one-shot query path with hooks, and the
 * warning for a mirror error nobody can receive any more.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@SuppressWarnings("null")
class MirrorBarrierBoundsTest {

    private static final long GATED_SEND_TIMEOUT_MS = 30_000;
    private static final long QUIET_MS = 250;

    private final List<QueryHandler> handlers = new ArrayList<>();
    private final List<ScriptedStore> stores = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (ScriptedStore store : stores) {
            store.openAll();
        }
        for (QueryHandler handler : handlers) {
            handler.close();
        }
    }

    private QueryHandler handler(PipeTransport transport, int maxMsgQSize) {
        QueryHandler handler = MirrorBarrierFixtures.handler(transport, null, null, maxMsgQSize);
        handlers.add(handler);
        return handler;
    }

    private static void go(PipeTransport transport, QueryHandler handler) {
        transport.connect();
        handler.start();
    }

    /** Whether {@code reader} is parked in the queue's wait for room. */
    private static boolean waitingForRoom(Thread reader) {
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

    private static Thread awaitReader(PipeTransport transport) throws Exception {
        awaitUntil("the reader to start", WAIT_SECONDS, () -> transport.readerThread() != null);
        return transport.readerThread();
    }

    /** Sends an interrupt the CLI never answers, so it stays pending. */
    private static CompletableFuture<Void> pendingInterrupt(PipeTransport transport, QueryHandler handler)
            throws Exception {
        transport.replier = request -> null;
        CompletableFuture<Void> call = CompletableFuture.runAsync(() -> {
            try {
                handler.interrupt();
            } catch (RuntimeException expected) {
                // Times out, or fails on close().
            }
        });
        awaitUntil("the interrupt to be sent", WAIT_SECONDS, () -> !transport.requestsWritten("interrupt").isEmpty());
        return call;
    }

    // ------------------------------------------------------------------
    // (a) The hard ceiling
    // ------------------------------------------------------------------

    @Test
    void aHungStoreAndAPendingControlRequestStopTheReaderAtTheHardCeiling() throws Exception {
        int limit = 5;
        int ceiling = QueryHandler.HARD_LIMIT_FACTOR * limit;
        int buffered = 200;
        PipeTransport transport = new PipeTransport();
        ScriptedStore store = new ScriptedStore();
        stores.add(store);
        QueryHandler handler = handler(transport, limit);
        attach(handler, store, GATED_SEND_TIMEOUT_MS, false);
        go(transport, handler);
        Collector consumer = new Collector(handler); // draining, but everything is held
        CompletableFuture<Void> interrupt = pendingInterrupt(transport, handler);

        // Sampled while the result is held: once it is released nothing is
        // held, so there is no ceiling and the reader reads on.
        AtomicInteger maxBuffered = new AtomicInteger();
        AtomicBoolean released = new AtomicBoolean();
        CompletableFuture<Void> sampler = CompletableFuture.runAsync(() -> {
            while (!released.get()) {
                maxBuffered.accumulateAndGet(handler.bufferedCountForTest(), Math::max);
                LockSupport.parkNanos(50_000);
            }
        });
        transport.push(mirror("s1", "gate-1"));
        transport.push(result("r1"));
        for (int i = 0; i < buffered; i++) {
            transport.push(user("m" + i));
        }

        // The result and ceiling-1 more are held, one more is in hand: the
        // reader stops although a control request is pending.
        Thread reader = awaitReader(transport);
        awaitUntil("the reader to stop at the ceiling", WAIT_SECONDS,
                () -> (transport.taken() == 2 + ceiling) && waitingForRoom(reader));
        Thread.sleep(QUIET_MS);
        assertThat(transport.taken()).isEqualTo(2 + ceiling);
        assertThat(interrupt).isNotDone();

        released.set(true);
        sampler.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(maxBuffered.get()).isBetween(1, ceiling);
        store.release("gate-1");
        consumer.awaitSeen(1 + buffered);
        List<String> expected = new ArrayList<>();
        expected.add("r:r1");
        for (int i = 0; i < buffered; i++) {
            expected.add("u:m" + i);
        }
        assertThat(consumer.seen).containsExactlyElementsOf(expected);
    }

    /**
     * Regression guard: with nothing held the consumer can always drain, so
     * there is no ceiling, as in 0.2.6. A control call made while far more
     * than four times the limit is buffered still gets its response.
     */
    @Test
    void withNothingHeldAPendingControlRequestReadsPastTheHardCeiling() throws Exception {
        int limit = 3;
        int ceiling = QueryHandler.HARD_LIMIT_FACTOR * limit;
        int buffered = 100;
        PipeTransport transport = new PipeTransport();
        QueryHandler handler = handler(transport, limit);
        go(transport, handler);
        CompletableFuture<Void> interrupt = pendingInterrupt(transport, handler);
        for (int i = 0; i < buffered; i++) {
            transport.push(user("m" + i));
        }

        // Nobody drains, yet the reader reads everything.
        awaitUntil("the reader to read past the ceiling", WAIT_SECONDS,
                () -> handler.bufferedCountForTest() == buffered);
        assertThat(buffered).isGreaterThan(ceiling);
        for (Map<String, Object> frame : transport.writtenFrames()) {
            if ("control_request".equals(frame.get("type"))) {
                transport.push(PipeTransport.success(frame)); // answers the interrupt
            }
        }
        interrupt.get(WAIT_SECONDS, TimeUnit.SECONDS);

        Collector consumer = new Collector(handler);
        consumer.awaitSeen(buffered);
        for (int i = 0; i < buffered; i++) {
            assertThat(consumer.seen.get(i)).isEqualTo("u:m" + i);
        }
    }

    /**
     * The ceiling applies only while messages are held: once the flush
     * completes and the holdback empties, a reader parked at the ceiling reads
     * on although nobody drains, because a control request is pending.
     */
    @Test
    void theHardCeilingLiftsOnceTheHoldbackIsReleased() throws Exception {
        int limit = 3;
        int ceiling = QueryHandler.HARD_LIMIT_FACTOR * limit;
        int buffered = 100;
        PipeTransport transport = new PipeTransport();
        ScriptedStore store = new ScriptedStore();
        stores.add(store);
        QueryHandler handler = handler(transport, limit);
        attach(handler, store, GATED_SEND_TIMEOUT_MS, false);
        go(transport, handler);
        pendingInterrupt(transport, handler);
        transport.push(mirror("s1", "gate-1"));
        transport.push(result("r1"));
        for (int i = 0; i < buffered; i++) {
            transport.push(user("m" + i));
        }

        Thread reader = awaitReader(transport);
        awaitUntil("the reader to stop at the ceiling", WAIT_SECONDS,
                () -> (transport.taken() == 2 + ceiling) && waitingForRoom(reader));

        // Nobody drains; releasing the hold alone lets the reader read on.
        store.release("gate-1");
        awaitUntil("the reader to read everything", WAIT_SECONDS,
                () -> handler.bufferedCountForTest() == 1 + buffered);

        Collector consumer = new Collector(handler);
        consumer.awaitSeen(1 + buffered);
        assertThat(consumer.seen.get(0)).isEqualTo("r:r1");
        for (int i = 0; i < buffered; i++) {
            assertThat(consumer.seen.get(1 + i)).isEqualTo("u:m" + i);
        }
    }

    // ------------------------------------------------------------------
    // (c) A report outliving its segment's flush bound
    // ------------------------------------------------------------------

    /**
     * Regression guard for the drain-number routing: the segment of r1 is
     * released at its flush bound while its drain still runs; that drain's
     * late reports go to the next segment, whose flush covers the same drain,
     * so they arrive before r2.
     */
    @Test
    void aLateReportFromASegmentReleasedAtTheBoundPrecedesTheNextResult() throws Exception {
        long sendTimeoutMs = 400; // bound 3 x 400 + 1000 = 2.2 s
        int paths = 7; // 7 x 400 ms of timed-out appends: 2.8 s
        PipeTransport transport = new PipeTransport();
        ScriptedStore store = new ScriptedStore();
        QueryHandler handler = handler(transport, 1000);
        attach(handler, store, sendTimeoutMs, false);
        go(transport, handler);
        for (int i = 1; i <= paths; i++) {
            transport.push(mirror("s" + i, "hang-" + i));
        }
        transport.push(result("r1"));
        transport.push(user("u1"));
        // Once r1 is out (at the bound, before the drain reports), the next
        // turn arrives.
        Collector consumer = new Collector(handler, message -> {
            if ("r:r1".equals(label(message))) {
                transport.push(user("u2"));
                transport.push(result("r2"));
                transport.end();
            }
        });

        List<String> expected = new ArrayList<>(List.of("r:r1", "u:u1", "u:u2"));
        for (int i = 1; i <= paths; i++) {
            expected.add("me:s" + i);
        }
        expected.add("r:r2");
        assertThat(consumer.awaitEnd()).containsExactlyElementsOf(expected);
    }

    // ------------------------------------------------------------------
    // (d) close() racing releases
    // ------------------------------------------------------------------

    @Test
    void closeRacingReleasesNeverDeliversAfterTheEndAndStaysBounded() throws Exception {
        Random random = new Random(0xC105EL);
        for (int iteration = 0; iteration < 150; iteration++) {
            PipeTransport transport = new PipeTransport();
            ScriptedStore store = new ScriptedStore();
            QueryHandler handler = MirrorBarrierFixtures.handler(transport, null, null, 1000);
            attach(handler, store, 2_000, random.nextBoolean());
            go(transport, handler);
            List<String> expected = new ArrayList<>();
            int turns = 1 + random.nextInt(6);
            int frames = 0;
            for (int t = 1; t <= turns; t++) {
                transport.push(mirror("s" + t, "delay" + random.nextInt(4) + "-" + t));
                transport.push(result("t" + t));
                transport.push(user("u" + t));
                expected.add("r:t" + t);
                expected.add("u:u" + t);
                frames += 3;
            }
            List<String> afterEnd = new CopyOnWriteArrayList<>();
            CompletableFuture<Void> consumed = new CompletableFuture<>();
            List<String> seen = new CopyOnWriteArrayList<>();
            Thread consumerThread = new Thread(() -> {
                try {
                    var messages = handler.receiveMessages();
                    while (messages.hasNext()) {
                        seen.add(label(messages.next()));
                    }
                    // The end is final: nothing turns up afterwards, on this
                    // iterator or a new one.
                    for (int i = 0; i < 3; i++) {
                        if (messages.hasNext() || handler.receiveMessages().hasNext()) {
                            afterEnd.add("delivered after the end");
                        }
                        Thread.sleep(2);
                    }
                    consumed.complete(null);
                } catch (Throwable e) {
                    consumed.completeExceptionally(e);
                }
            });
            consumerThread.start();
            int closeAt = random.nextInt(frames + 1);
            awaitUntil("frame " + closeAt, WAIT_SECONDS, () -> transport.taken() >= closeAt);
            assertTimeoutPreemptively(Duration.ofSeconds(10), handler::close);

            consumed.get(WAIT_SECONDS, TimeUnit.SECONDS);
            String context = "iteration " + iteration + ": " + seen;
            assertThat(afterEnd).as(context).isEmpty();
            // What was delivered is a prefix of the stream: no loss inside it,
            // no duplicate, no reordering.
            assertThat(seen.size()).as(context).isLessThanOrEqualTo(expected.size());
            assertThat(seen).as(context).containsExactlyElementsOf(expected.subList(0, seen.size()));
        }
    }

    // ------------------------------------------------------------------
    // (e) One-shot query with hooks
    // ------------------------------------------------------------------

    /**
     * Regression guard: with hooks the run is bidirectional, so stdin closes
     * at the result while its flush may still run; the store still has every
     * entry when query() returns, and the mirror error precedes the result.
     */
    @Test
    void oneShotQueryWithHooksKeepsEveryMirrorFrameAndError() throws Exception {
        Path configDir = Files.createTempDirectory("barrier-hooks");
        ScriptedStore store = new ScriptedStore();
        stores.add(store);
        PipeTransport transport = new PipeTransport();
        HookMatcher.HookCallback hook = (input, context) -> CompletableFuture.completedFuture(HookOutput.empty());
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sessionStore(store)
                .hooks(Map.of(HookEvent.PRE_TOOL_USE, List.of(new HookMatcher(null, List.of(hook)))))
                .env(Map.of("CLAUDE_CONFIG_DIR", configDir.toString()))
                .build();
        Path projects = configDir.resolve("projects").resolve("p");
        CompletableFuture<List<Message>> query = CompletableFuture.supplyAsync(
                () -> ClaudeSDK.query("hi", options, transport));
        awaitUntil("initialize", WAIT_SECONDS, () -> !transport.requestsWritten("initialize").isEmpty());
        transport.push(entryFrame(projects.resolve("s.jsonl"), "delay3-ok-1"));
        transport.push(entryFrame(projects.resolve("f.jsonl"), "fail-1"));
        transport.push(user("u1"));
        transport.push(result("r1"));
        // Bidirectional: stdin closes at the result, then the CLI writes its
        // last mirror frame and exits.
        awaitUntil("stdin to close", WAIT_SECONDS, () -> transport.endInputCalls() > 0);
        transport.push(entryFrame(projects.resolve("s.jsonl"), "delay3-ok-after"));
        transport.end();

        List<Message> messages = query.get(WAIT_SECONDS, TimeUnit.SECONDS);
        List<String> appendedAtReturn = List.copyOf(store.appended);
        List<String> labels = new ArrayList<>();
        for (Message message : messages) {
            labels.add(label(message));
        }
        assertThat(labels).containsExactly("u:u1", "me:f", "r:r1");
        assertThat(appendedAtReturn).containsExactly("delay3-ok-1", "delay3-ok-after");
    }

    private static Map<String, Object> entryFrame(Path file, String uuid) {
        return Map.of("type", "transcript_mirror", "filePath", file.toString(),
                "entries", List.of(Map.of("type", "user", "uuid", uuid)));
    }

    // ------------------------------------------------------------------
    // (f) A mirror error nobody can receive is logged
    // ------------------------------------------------------------------

    @Test
    void aMirrorErrorAfterTheEndOrCloseIsDroppedWithAWarning() throws Exception {
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(QueryHandler.class.getName());
        logger.addHandler(capture);
        try {
            PipeTransport transport = new PipeTransport();
            QueryHandler handler = handler(transport, 1000);
            attach(handler, new ScriptedStore(), GATED_SEND_TIMEOUT_MS, false);
            go(transport, handler);
            transport.push(result("r1"));
            transport.end();
            assertThat(new Collector(handler).awaitEnd()).containsExactly("r:r1");

            handler.reportMirrorError(new SessionKey("p", "after-end", null), "late boom", 1);
            handler.close();
            handler.reportMirrorError(new SessionKey("p", "after-close", null), "later boom", 2);

            List<String> warnings = new ArrayList<>();
            for (LogRecord record : records) {
                if (record.getLevel() == Level.WARNING && record.getMessage().contains("mirror_error")) {
                    warnings.add(record.getMessage());
                }
            }
            assertThat(warnings).hasSize(2);
            assertThat(warnings.get(0)).contains("after-end").contains("late boom");
            assertThat(warnings.get(1)).contains("after-close").contains("later boom");
            // Dropped, not delivered.
            assertThat(handler.receiveMessages().hasNext()).isFalse();
        } finally {
            logger.removeHandler(capture);
        }
    }

}
