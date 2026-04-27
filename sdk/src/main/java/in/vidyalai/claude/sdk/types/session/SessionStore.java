package in.vidyalai.claude.sdk.types.session;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.jspecify.annotations.Nullable;

/**
 * Adapter for mirroring session transcripts to external storage.
 *
 * <p>The CLI subprocess still writes to local disk (set
 * {@code CLAUDE_CONFIG_DIR=/tmp} for an ephemeral local copy); the adapter
 * receives a secondary copy.
 *
 * <p>The SDK never deletes from your store unless you call
 * {@link in.vidyalai.claude.sdk.ClaudeSDK#deleteSessionViaStore} (or equivalent)
 * with {@link #delete(SessionKey)} implemented. Retention is the adapter's
 * responsibility — implement TTL, object-storage lifecycle policies, or
 * scheduled cleanup according to your compliance requirements.
 *
 * <p>Only {@link #append(SessionKey, List)} and {@link #load(SessionKey)} are
 * required. The remaining methods are optional: implementers may override
 * {@link #implementsListSessions()}, {@link #implementsListSessionSummaries()},
 * {@link #implementsListSubkeys()}, and {@link #implementsDelete()} to declare
 * which optional methods they support. The defaults throw
 * {@link UnsupportedOperationException}.
 *
 * <p><b>Java vs Python:</b> The Java SDK uses synchronous (blocking) method
 * signatures. Adapters that perform I/O should run on virtual threads or
 * appropriate executors at the call site if non-blocking semantics are
 * required.
 */
public interface SessionStore {

    /**
     * Mirror a batch of transcript entries.
     *
     * <p>Called AFTER the subprocess's local write succeeds — durability is
     * already guaranteed locally.
     *
     * <p>Batches arrive at ~100ms cadence during active turns. Entries are
     * JSON-safe plain objects — one per line in the local JSONL file.
     *
     * <p>Within a single process, persist entries in append-call order; across
     * concurrent processes, order is by storage commit time, not call time.
     *
     * <p>Most entries carry a stable {@code uuid} that adapters should treat as
     * an idempotency key (upsert / ignore-duplicate). Entries without a
     * {@code uuid} (e.g. titles, tags, mode markers) should be appended without
     * dedup. Exceptions are surfaced to the caller; the SDK's mirror batcher
     * retries before reporting a {@link in.vidyalai.claude.sdk.types.message.MirrorErrorMessage}.
     */
    void append(SessionKey key, List<SessionStoreEntry> entries);

    /**
     * Load a full session for resume.
     *
     * <p>Called once, in the SDK parent, before subprocess spawn. The result is
     * materialized to a temporary JSONL file; the subprocess resumes from that
     * file using its existing resume code.
     *
     * <p>Return {@code null} for a key that was never written; adapters that
     * cannot distinguish "never written" from "emptied" may return {@code null}
     * for both. Returned entries must be deep-equal to what was appended —
     * byte-equal serialization is NOT required.
     */
    @Nullable
    List<SessionStoreEntry> load(SessionKey key);

    /**
     * List sessions for a {@code projectKey}. Returns IDs + modification times.
     *
     * <p>{@code mtime} is Unix epoch milliseconds; adapters without native
     * modification time (e.g. Redis) must maintain their own index. Result
     * order is unspecified — the SDK sorts by {@code mtime} descending.
     *
     * <p>Optional — if unimplemented (default), throws
     * {@link UnsupportedOperationException}.
     */
    default List<SessionStoreListEntry> listSessions(String projectKey) {
        throw new UnsupportedOperationException("listSessions is not implemented");
    }

    /**
     * Return incrementally-maintained summaries for all sessions in one call.
     *
     * <p>Stores should maintain these via {@code foldSessionSummary(...)} inside
     * {@link #append(SessionKey, List)}. Skip the fold for keys with a
     * {@code subpath} — subagent transcripts must not contribute to the main
     * session's summary.
     *
     * <p>Like {@link #listSessions(String)}, results are scoped to a single
     * {@code projectKey} and exclude {@code subpath} entries.
     *
     * <p>Optional — if unimplemented, list-from-store falls back to
     * {@link #listSessions(String)} + per-session {@link #load(SessionKey)}.
     */
    default List<SessionSummaryEntry> listSessionSummaries(String projectKey) {
        throw new UnsupportedOperationException("listSessionSummaries is not implemented");
    }

    /**
     * Delete a session.
     *
     * <p>Deleting a main-transcript key (no {@code subpath}) must cascade to all
     * subkeys under that session so subagent transcripts aren't orphaned. A
     * targeted delete with an explicit {@code subpath} removes only that one
     * entry.
     *
     * <p>Optional — if unimplemented, deletion is a no-op.
     */
    default void delete(SessionKey key) {
        throw new UnsupportedOperationException("delete is not implemented");
    }

