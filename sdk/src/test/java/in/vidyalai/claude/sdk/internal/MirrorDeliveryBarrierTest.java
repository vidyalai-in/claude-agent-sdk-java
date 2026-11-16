package in.vidyalai.claude.sdk.internal;

import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.WAIT_SECONDS;
import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.assistant;
import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.attach;
import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.canUseTool;
import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.cancel;
import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.hookCallback;
import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.label;
import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.mirror;
import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.result;
import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.streamEvent;
import static in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.user;
import static in.vidyalai.claude.sdk.internal.PipeTransport.awaitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.exceptions.ClaudeSDKException;
import in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.Collector;
import in.vidyalai.claude.sdk.internal.MirrorBarrierFixtures.ScriptedStore;
import in.vidyalai.claude.sdk.types.hook.output.HookOutput;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.permission.PermissionResult;
import in.vidyalai.claude.sdk.types.permission.PermissionResultAllow;

/**
 * The transcript-mirror delivery barrier: at a {@code result} the reader
 * starts the mirror flush and holds the result, and the regular messages
 * after it, until the flush completes, while it goes on reading control
 * responses, the CLI's control requests and further mirror frames.
 *
 * <p>
 * Each test waits on an observable effect (frames taken from the transport,
 * a store call, a written response); the only fixed waits are short negative
 * checks that something has <i>not</i> happened yet. Tests marked
 * "regression guard" pin a guarantee the blocking reader already gave; every
 * other test fails against it, because that reader read nothing past a
 * result until the store had the turn.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@SuppressWarnings("null")
class MirrorDeliveryBarrierTest {

    /** Long enough that a gated append never times out while a test holds it. */
    private static final long GATED_SEND_TIMEOUT_MS = 30_000;
    /** How long a negative check waits for something that must not happen. */
    private static final long QUIET_MS = 250;
    /** Far above the stress store's 0-4 ms delays, so only hung appends time out. */
    private static final long STRESS_SEND_TIMEOUT_MS = 150;

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

    private ScriptedStore store() {
        ScriptedStore store = new ScriptedStore();
        stores.add(store);
        return store;
    }

    private QueryHandler start(PipeTransport transport, ScriptedStore store, long sendTimeoutMs, boolean eager) {
        return start(transport, store, sendTimeoutMs, eager, null, 1000);
    }

    private QueryHandler start(PipeTransport transport, @Nullable ScriptedStore store, long sendTimeoutMs,
            boolean eager, ClaudeAgentOptions.@Nullable CanUseTool canUseTool, int maxMsgQSize) {
        QueryHandler handler = MirrorBarrierFixtures.handler(transport, canUseTool, null, maxMsgQSize);
        handlers.add(handler);
        if (store != null) {
            attach(handler, store, sendTimeoutMs, eager);
        }
        transport.connect();
        handler.start();
        return handler;
    }

    private static void quietly() throws InterruptedException {
        Thread.sleep(QUIET_MS);
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

    // ------------------------------------------------------------------
    // 1. Outbound control calls while the pre-result flush is blocked
    // ------------------------------------------------------------------

    @Test
    void interruptSetModelAndGetMcpStatusAreAnsweredWhileTheStoreIsBlocked() throws Exception {
        PipeTransport transport = new PipeTransport();
        transport.replier = request -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) request.get("request");
            return "mcp_status".equals(body.get("subtype"))
                    ? PipeTransport.successWith(request, Map.of("mcpServers", List.of()))
                    : PipeTransport.success(request);
        };
        ScriptedStore store = store();
        QueryHandler handler = start(transport, store, GATED_SEND_TIMEOUT_MS, false);
        transport.push(mirror("s1", "gate-1"));
        transport.push(result("r1"));
        Collector consumer = new Collector(handler);
        awaitUntil("the result's append to start", WAIT_SECONDS, () -> store.started.contains("gate-1"));

        // Each used to wait out its 60 s timeout behind the flush.
        assertTimeoutPreemptively(Duration.ofSeconds(5), handler::interrupt);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> handler.setModel("claude-x"));
        assertThat(assertTimeoutPreemptively(Duration.ofSeconds(5), handler::getMcpStatus)).isNotNull();
        assertThat(transport.requestsWritten("interrupt")).hasSize(1);
        assertThat(transport.requestsWritten("set_model")).hasSize(1);
        assertThat(transport.requestsWritten("mcp_status")).hasSize(1);

        // The result is still held for the store.
        assertThat(consumer.seen).isEmpty();
        store.release("gate-1");
        consumer.awaitSeen(1);
        assertThat(consumer.seen).containsExactly("r:r1");
    }

    // ------------------------------------------------------------------
    // 2. The result waits for the store
    // ------------------------------------------------------------------

    @Test
    void theResultIsWithheldUntilTheStoreHasTheTurnWhileTheReaderReadsOn() throws Exception {
        PipeTransport transport = new PipeTransport();
        ScriptedStore store = store();
        QueryHandler handler = start(transport, store, GATED_SEND_TIMEOUT_MS, false);
        AtomicReference<List<String>> storedAtResult = new AtomicReference<>();
        Collector consumer = new Collector(handler, message -> {
            if (message instanceof ResultMessage) {
                storedAtResult.set(List.copyOf(store.appended));
            }
        });
        transport.push(user("u0"));
        transport.push(mirror("s1", "gate-1"));
        transport.push(result("r1"));
        transport.push(user("u2"));

        awaitUntil("the result's append to start", WAIT_SECONDS, () -> store.started.contains("gate-1"));
        // The reader read past the result while the store is blocked.
        awaitUntil("the reader to read past the result", WAIT_SECONDS, () -> transport.taken() == 4);
        consumer.awaitSeen(1);
        quietly();
        assertThat(consumer.seen).containsExactly("u:u0");
        assertThat(store.appended).isEmpty();

        store.release("gate-1");
        consumer.awaitSeen(3);
        assertThat(consumer.seen).containsExactly("u:u0", "r:r1", "u:u2");
        // The store had the turn when the result reached the consumer.
        assertThat(storedAtResult.get()).containsExactly("gate-1");
    }

    // ------------------------------------------------------------------
    // 3. Order after the result
    // ------------------------------------------------------------------

    @Test
    void messagesAfterAHeldResultFollowItInStdoutOrder() throws Exception {
        PipeTransport transport = new PipeTransport();
        ScriptedStore store = store();
        QueryHandler handler = start(transport, store, GATED_SEND_TIMEOUT_MS, false);
        transport.push(mirror("s1", "gate-1"));
        transport.push(result("r1"));
        transport.push(streamEvent("se1"));
        transport.push(streamEvent("se2"));
        transport.push(assistant("a2"));
        transport.push(user("u3"));
        transport.push(mirror("s2", "ok-2"));
        transport.push(result("r2"));
        transport.end();
        Collector consumer = new Collector(handler);

        awaitUntil("every frame to be read", WAIT_SECONDS, transport::readEnded);
        assertThat(transport.taken()).isEqualTo(8);
        quietly();
        assertThat(consumer.seen).isEmpty();
        assertThat(consumer.finished).isNotDone();

        store.release("gate-1");
        assertThat(consumer.awaitEnd())
                .containsExactly("r:r1", "se:se1", "se:se2", "a:a2", "u:u3", "r:r2");
        assertThat(store.appended).containsExactly("gate-1", "ok-2");
    }

    // ------------------------------------------------------------------
    // 4. Mirror errors from the turn's own flush (regression guard)
    // ------------------------------------------------------------------

    /**
     * Regression guard: the blocking reader gave the same order. Pinned here
     * because the barrier now routes each report by its drain number.
     */
    @ParameterizedTest(name = "eager={0}")
    @ValueSource(booleans = { false, true })
    void mirrorErrorsOfTheTurnsOwnFlushAlwaysPrecedeItsResult(boolean eager) throws Exception {
        for (int run = 0; run < 40; run++) {
            PipeTransport transport = new PipeTransport();
            ScriptedStore store = store();
            QueryHandler handler = start(transport, store, 20, eager);
            try {
                transport.push(user("u0"));
                transport.push(mirror("s1", "hang-1-" + run));
                transport.push(mirror("s2", "hang-2-" + run));
                transport.push(user("u1"));
                transport.push(result("r1"));
                transport.push(user("u2"));
                transport.end();
                List<String> seen = new Collector(handler).awaitEnd();
                if (eager) {
                    // Each frame's own drain reports while the reader reads on,
                    // so its error may come before or after u1, but always
                    // after u0, in drain order and ahead of the result.
                    List<String> regular = new ArrayList<>(seen);
                    regular.removeIf(l -> l.startsWith("me:"));
                    assertThat(regular).as("run %d", run).containsExactly("u:u0", "u:u1", "r:r1", "u:u2");
                    assertThat(seen.indexOf("me:s1")).as("run %d: %s", run, seen)
                            .isGreaterThan(seen.indexOf("u:u0"))
                            .isLessThan(seen.indexOf("me:s2"));
                    assertThat(seen.indexOf("me:s2")).as("run %d: %s", run, seen)
                            .isLessThan(seen.indexOf("r:r1"));
                } else {
                    assertThat(seen).as("run %d", run)
                            .containsExactly("u:u0", "u:u1", "me:s1", "me:s2", "r:r1", "u:u2");
                }
            } finally {
                handler.close();
            }
        }
    }

    // ------------------------------------------------------------------
    // 5. Mirror errors from later drains
    // ------------------------------------------------------------------

    @Test
    void mirrorErrorsFromLaterDrainsFollowTheHeldResult() throws Exception {
        PipeTransport transport = new PipeTransport();
        ScriptedStore store = store();
        QueryHandler handler = start(transport, store, GATED_SEND_TIMEOUT_MS, true);
        transport.push(mirror("s1", "gate-1")); // eager drain 1: blocks
        transport.push(result("r1")); // held behind drain 1
        transport.push(mirror("s2", "fail-2")); // eager drain 2: fails, after r1's flush
        transport.push(user("u2"));
        Collector consumer = new Collector(handler);

        awaitUntil("the reader to read past the held result", WAIT_SECONDS, () -> transport.taken() == 4);
        store.release("gate-1");
        consumer.awaitSeen(3);
        transport.end();
        assertThat(consumer.awaitEnd()).containsExactly("r:r1", "u:u2", "me:s2");
    }

    // ------------------------------------------------------------------
    // 6. Two results whose store calls are released in reverse order
    // ------------------------------------------------------------------

    @Test
    void twoResultsAreDeliveredInOrderWhenTheSecondStoreCallIsReleasedFirst() throws Exception {
        PipeTransport transport = new PipeTransport();
        ScriptedStore store = store();
        QueryHandler handler = start(transport, store, GATED_SEND_TIMEOUT_MS, false);
        transport.push(mirror("s1", "gate-1"));
        transport.push(result("r1"));
        transport.push(user("x"));
        transport.push(mirror("s2", "gate-2"));
        transport.push(result("r2"));
        transport.push(user("y"));
        Collector consumer = new Collector(handler);

        awaitUntil("both results to be read", WAIT_SECONDS, () -> transport.taken() == 6);
        store.release("gate-2");
        quietly();
        assertThat(consumer.seen).isEmpty();

        store.release("gate-1");
        consumer.awaitSeen(4);
        assertThat(consumer.seen).containsExactly("r:r1", "u:x", "r:r2", "u:y");
        assertThat(store.appended).containsExactly("gate-1", "gate-2");
    }

    // ------------------------------------------------------------------
    // 7. Inbound requests during a hold
    // ------------------------------------------------------------------

    @Test
    void canUseToolHookCallbackAndCancelAreServedDuringAHold() throws Exception {
        CompletableFuture<PermissionResult> slow = new CompletableFuture<>();
        AtomicInteger permissionCalls = new AtomicInteger();
        ClaudeAgentOptions.CanUseTool canUseTool = (tool, input, context) -> {
            permissionCalls.incrementAndGet();
            return "Slow".equals(tool) ? slow
                    : CompletableFuture.<PermissionResult>completedFuture(new PermissionResultAllow());
        };
        PipeTransport transport = new PipeTransport();
        ScriptedStore store = store();
        QueryHandler handler = MirrorBarrierFixtures.handler(transport, canUseTool,
                (input, context) -> CompletableFuture.completedFuture(HookOutput.empty()), 1000);
        handlers.add(handler);
        attach(handler, store, GATED_SEND_TIMEOUT_MS, false);
        transport.connect();
        handler.start();
        handler.initialize(); // registers the hook as hook_0

        transport.push(mirror("s1", "gate-1"));
        transport.push(result("r1"));
        transport.push(canUseTool("perm_1"));
        transport.push(hookCallback("hook_1"));
        Map<String, Object> slowRequest = new HashMap<>(canUseTool("perm_2"));
        slowRequest.put("request", Map.of("subtype", "can_use_tool", "tool_name", "Slow", "input", Map.of()));
        transport.push(slowRequest);
        Collector consumer = new Collector(handler);

        awaitUntil("a response to perm_1", WAIT_SECONDS, () -> !transport.responsesTo("perm_1").isEmpty());
        awaitUntil("a response to hook_1", WAIT_SECONDS, () -> !transport.responsesTo("hook_1").isEmpty());
        awaitUntil("the slow permission callback", WAIT_SECONDS, () -> permissionCalls.get() == 2);
        transport.push(cancel("perm_2"));
        awaitUntil("the slow callback to be cancelled", WAIT_SECONDS, slow::isCancelled);
        awaitUntil("no request in flight", WAIT_SECONDS, () -> handler.inflightControlRequestCount() == 0);
        assertThat(transport.responsesTo("perm_1").get(0).get("subtype")).isEqualTo("success");
        assertThat(transport.responsesTo("hook_1").get(0).get("subtype")).isEqualTo("success");
        assertThat(transport.responsesTo("perm_2")).isEmpty();

        // All of it while the result was held.
        assertThat(consumer.seen).isEmpty();
        store.release("gate-1");
        consumer.awaitSeen(1);
        assertThat(consumer.seen).containsExactly("r:r1");
    }

    // ------------------------------------------------------------------
    // 8. The soft limit counts held messages
    // ------------------------------------------------------------------

    @Test
    void heldMessagesCountTowardTheSoftLimitButAPendingControlRequestReadsOn() throws Exception {
        int limit = 5;
        int buffered = 15; // with the result, below the hard ceiling of 4 x limit
        PipeTransport transport = new PipeTransport();
        ScriptedStore store = store();
        QueryHandler handler = start(transport, store, GATED_SEND_TIMEOUT_MS, false, null, limit);
        transport.push(mirror("s1", "gate-1"));
        transport.push(result("r1"));
        for (int i = 0; i < buffered; i++) {
            transport.push(user("m" + i));
        }

        // A hung store: the result and four more are held, the reader has one
        // more in hand and waits for room. Memory is bounded.
        Thread reader = awaitReader(transport);
        awaitUntil("the reader to wait for room", WAIT_SECONDS,
                () -> (transport.taken() == 2 + limit) && waitingForRoom(reader));
        quietly();
        assertThat(transport.taken()).isEqualTo(2 + limit);

        // A pending outbound request lets it read on, so the response arrives.
        assertTimeoutPreemptively(Duration.ofSeconds(5), handler::interrupt);
        assertThat(transport.taken()).isEqualTo(2 + buffered + 1);

        // After the release everything arrives, in order.
        Collector consumer = new Collector(handler);
        store.release("gate-1");
        consumer.awaitSeen(1 + buffered);
        List<String> expected = new ArrayList<>();
        expected.add("r:r1");
        for (int i = 0; i < buffered; i++) {
            expected.add("u:m" + i);
        }
        assertThat(consumer.seen).containsExactlyElementsOf(expected);
    }

    private static Thread awaitReader(PipeTransport transport) throws Exception {
        awaitUntil("the reader to start", WAIT_SECONDS, () -> transport.readerThread() != null);
        return transport.readerThread();
    }

    // ------------------------------------------------------------------
    // 9. End of stream and reader errors during a hold
    // ------------------------------------------------------------------

    @Test
    void eofDuringAHoldDeliversTheHeldMessagesAndThenTheEnd() throws Exception {
        PipeTransport transport = new PipeTransport();
        ScriptedStore store = store();
        QueryHandler handler = start(transport, store, GATED_SEND_TIMEOUT_MS, false);
        transport.push(mirror("s1", "gate-1"));
        transport.push(result("r1"));
        transport.push(user("u1"));
        transport.end();
        Collector consumer = new Collector(handler);

        awaitUntil("EOF to be read", WAIT_SECONDS, transport::readEnded);
        quietly();
        assertThat(consumer.seen).isEmpty();
        assertThat(consumer.finished).isNotDone();

        store.release("gate-1");
        assertThat(consumer.awaitEnd()).containsExactly("r:r1", "u:u1");
    }

    @Test
    void aTransportErrorDuringAHoldDeliversTheHeldMessagesAndThenTheError() throws Exception {
        PipeTransport transport = new PipeTransport();
        ScriptedStore store = store();
        QueryHandler handler = start(transport, store, GATED_SEND_TIMEOUT_MS, false);
        transport.push(mirror("s1", "gate-1"));
        transport.push(result("r1"));
        transport.push(user("u1"));
        transport.fail(new CLIConnectionException("boom"));
        Collector consumer = new Collector(handler);

        awaitUntil("the read to fail", WAIT_SECONDS, transport::readEnded);
        quietly();
        assertThat(consumer.seen).isEmpty();
        assertThat(consumer.finished).isNotDone();

        store.release("gate-1");
        assertThatThrownBy(() -> consumer.finished.get(WAIT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(CLIConnectionException.class).hasMessage("boom");
        assertThat(consumer.seen).containsExactly("r:r1", "u:u1");
    }

    @ParameterizedTest(name = "readError={0}")
    @ValueSource(booleans = { false, true })
    void theFinalFlushsMirrorErrorsComeAfterTheHeldMessagesAndBeforeTheEnd(boolean readError) throws Exception {
        PipeTransport transport = new PipeTransport();
        ScriptedStore store = store();
        QueryHandler handler = start(transport, store, GATED_SEND_TIMEOUT_MS, false);
        transport.push(mirror("s1", "gate-1"));
        transport.push(result("r1"));
        transport.push(mirror("s2", "fail-2")); // batched: only the final flush sends it
        transport.push(user("u1"));
        if (readError) {
            transport.fail(new CLIConnectionException("boom"));
        } else {
            transport.end();
        }
        Collector consumer = new Collector(handler);

        awaitUntil("the end of stdout", WAIT_SECONDS, transport::readEnded);
        store.release("gate-1");
        if (readError) {
            assertThatThrownBy(() -> consumer.finished.get(WAIT_SECONDS, TimeUnit.SECONDS))
                    .cause().isInstanceOf(CLIConnectionException.class);
        } else {
            consumer.awaitEnd();
        }
        assertThat(consumer.seen).containsExactly("r:r1", "u:u1", "me:s2");
    }

    // ------------------------------------------------------------------
    // 10. close() during a hold
    // ------------------------------------------------------------------

    @Test
    void closeDuringAHoldIsBoundedEndsTheStreamAndDeliversNothingAfter() throws Exception {
        PipeTransport transport = new PipeTransport();
        ScriptedStore store = store();
        // A short per-append timeout bounds close()'s final flush.
        QueryHandler handler = start(transport, store, 300, false);
        transport.push(mirror("s1", "gate-1"));
        transport.push(result("r1"));
        transport.push(user("u1"));
        Collector consumer = new Collector(handler);
        awaitUntil("the reader to read past the held result", WAIT_SECONDS, () -> transport.taken() == 3);

        long t0 = System.nanoTime();
        assertTimeoutPreemptively(Duration.ofSeconds(10), handler::close);
        long closeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        // The consumer sees the end of the stream; the held messages were dropped.
        assertThat(consumer.awaitEnd()).isEmpty();

        // The store finishing afterwards releases nothing.
        store.release("gate-1");
        quietly();
        assertThat(consumer.seen).isEmpty();
        assertThat(handler.receiveMessages().hasNext()).isFalse();
        assertThat(closeMs).isLessThan(TimeUnit.SECONDS.toMillis(8));
    }

    // ------------------------------------------------------------------
    // 11. Store failures
    // ------------------------------------------------------------------

    @Test
    void aFailingAppendReleasesTheResultBehindItsMirrorError() throws Exception {
        PipeTransport transport = new PipeTransport();
        ScriptedStore store = store();
        QueryHandler handler = start(transport, store, GATED_SEND_TIMEOUT_MS, false);
        transport.push(user("u0"));
        transport.push(mirror("s1", "fail-1"));
        transport.push(result("r1"));
        transport.push(user("u2"));
        Collector consumer = new Collector(handler);

        // The retries take about a second; the reader does not wait for them.
        awaitUntil("the reader to read past the result", WAIT_SECONDS, () -> transport.taken() == 4);
        assertThat(consumer.seen).doesNotContain("r:r1");
        consumer.awaitSeen(4);
        assertThat(consumer.seen).containsExactly("u:u0", "me:s1", "r:r1", "u:u2");
        assertThat(store.started).containsExactly("fail-1", "fail-1", "fail-1");
    }

    @Test
    void aStoreHungPastTheFlushBoundReleasesTheResultAtTheBound() throws Exception {
        long sendTimeoutMs = 100;
        int paths = 15; // 15 x 100 ms of timed-out appends: past the 1.3 s bound
        PipeTransport transport = new PipeTransport();
        ScriptedStore store = store();
        QueryHandler handler = start(transport, store, sendTimeoutMs, false);
        long boundMs = new TranscriptMirrorBatcher(store, "/x", (k, e, d) -> {
        }, sendTimeoutMs, 0, 0, Runnable::run).flushTimeoutMs();
        assertThat(boundMs).isEqualTo(1300);
        for (int i = 1; i <= paths; i++) {
            transport.push(mirror("s" + i, "hang-" + i));
        }
        AtomicReference<Long> deliveredAt = new AtomicReference<>();
        Collector consumer = new Collector(handler, message -> {
            if (message instanceof ResultMessage) {
                deliveredAt.set(System.nanoTime());
            }
        });
        transport.push(result("r1"));
        awaitUntil("the result to be read", WAIT_SECONDS, () -> transport.taken() == paths + 1);
        long readAt = System.nanoTime();
        transport.push(user("u2"));
        awaitUntil("the reader to read on", WAIT_SECONDS, () -> transport.taken() == paths + 2);
        // Well inside the bound: the reader did not wait for the flush.
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - readAt)).isLessThan(boundMs / 2);
        transport.end();

        List<String> seen = consumer.awaitEnd();
        long heldMs = TimeUnit.NANOSECONDS.toMillis(deliveredAt.get() - readAt);
        assertThat(heldMs).isBetween(boundMs - 200, boundMs + 3_000);
        // The result went out at the bound. The drain reports its dropped
        // batches once all its appends are done, after the bound, so they
        // follow the result (and what was held with it) and, through the
        // final flush, still come before the end.
        List<String> expected = new ArrayList<>(List.of("r:r1", "u:u2"));
        for (int i = 1; i <= paths; i++) {
            expected.add("me:s" + i);
        }
        assertThat(seen).containsExactlyElementsOf(expected);
    }

    // ------------------------------------------------------------------
    // 12. No store configured (regression guard)
    // ------------------------------------------------------------------

    /** Regression guard: without a batcher nothing is held. */
    @Test
    void withoutAStoreNothingIsHeld() throws Exception {
        PipeTransport transport = new PipeTransport();
        QueryHandler handler = start(transport, null, 0, false, null, 1000);
        transport.push(user("u1"));
        transport.push(mirror("s1", "gate-1")); // dropped: nobody mirrors
        transport.push(result("r1"));
        transport.push(user("u2"));
        Collector consumer = new Collector(handler);
        consumer.awaitSeen(3);
        assertThat(consumer.seen).containsExactly("u:u1", "r:r1", "u:u2");
        transport.end();
        assertThat(consumer.awaitEnd()).containsExactly("u:u1", "r:r1", "u:u2");
    }

    // ------------------------------------------------------------------
    // 13. Consumer interrupt during a hold (regression guard)
    // ------------------------------------------------------------------

    /**
     * Regression guard: an interrupted consumer throws, as before, and the
     * iterator stays usable for the held result.
     */
    @Test
    void anInterruptedConsumerThrowsDuringAHoldAndCanReadOnAfter() throws Exception {
        PipeTransport transport = new PipeTransport();
        ScriptedStore store = store();
        QueryHandler handler = start(transport, store, GATED_SEND_TIMEOUT_MS, false);
        transport.push(mirror("s1", "gate-1"));
        transport.push(result("r1"));
        awaitUntil("the result's append to start", WAIT_SECONDS, () -> store.started.contains("gate-1"));

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<String> after = new AtomicReference<>();
        CompletableFuture<Void> released = new CompletableFuture<>();
        Thread consumer = new Thread(() -> {
            Iterator<Message> messages = handler.receiveMessages();
            try {
                messages.hasNext();
            } catch (ClaudeSDKException e) {
                thrown.set(e);
            }
            Thread.interrupted();
            released.join();
            if (messages.hasNext()) {
                after.set(label(messages.next()));
            }
        }, "interrupted-consumer");
        consumer.start();
        awaitUntil("the consumer to wait", WAIT_SECONDS,
                () -> consumer.getState() == Thread.State.TIMED_WAITING);
        consumer.interrupt();
        awaitUntil("the consumer to throw", WAIT_SECONDS, () -> thrown.get() != null);
        assertThat(thrown.get()).hasMessage("Interrupted while waiting for the next message")
                .hasCauseInstanceOf(InterruptedException.class);

        store.release("gate-1");
        released.complete(null);
        consumer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertThat(after.get()).isEqualTo("r:r1");
    }

    // ------------------------------------------------------------------
    // 14. Stress
    // ------------------------------------------------------------------

    /**
     * Many turns with random store delays, random failures and random frame
     * interleaving, from a fixed seed: the delivered order is stdout order
     * minus control and mirror frames (plus the mirror errors, each ahead of
     * its own result), and each result is delivered only once its turn's
     * appends returned. Fails against the blocking reader, which never read
     * past a result while that turn's append ran.
     */
    @ParameterizedTest(name = "eager={0}")
    @ValueSource(booleans = { false, true })
    void stressOrderAndStoreBarrierOverSeededRandomRuns(boolean eager) throws Exception {
        Random random = new Random(eager ? 0xE6E5L : 0xBA7CL);
        AtomicInteger readPastHeldResult = new AtomicInteger();
        for (int iteration = 0; iteration < 80; iteration++) {
            runStressIteration(random, eager, iteration, readPastHeldResult);
        }
        // The reader went on reading while a result waited for its store.
        assertThat(readPastHeldResult.get()).isPositive();
    }

    private void runStressIteration(Random random, boolean eager, int iteration,
            AtomicInteger readPastHeldResult) throws Exception {
        List<Map<String, Object>> script = new ArrayList<>();
        List<String> expectedRegular = new ArrayList<>();
        Map<Integer, List<String>> okUuids = new HashMap<>();
        Map<Integer, Integer> failedFrames = new HashMap<>();
        Map<String, Integer> turnOfUuid = new HashMap<>();
        // How many frames the reader has taken once it took result t.
        Map<Integer, Integer> resultPosition = new HashMap<>();
        List<String> permissionIds = new ArrayList<>();
        int turns = 1 + random.nextInt(5);
        for (int t = 1; t <= turns; t++) {
            boolean failing = random.nextInt(12) == 0;
            okUuids.put(t, new ArrayList<>());
            failedFrames.put(t, 0);
            int frames = random.nextInt(7);
            boolean mirrored = false;
            for (int k = 0; k <= frames; k++) {
                int kind = random.nextInt(6);
                boolean last = (k == frames);
                if (kind == 0 || (last && !mirrored)) {
                    String uuid = (failing ? "hang" : "delay" + random.nextInt(5)) + "-" + t + "-" + k;
                    script.add(mirror("s" + t, uuid));
                    turnOfUuid.put(uuid, t);
                    if (failing) {
                        failedFrames.merge(t, 1, Integer::sum);
                    } else {
                        okUuids.get(t).add(uuid);
                    }
                    mirrored = true;
                } else if (kind == 1) {
                    script.add(streamEvent("se" + t + "_" + k));
                    expectedRegular.add("se:se" + t + "_" + k);
                } else if (kind == 2) {
                    script.add(assistant("a" + t + "_" + k));
                    expectedRegular.add("a:a" + t + "_" + k);
                } else if (kind == 3) {
                    String id = "perm_" + t + "_" + k;
                    script.add(canUseTool(id));
                    permissionIds.add(id);
                } else {
                    script.add(user("u" + t + "_" + k));
                    expectedRegular.add("u:u" + t + "_" + k);
                }
            }
            script.add(result("t" + t));
            resultPosition.put(t, script.size());
            expectedRegular.add("r:t" + t);
        }

        PipeTransport transport = new PipeTransport();
        ScriptedStore store = new ScriptedStore();
        store.onAppended = uuids -> {
            Integer t = turnOfUuid.get(uuids.get(0));
            if ((t != null) && (transport.taken() > resultPosition.get(t))) {
                readPastHeldResult.incrementAndGet();
            }
        };
        QueryHandler handler = MirrorBarrierFixtures.handler(transport,
                (tool, input, context) -> CompletableFuture.completedFuture(new PermissionResultAllow()),
                null, 1000);
        // Delays are 0-4 ms; the 150 ms timeout only cuts the hung appends.
        attach(handler, store, STRESS_SEND_TIMEOUT_MS, eager);
        transport.connect();
        handler.start();
        List<String> violations = new CopyOnWriteArrayList<>();
        try {
            Collector consumer = new Collector(handler, message -> {
                if (message instanceof ResultMessage r) {
                    int t = Integer.parseInt(r.result().substring(1));
                    if (!store.appended.containsAll(okUuids.get(t))) {
                        violations.add("t" + t + " delivered before its appends " + okUuids.get(t)
                                + " (stored " + store.appended + ")");
                    }
                }
            });
            for (Map<String, Object> frame : script) {
                transport.push(frame);
            }
            transport.end();
            List<String> seen = consumer.awaitEnd();
            String context = "iteration " + iteration + ", eager " + eager + ": " + seen;
            assertThat(violations).as(context).isEmpty();

            List<String> regular = new ArrayList<>(seen);
            regular.removeIf(l -> l.startsWith("me:"));
            assertThat(regular).as(context).containsExactlyElementsOf(expectedRegular);
            for (int t = 1; t <= turns; t++) {
                int expectedErrors = eager ? failedFrames.get(t) : Math.min(1, failedFrames.get(t));
                int resultAt = seen.indexOf("r:t" + t);
                int previousResultAt = (t == 1) ? -1 : seen.indexOf("r:t" + (t - 1));
                int errors = 0;
                for (int i = 0; i < seen.size(); i++) {
                    if (seen.get(i).equals("me:s" + t)) {
                        errors++;
                        assertThat(i).as(context).isBetween(previousResultAt + 1, resultAt - 1);
                        if (!eager) {
                            assertThat(i).as(context).isEqualTo(resultAt - 1);
                        }
                    }
                }
                assertThat(errors).as(context + " (errors of turn " + t + ")").isEqualTo(expectedErrors);
            }
            for (String id : permissionIds) {
                awaitUntil("a response to " + id, WAIT_SECONDS, () -> !transport.responsesTo(id).isEmpty());
            }
        } finally {
            handler.close();
        }
    }

    // ------------------------------------------------------------------
    // 15. End to end: ClaudeSDKClient and ClaudeSDK.query with a store
    // ------------------------------------------------------------------

    @Test
    void clientControlCallsWorkDuringAHoldAndReceiveResponseStopsAtTheHeldResult() throws Exception {
        Path configDir = Files.createTempDirectory("barrier-client");
        ScriptedStore store = store();
        PipeTransport transport = new PipeTransport();
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sessionStore(store)
                .env(Map.of("CLAUDE_CONFIG_DIR", configDir.toString()))
                .build();
        try (ClaudeSDKClient client = new ClaudeSDKClient(options, transport)) {
            client.connect();
            int initFrames = transport.taken();
            String file = configDir.resolve("projects").resolve("p").resolve("s.jsonl").toString();
            transport.push(Map.of("type", "transcript_mirror", "filePath", file,
                    "entries", List.of(Map.of("type", "user", "uuid", "gate-1"))));
            transport.push(result("r1"));
            transport.push(user("next"));
            awaitUntil("the reader to read past the result", WAIT_SECONDS,
                    () -> transport.taken() == initFrames + 3);

            assertTimeoutPreemptively(Duration.ofSeconds(5), client::interrupt);

            CompletableFuture<List<String>> response = CompletableFuture.supplyAsync(() -> {
                List<String> seen = new ArrayList<>();
                for (Message message : client.receiveResponse()) {
                    seen.add(label(message));
                }
                return seen;
            });
            quietly();
            assertThat(response).isNotDone();
            store.release("gate-1");
            assertThat(response.get(WAIT_SECONDS, TimeUnit.SECONDS)).containsExactly("r:r1");
            assertThat(store.appended).containsExactly("gate-1");

            // A later iterator picks up what followed the result.
            Iterator<Message> later = client.receiveMessages();
            assertThat(later.hasNext()).isTrue();
            assertThat(label(later.next())).isEqualTo("u:next");
        }
    }

    @Test
    void oneShotQueryWithAStoreReturnsOnceTheStoreHasTheTurn() throws Exception {
        Path configDir = Files.createTempDirectory("barrier-query");
        ScriptedStore store = store();
        PipeTransport transport = new PipeTransport();
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sessionStore(store)
                .env(Map.of("CLAUDE_CONFIG_DIR", configDir.toString()))
                .build();
        AtomicReference<List<String>> storedAtResult = new AtomicReference<>();
        CompletableFuture<List<Message>> query = CompletableFuture.supplyAsync(
                () -> ClaudeSDK.query("hi", options, transport));
        awaitUntil("initialize", WAIT_SECONDS, () -> !transport.requestsWritten("initialize").isEmpty());
        String file = configDir.resolve("projects").resolve("p").resolve("s.jsonl").toString();
        transport.push(Map.of("type", "transcript_mirror", "filePath", file,
                "entries", List.of(Map.of("type", "user", "uuid", "gate-1"))));
        transport.push(user("u1"));
        transport.push(result("r1"));
        transport.end();

        awaitUntil("EOF to be read", WAIT_SECONDS, transport::readEnded);
        quietly();
        assertThat(query).isNotDone();
        store.onAppended = storedAtResult::set;
        store.release("gate-1");
        List<Message> messages = query.get(WAIT_SECONDS, TimeUnit.SECONDS);
        List<String> labels = new ArrayList<>();
        for (Message message : messages) {
            labels.add(label(message));
        }
        assertThat(labels).containsExactly("u:u1", "r:r1");
        assertThat(storedAtResult.get()).containsExactly("gate-1");
        assertThat(new HashSet<>(store.appended)).isEqualTo(Set.of("gate-1"));
    }

}
