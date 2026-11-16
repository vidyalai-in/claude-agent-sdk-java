package in.vidyalai.claude.sdk.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;
import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionStore;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;
import in.vidyalai.claude.sdk.types.session.SessionStoreExecutor;

/**
 * Batching layer between {@code transcript_mirror} stdout frames and a
 * {@link SessionStore}.
 *
 * <p>The CLI subprocess emits
 * {@code {"type": "transcript_mirror", "filePath": ..., "entries": [...]}}
 * frames interleaved with normal SDK messages. The receive loop peels these
 * off and hands them to {@link #enqueue(String, List)}, which accumulates them
 * and flushes to {@link SessionStore#appendAsync} either when a {@code result}
 * message arrives (explicit {@link #flush()}) or when the pending buffer
 * exceeds size thresholds (eager background flush). This keeps adapter latency
 * off the hot path during model streaming.
 *
 * <p>Mirrors Python SDK's {@code transcript_mirror_batcher.py}.
 *
 * <p><b>Failure handling:</b> Adapter failures are retried
 * ({@link #MIRROR_APPEND_MAX_ATTEMPTS} attempts total) with short backoff;
 * timeouts are not retried since the in-flight call may still land. Only after
 * the final attempt fails is the batch dropped and reported via
 * {@code onError}. Failures never raise — the local-disk transcript is already
 * durable so the session must continue unaffected. Adapters should dedupe by
 * {@code entry.uuid()} when present (some entry types lack a uuid) since a
 * retried batch may partially overlap a prior partial write.
 *
 * <p><b>Ordering:</b> drains are strictly FIFO. A drain detaches the pending
 * buffer and is chained behind every earlier drain in the same critical
 * section, so two eager flushes, or an eager flush and an explicit
 * {@link #flush()}, append in enqueue order: never interleaved, never
 * duplicated. The Python SDK gets the same guarantee by detaching and then
 * queueing on a FIFO lock with no suspension point in between.
 *
 * <p><b>Bounded completion:</b> the futures returned by {@link #flush()} and
 * {@link #close()} always complete, normally, within {@link #flushTimeoutMs()}
 * of the call. The bound is
 * {@code MIRROR_APPEND_MAX_ATTEMPTS × sendTimeoutMs + Σ MIRROR_APPEND_BACKOFF_MS},
 * which is {@code 3 × 60 000 + 200 + 800 = 181 000 ms} with the defaults: the
 * worst case for one file path, where two attempts fail just inside the
 * per-append timeout and the third times out. When the cap is reached first
 * (several slow paths in one batch, or a backlog of earlier eager drains on a
 * slow store), the future completes anyway and a warning is logged. The drains
 * keep running in the background, still in order, and report any dropped
 * batch through {@code onError}. Callers can therefore wait on these futures
 * without an outer timeout of their own. The adapter's {@code appendAsync} is
 * invoked off the drain thread, so an adapter that blocks inside
 * {@code appendAsync} itself, rather than in the future it returns, is still
 * cut off at {@code sendTimeoutMs}.
 *
 * <p><b>Errors before the result:</b> the futures returned by {@link #flush()}
 * and {@link #close()} complete only after every drain scheduled so far,
 * eager ones included, has appended <em>and</em> reported its dropped batches
 * through {@code onError}. A {@code mirror_error} therefore always reaches the
 * message stream ahead of the {@code result} the flush precedes. The reports
 * are chained on their own, behind the appends they describe, so a slow
 * {@code onError} never holds up a later append; it only delays the flush
 * that waits for it.
 *
 * <p><b>A slow store holds up the stream.</b> The receive loop waits for
 * {@link #flush()} before it yields each {@code result}, so while a store is
 * slow (up to {@link #flushTimeoutMs()}, 181 s by default) no further message
 * or control response is read; an outbound control request such as
 * {@code interrupt()} waits too, and {@code close()} waits for
 * {@link #close()} up to the same bound. The Python SDK behaves the same way.
 */
public final class TranscriptMirrorBatcher {

    private static final Logger logger = Logger.getLogger(TranscriptMirrorBatcher.class.getName());

    /** Eager-flush threshold — entries. Exposed for tests. */
    public static final int MAX_PENDING_ENTRIES = 500;

    /** Eager-flush threshold — bytes (1 MiB). Exposed for tests. */
    public static final int MAX_PENDING_BYTES = 1 << 20;

    /** Default per-append timeout. */
    public static final long SEND_TIMEOUT_MS = 60_000L;

