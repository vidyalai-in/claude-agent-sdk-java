package in.vidyalai.claude.sdk.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
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

    /** Serializes flushes and lets {@link #flush()} await any in-flight drain. */
    private final ReentrantLock drainLock = new ReentrantLock();

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
     * Flush all pending entries. Awaits any in-flight eager flush first.
     */
    public CompletableFuture<Void> flush() {
        return scheduleDrain();
    }

    /**
     * Final flush before teardown. Never raises.
     */
    public CompletableFuture<Void> close() {
        closed = true;
        return flush().exceptionally(e -> {
            logger.log(Level.FINE, "[TranscriptMirrorBatcher] close flush failed", e);
            return null;
        });
    }

    private CompletableFuture<Void> scheduleDrain() {
        return CompletableFuture.runAsync(this::drainAndReport, executor);
    }

    /**
     * Detach the pending buffer, await any prior flush, then send. Never
     * raises — adapter and {@code onError} callback errors are caught and
     * logged.
     */
    private void drainAndReport() {
        List<MirrorEntry> items;
        synchronized (this) {
            if (pending.isEmpty()) {
                return;
            }
            items = new ArrayList<>(pending);
            pending.clear();
            pendingEntries = 0;
            pendingBytes = 0;
        }
        List<KeyError> errors = new ArrayList<>();
        drainLock.lock();
        try {
            doFlush(items, errors);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[TranscriptMirrorBatcher] doFlush raised", e);
            return;
        } finally {
            drainLock.unlock();
        }
        // Report errors after releasing the lock so a slow onError callback
        // cannot block subsequent drains.
        for (KeyError ke : errors) {
            try {
                onError.accept(ke.key, ke.message);
            } catch (Exception cb) {
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
                    store.appendAsync(key, entries, executor)
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
                } catch (CompletionException ce) {
                    lastErr = ce.getCause() != null ? ce.getCause() : ce;
                    logger.fine("[TranscriptMirrorBatcher] append attempt "
                            + (attempt + 1) + "/" + MIRROR_APPEND_MAX_ATTEMPTS
                            + " failed for " + filePath + ": " + lastErr.getMessage());
                } catch (Exception ex) {
                    lastErr = ex.getCause() != null ? ex.getCause() : ex;
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