    /**
     * List all subpath keys under a session (e.g. subagent transcripts).
     *
     * <p>Used during resume to discover and materialize all subagent data.
     *
     * <p>Optional — if unimplemented, resume only materializes the main
     * transcript.
     */
    default List<String> listSubkeys(SessionListSubkeysKey key) {
        throw new UnsupportedOperationException("listSubkeys is not implemented");
    }

    /**
     * Indicates whether {@link #listSessions(String)} is implemented.
     * Defaults to {@code false} — override to return {@code true} when the
     * subclass implements it.
     */
    default boolean implementsListSessions() {
        return false;
    }

    /**
     * Indicates whether {@link #listSessionSummaries(String)} is implemented.
     */
    default boolean implementsListSessionSummaries() {
        return false;
    }

    /**
     * Indicates whether {@link #delete(SessionKey)} is implemented.
     */
    default boolean implementsDelete() {
        return false;
    }

    /**
     * Indicates whether {@link #listSubkeys(SessionListSubkeysKey)} is implemented.
     */
    default boolean implementsListSubkeys() {
        return false;
    }

    // -----------------------------------------------------------------------
    // Async default wrappers
    // -----------------------------------------------------------------------
    //
    // Default methods wrap each sync call on a configurable {@link Executor}
    // (default: per-task virtual thread via {@link SessionStoreExecutor}).
    // Adapters with native async clients (AWS SDK v2 async, R2DBC,
    // Lettuce reactive) should override these directly so they don't lose
    // parallelism through a blocking wrapper.
    //
    // The mirror batcher and resume materializer call these *Async variants
    // — overriding them is the supported way to plug in a non-blocking
    // adapter end-to-end.

    /**
     * Async variant of {@link #append(SessionKey, List)} using the configured
     * {@link SessionStoreExecutor#getDefault()} executor.
     *
     * <p>Adapters with native async clients should override this method
     * directly to avoid a thread hop.
     */
    default CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries) {
        return appendAsync(key, entries, SessionStoreExecutor.getDefault());
    }

    /**
     * Async variant of {@link #append(SessionKey, List)} using the supplied executor.
     */
    default CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries, Executor executor) {
        return CompletableFuture.runAsync(() -> append(key, entries), executor);
    }

    /**
     * Async variant of {@link #load(SessionKey)} using the configured executor.
     */
    default CompletableFuture<@Nullable List<SessionStoreEntry>> loadAsync(SessionKey key) {
        return loadAsync(key, SessionStoreExecutor.getDefault());
    }

    /**
     * Async variant of {@link #load(SessionKey)} using the supplied executor.
     */
    default CompletableFuture<@Nullable List<SessionStoreEntry>> loadAsync(SessionKey key, Executor executor) {
        return CompletableFuture.supplyAsync(() -> load(key), executor);
    }

    /**
     * Async variant of {@link #listSessions(String)} using the configured executor.
     */
    default CompletableFuture<List<SessionStoreListEntry>> listSessionsAsync(String projectKey) {
        return listSessionsAsync(projectKey, SessionStoreExecutor.getDefault());
    }

    /**
     * Async variant of {@link #listSessions(String)} using the supplied executor.
     */
    default CompletableFuture<List<SessionStoreListEntry>> listSessionsAsync(String projectKey, Executor executor) {
        return CompletableFuture.supplyAsync(() -> listSessions(projectKey), executor);
    }

    /**
     * Async variant of {@link #listSessionSummaries(String)} using the configured executor.
     */
    default CompletableFuture<List<SessionSummaryEntry>> listSessionSummariesAsync(String projectKey) {
        return listSessionSummariesAsync(projectKey, SessionStoreExecutor.getDefault());
    }

    /**
     * Async variant of {@link #listSessionSummaries(String)} using the supplied executor.
     */
    default CompletableFuture<List<SessionSummaryEntry>> listSessionSummariesAsync(String projectKey,
            Executor executor) {
        return CompletableFuture.supplyAsync(() -> listSessionSummaries(projectKey), executor);
    }

    /**
     * Async variant of {@link #delete(SessionKey)} using the configured executor.
     */
    default CompletableFuture<Void> deleteAsync(SessionKey key) {
        return deleteAsync(key, SessionStoreExecutor.getDefault());
    }

    /**
     * Async variant of {@link #delete(SessionKey)} using the supplied executor.
     */
    default CompletableFuture<Void> deleteAsync(SessionKey key, Executor executor) {
        return CompletableFuture.runAsync(() -> delete(key), executor);
    }

    /**
     * Async variant of {@link #listSubkeys(SessionListSubkeysKey)} using the configured executor.
     */
    default CompletableFuture<List<String>> listSubkeysAsync(SessionListSubkeysKey key) {
        return listSubkeysAsync(key, SessionStoreExecutor.getDefault());
    }

    /**
     * Async variant of {@link #listSubkeys(SessionListSubkeysKey)} using the supplied executor.
     */
    default CompletableFuture<List<String>> listSubkeysAsync(SessionListSubkeysKey key, Executor executor) {
        return CompletableFuture.supplyAsync(() -> listSubkeys(key), executor);
    }

}