    /** Bounded retry for transient adapter failures. */
    public static final int MIRROR_APPEND_MAX_ATTEMPTS = 3;

    /** Backoff between retries — length must be MIRROR_APPEND_MAX_ATTEMPTS - 1. */
    public static final long[] MIRROR_APPEND_BACKOFF_MS = { 200L, 800L };

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Where drains run. Deliberately <em>not</em> {@link #executor}.
     *
     * <p>A drain blocks waiting for the {@code appendAsync} it submits, and the
     * default {@code appendAsync} submits to {@code executor}. Running the drain
     * there too means it waits for a slot it is itself occupying: harmless on the
     * unbounded thread-per-task default, but on a bounded executor - the
     * configuration {@code SessionStoreExecutor}'s javadoc offers as an example -
     * the append can never be scheduled, every flush waits out
     * {@code sendTimeoutMs} and the batch is dropped through {@code onError}.
     *
     * <p>Shared and thread-per-task, so it costs nothing while idle, and daemon,
     * so it never holds the JVM open. The adapter itself still runs on
     * {@code executor}: that is the caller's lever for bounding their own store
     * work, and this only keeps the SDK's blocking coordinator out of it.
     * Ordering does not come from this executor: drains are chained one behind
     * the other (see {@link #scheduleDrain()}).
     */
    private static final Executor DRAIN_EXECUTOR =
            Threads.newThreadPerTaskExecutor("session-store-mirror-drain-");

    private final SessionStore store;
    private final String projectsDir;
    private final BiConsumer<@Nullable SessionKey, String> onError;
    private final long sendTimeoutMs;
    private final int maxPendingEntries;
    private final int maxPendingBytes;
    private final Executor executor;

    private final List<MirrorEntry> pending = new ArrayList<>();
    private int pendingEntries = 0;
    private int pendingBytes = 0;

    /**
     * The append phase of the most recently scheduled drain. Each new drain is
     * chained behind it under this object's monitor, so drains run strictly in
     * the order they detached their buffers. Never completes exceptionally.
     * Guarded by {@code this}.
     */
    private CompletableFuture<?> drainTail = CompletableFuture.completedFuture(null);

    /**
     * The error-reporting phase of the most recently scheduled drain. Each
     * drain's {@code onError} reports run behind its own appends and behind the
     * previous drain's reports, so reports are FIFO too, but this chain is
     * separate from {@link #drainTail}: appends never wait for a report.
     * Completing it therefore means every drain scheduled so far has appended
     * and reported. Never completes exceptionally. Guarded by {@code this}.
     */
    private CompletableFuture<?> reportTail = CompletableFuture.completedFuture(null);

    private volatile boolean closed = false;

    private record MirrorEntry(String filePath, List<SessionStoreEntry> entries, int size) {
    }

    public TranscriptMirrorBatcher(
            SessionStore store,
            String projectsDir,
            BiConsumer<@Nullable SessionKey, String> onError) {
        this(store, projectsDir, onError, SEND_TIMEOUT_MS, MAX_PENDING_ENTRIES, MAX_PENDING_BYTES,
                SessionStoreExecutor.getDefault());
    }

    public TranscriptMirrorBatcher(
            SessionStore store,
            String projectsDir,
            BiConsumer<@Nullable SessionKey, String> onError,
            long sendTimeoutMs,
            int maxPendingEntries,
            int maxPendingBytes,
            Executor executor) {
        this.store = store;
        this.projectsDir = projectsDir;
        this.onError = onError;
        this.sendTimeoutMs = sendTimeoutMs;
        this.maxPendingEntries = maxPendingEntries;
        this.maxPendingBytes = maxPendingBytes;
        this.executor = executor;
    }

    /**
     * Buffer a frame; schedule an eager flush if thresholds are exceeded.
     *
     * <p>Fire-and-forget — never blocks the caller. Eager-flush failures are
     * routed through {@code onError}.
     */
    public synchronized void enqueue(String filePath, List<SessionStoreEntry> entries) {
        if (closed) {
            return;
        }
        // Approximate wire size — one stringify per frame (not per entry).
        int size;
        try {
            size = MAPPER.writeValueAsString(entries).length();
        } catch (JsonProcessingException e) {
            // Defensive: fall back to entry count × 100 as a rough estimate.
            size = entries.size() * 100;
        }
        pending.add(new MirrorEntry(filePath, new ArrayList<>(entries), size));
        pendingEntries += entries.size();
        pendingBytes += size;
        if (pendingEntries > maxPendingEntries || pendingBytes > maxPendingBytes) {
            scheduleDrain();
        }
    }

    /**
     * Flush all pending entries, serialized after any in-flight drain.
     *
     * <p>The future completes once every drain scheduled so far (eager drains
     * included) has finished its appends and delivered its {@code onError}
     * reports, or once {@link #flushTimeoutMs()} has passed.
     *
     * <p>Never completes exceptionally, and always completes within
     * {@link #flushTimeoutMs()} (see the class Javadoc).
     */
    public CompletableFuture<Void> flush() {
        return bounded(scheduleDrain(), "flush");
    }

    /**
     * Final flush before teardown. Never raises; bounded like {@link #flush()}.
     * Frames enqueued after this call are dropped.
     */
    public CompletableFuture<Void> close() {
        closed = true;
        return bounded(scheduleDrain(), "close flush");
    }

    /**
     * Upper bound, in milliseconds, on the time the futures returned by
     * {@link #flush()} and {@link #close()} take to complete:
     * {@code MIRROR_APPEND_MAX_ATTEMPTS × sendTimeoutMs + Σ MIRROR_APPEND_BACKOFF_MS}
     * (181 000 ms with the default {@link #SEND_TIMEOUT_MS}).
     */
    public long flushTimeoutMs() {
        long backoff = 0L;
        for (long b : MIRROR_APPEND_BACKOFF_MS) {
            backoff += b;
        }
        return MIRROR_APPEND_MAX_ATTEMPTS * Math.max(0L, sendTimeoutMs) + backoff;
    }

    /**
     * A future that completes normally when {@code drain} does, or after
     * {@link #flushTimeoutMs()}, whichever comes first.
     */
    private CompletableFuture<Void> bounded(CompletableFuture<?> drain, String what) {
        long capMs = flushTimeoutMs();
        CompletableFuture<Void> out = new CompletableFuture<>();
        drain.whenComplete((r, e) -> out.complete(null));
        // completeOnTimeout uses the JDK's shared delay scheduler; it starts
        // no thread per call.
        out.completeOnTimeout(null, capMs, TimeUnit.MILLISECONDS);
        out.whenComplete((r, e) -> {
            if (!drain.isDone()) {
                logger.warning("[TranscriptMirrorBatcher] " + what + " did not finish within "
                        + capMs + "ms; returning while the remaining appends continue in order "
                        + "in the background");
            }
        });
        return out;
    }

    /**
     * Detach the pending buffer and chain its drain behind every earlier one.
     *
     * <p>Detaching and chaining happen in one critical section, which is what
     * makes drains FIFO: a later drain can never overtake an earlier one.
     *
     * <p>The returned future is {@link #reportTail}: it covers this drain and
     * every earlier one, each one's appends and then its {@code onError}
     * reports. With nothing pending it still waits for the in-flight drains
     * and their reports, so {@link #flush()} serializes after them. The
     * reports are not part of the append chain, so a slow {@code onError}
     * callback cannot hold up later drains.
     */
    private synchronized CompletableFuture<?> scheduleDrain() {
        if (pending.isEmpty()) {
            return reportTail;
        }
        List<MirrorEntry> items = new ArrayList<>(pending);
        pending.clear();
        pendingEntries = 0;
        pendingBytes = 0;
        CompletableFuture<List<KeyError>> appended =
                drainTail.thenApplyAsync(ignored -> appendAll(items), DRAIN_EXECUTOR);
        drainTail = appended.handle((r, e) -> null);
        CompletableFuture<List<KeyError>> errors =
                appended.handle((r, e) -> ((r != null) ? r : List.<KeyError>of()));
        reportTail = reportTail
                .thenCombineAsync(errors, (ignored, errs) -> {
                    reportErrors(errs);
                    return null;
                }, DRAIN_EXECUTOR)
                .handle((r, e) -> null);
        return reportTail;
    }

    /** Send one detached batch. Never raises. */
    private List<KeyError> appendAll(List<MirrorEntry> items) {
        List<KeyError> errors = new ArrayList<>();
        try {
            doFlush(items, errors);
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "[TranscriptMirrorBatcher] doFlush raised", e);
        }
        return errors;
    }

    /** Report dropped batches through {@code onError}. Never raises. */
    private void reportErrors(List<KeyError> errors) {
        for (KeyError ke : errors) {
            try {
                onError.accept(ke.key, ke.message);
            } catch (RuntimeException cb) {
                logger.log(Level.SEVERE, "[TranscriptMirrorBatcher] onError callback raised", cb);
            }
        }
    }

    private record KeyError(@Nullable SessionKey key, String message) {
    }

    private void doFlush(List<MirrorEntry> items, List<KeyError> errors) {
        // Coalesce by filePath so each unique file gets one append per flush
        // instead of one per enqueued frame. LinkedHashMap preserves first-seen order;
        // entries within a path keep enqueue order.
        Map<String, List<SessionStoreEntry>> byPath = new LinkedHashMap<>();
        for (MirrorEntry item : items) {
            byPath.computeIfAbsent(item.filePath(), k -> new ArrayList<>()).addAll(item.entries());
        }

        for (Map.Entry<String, List<SessionStoreEntry>> e : byPath.entrySet()) {
            String filePath = e.getKey();
            List<SessionStoreEntry> entries = e.getValue();
            if (entries.isEmpty()) {
                continue;
            }
            SessionKey key = InMemorySessionStore.filePathToSessionKey(filePath, projectsDir);
            if (key == null) {
                logger.warning(
                        "[SessionStore] dropping mirror frame: filePath " + filePath
                                + " is not under " + projectsDir
                                + " -- subprocess CLAUDE_CONFIG_DIR likely differs from parent (custom env / container?)");
                continue;
            }

            Throwable lastErr = null;
            boolean succeeded = false;
            for (int attempt = 0; attempt < MIRROR_APPEND_MAX_ATTEMPTS; attempt++) {
                if (attempt > 0) {
                    try {
                        Thread.sleep(MIRROR_APPEND_BACKOFF_MS[attempt - 1]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        lastErr = ie;
                        break;
                    }
                }
                try {
                    // Invoke appendAsync off this thread: an adapter that blocks
                    // inside appendAsync itself would otherwise never reach the
                    // timed get below, and would stall every later drain with it.
                    CompletableFuture.supplyAsync(
                            () -> store.appendAsync(key, entries, executor), DRAIN_EXECUTOR)
                            .thenCompose(f -> f)
                            .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                    succeeded = true;
                    break;
                } catch (TimeoutException te) {
                    // Don't retry on timeout: the in-flight call may still land.
                    // A retry would launch a concurrent duplicate.
                    lastErr = te;
                    logger.fine(
                            "[TranscriptMirrorBatcher] append timed out after "
                                    + sendTimeoutMs + "ms for " + filePath + " — not retrying");
                    break;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    lastErr = ie;
                    break;
                } catch (ExecutionException | RuntimeException ex) {
                    lastErr = ex.getCause() != null ? ex.getCause() : ex;
                    // supplyAsync + thenCompose wrap the adapter's own failure once more.
                    if (lastErr instanceof java.util.concurrent.CompletionException
                            && lastErr.getCause() != null) {
                        lastErr = lastErr.getCause();
                    }
                    logger.fine("[TranscriptMirrorBatcher] append attempt "
                            + (attempt + 1) + "/" + MIRROR_APPEND_MAX_ATTEMPTS
                            + " failed for " + filePath + ": " + lastErr.getMessage());
                }
            }
            if (!succeeded) {
                logger.severe("[TranscriptMirrorBatcher] flush failed for " + filePath
                        + ": " + (lastErr != null ? lastErr.getMessage() : "<unknown>"));
                errors.add(new KeyError(key, lastErr != null ? String.valueOf(lastErr.getMessage()) : ""));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Test inspection
    // -----------------------------------------------------------------------

    /** Test helper — current pending entry count. */
    public synchronized int pendingEntries() {
        return pendingEntries;
    }

    /** Test helper — current pending byte estimate. */
    public synchronized int pendingBytes() {
        return pendingBytes;
    }

    /**
     * Threshold above which a background drain is auto-scheduled by
     * {@link #enqueue}. Mirrors Python's {@code batcher.max_pending_entries}
     * public attribute. {@code 0} means every enqueue triggers a drain (eager
     * mode).
     */
    public int maxPendingEntries() {
        return maxPendingEntries;
    }

    /**
     * Byte threshold above which a background drain is auto-scheduled by
     * {@link #enqueue}. Mirrors Python's {@code batcher.max_pending_bytes}
     * public attribute. {@code 0} means every enqueue triggers a drain.
     */
    public int maxPendingBytes() {
        return maxPendingBytes;
    }

}
