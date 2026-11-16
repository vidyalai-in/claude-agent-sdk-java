package in.vidyalai.claude.sdk.internal;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.types.message.ForkSessionResult;
import in.vidyalai.claude.sdk.types.message.SDKSessionInfo;
import in.vidyalai.claude.sdk.types.message.SessionMessage;
import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionListSubkeysKey;
import in.vidyalai.claude.sdk.types.session.SessionStore;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;
import in.vidyalai.claude.sdk.types.session.SessionStoreListEntry;
import in.vidyalai.claude.sdk.types.session.SessionSummary;
import in.vidyalai.claude.sdk.types.session.SessionSummaryEntry;

/**
 * Internal implementation of {@link SessionStore}-backed session APIs.
 *
 * <p>Mirrors the Python SDK's {@code list_sessions_from_store},
 * {@code get_session_info_from_store}, {@code get_session_messages_from_store},
 * {@code list_subagents_from_store}, and {@code get_subagent_messages_from_store}
 * helpers, plus the {@code rename/tag/delete/fork_session_via_store} mutations.
 *
 * <p>The Python SDK exposes these as {@code async} functions; the Java SDK
 * uses synchronous (blocking) signatures — callers can wrap in
 * {@code CompletableFuture.supplyAsync} or virtual threads if non-blocking
 * semantics are required.
 */
public final class SessionStores {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Upper bound on concurrent {@code store.loadAsync()} calls issued by
     * {@link #listSessionsFromStore}. Keeps large project listings from
     * exhausting adapter connection pools or tripping backend rate limits.
     * Matches the Python SDK's {@code _STORE_LIST_LOAD_CONCURRENCY = 16}.
     */
    private static final int STORE_LIST_LOAD_CONCURRENCY = 16;

    private static final Pattern UUID_RE = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> TRANSCRIPT_ENTRY_TYPES = Set.of(
            "user", "assistant", "attachment", "system", "progress");

    private static final Set<String> TRANSCRIPT_TYPES_FORK = Set.of(
            "user", "assistant", "attachment", "system", "progress");

    private SessionStores() {
    }

    // -------------------------------------------------------------------------
    // Path helpers
    // -------------------------------------------------------------------------

    private static String canonicalizePath(@Nullable String dir) {
        // One canonicalization for disk and store paths, so a project key
        // always matches the on-disk project directory name.
        return Sessions.canonicalizePath((dir != null) ? dir : ".");
    }

    /**
     * Derive the {@link SessionStore} {@code projectKey} for a directory.
     *
     * <p>Defaults to the current working directory. Uses the same realpath +
     * NFC normalization + djb2-hashed sanitization the CLI uses for project
     * directory names.
     */
    public static String projectKeyForDirectory(@Nullable String directory) {
        return Sessions.sanitizePath(canonicalizePath(directory));
    }

    // -------------------------------------------------------------------------
    // Listing APIs
    // -------------------------------------------------------------------------

    /**
     * List sessions from a {@link SessionStore}.
     */
    public static List<SDKSessionInfo> listSessionsFromStore(
            SessionStore sessionStore,
            @Nullable String directory,
            @Nullable Integer limit,
            int offset) {
        Objects.requireNonNull(sessionStore, "sessionStore must not be null");

        String projectPath = canonicalizePath(directory);
        String projectKey = Sessions.sanitizePath(projectPath);
        boolean hasListSessions = sessionStore.implementsListSessions();

        // Fast path: list_session_summaries is implemented
        if (sessionStore.implementsListSessionSummaries()) {
            List<SessionSummaryEntry> summaries;
            try {
                summaries = sessionStore.listSessionSummaries(projectKey);
            } catch (UnsupportedOperationException e) {
                summaries = null;
            }
            if (summaries != null) {
                List<SessionStoreListEntry> listing = hasListSessions
                        ? new ArrayList<>(sessionStore.listSessions(projectKey))
                        : List.of();
                Map<String, Long> knownMtimes = new HashMap<>();
                for (SessionStoreListEntry e : listing) {
                    knownMtimes.put(e.sessionId(), e.mtime());
                }

                List<Slot> slots = new ArrayList<>();
                Set<String> freshSummaryIds = new HashSet<>();
                for (SessionSummaryEntry s : summaries) {
                    String sid = s.sessionId();
                    if (hasListSessions) {
                        Long known = knownMtimes.get(sid);
                        if (known == null) {
                            // Session listSessions no longer reports — drop.
                            continue;
                        }
                        if (s.mtime() < known) {
                            // Stale sidecar — gap-fill from source.
                            continue;
                        }
                    }
                    SDKSessionInfo info = SessionSummary.summaryEntryToSdkInfo(s, projectPath);
                    if (info == null) {
                        // Sidechain or empty summary — drop, but mark fresh
                        // so it doesn't get re-fetched via gap-fill.
                        freshSummaryIds.add(sid);
                        continue;
                    }
                    slots.add(new Slot(s.mtime(), sid, info));
                    freshSummaryIds.add(sid);
                }
                if (hasListSessions) {
                    for (SessionStoreListEntry e : listing) {
                        if (!freshSummaryIds.contains(e.sessionId())) {
                            slots.add(new Slot(e.mtime(), e.sessionId(), null));
                        }
                    }
                }

                slots.sort((a, b) -> Long.compare(b.mtime(), a.mtime()));
                List<Slot> page = applyOffsetLimit(slots, offset, limit);

                // Gap-fill missing slots via per-session loadAsync (bounded
                // concurrency, matches Python's asyncio.gather + Semaphore(16)).
                List<Slot> toFill = new ArrayList<>();
                for (Slot slot : page) {
                    if (slot.info() == null) {
                        toFill.add(slot);
                    }
                }
                if (!toFill.isEmpty()) {
                    Map<String, SDKSessionInfo> filled = deriveInfosViaLoadConcurrent(
                            sessionStore, toFill, directory, projectPath);
                    for (Slot slot : toFill) {
                        slot.setInfo(filled.get(slot.sessionId()));
                    }
                }

                List<SDKSessionInfo> result = new ArrayList<>();
                for (Slot slot : page) {
                    if (slot.info() != null) {
                        result.add(slot.info());
                    }
                }
                return result;
            }
        }

        if (!hasListSessions) {
            throw new IllegalStateException(
                    "session_store implements neither listSessionSummaries() nor "
                            + "listSessions() -- cannot list sessions. Provide a store with "
                            + "at least one of those methods.");
        }
        List<SessionStoreListEntry> listing = new ArrayList<>(sessionStore.listSessions(projectKey));
        List<Slot> slots = new ArrayList<>();
        for (SessionStoreListEntry entry : listing) {
            slots.add(new Slot(entry.mtime(), entry.sessionId(), null));
        }
        // Concurrent per-session loadAsync, then filter sidechain/no-summary
        // before applying sort+limit so pagination indexes the same filtered
        // set as the disk path.
        Map<String, SDKSessionInfo> filled = deriveInfosViaLoadConcurrent(
                sessionStore, slots, directory, projectPath);
        List<SDKSessionInfo> results = new ArrayList<>();
        for (Slot slot : slots) {
            SDKSessionInfo info = filled.get(slot.sessionId());
            if (info != null) {
                results.add(info);
            }
        }
        return Sessions.applySortLimitOffset(results, limit, offset);
    }

    /**
     * Read metadata for a single session from a {@link SessionStore}.
     *
     * <p>The entries are serialized to JSONL and run through the same lite
     * parse as the local-disk path, so {@code fileSize} is the JSONL byte size,
     * {@code firstPrompt}/{@code createdAt} come from its first 64 KB, and
     * {@code lastModified} is the last entry's {@code timestamp} (now when it
     * has none). An exception thrown by {@code store.load()} propagates; it is
     * not reported as "not found".
     */
    @Nullable
    public static SDKSessionInfo getSessionInfoFromStore(
            SessionStore sessionStore,
            String sessionId,
            @Nullable String directory) {
        if (!UUID_RE.matcher(sessionId).matches()) {
            return null;
        }
        Objects.requireNonNull(sessionStore, "sessionStore must not be null");
        SessionKey key = new SessionKey(projectKeyForDirectory(directory), sessionId, null);
        List<SessionStoreEntry> entries = sessionStore.load(key);
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        String jsonl = entriesToJsonl(entries);
        String projectPath = canonicalizePath(directory);
        return Sessions.parseSessionInfoFromLite(
                sessionId, jsonlToLite(jsonl, mtimeFromJsonlTail(jsonl)), projectPath);
    }

    /**
     * Read a session's conversation messages from a {@link SessionStore}.
     */
    public static List<SessionMessage> getSessionMessagesFromStore(
            SessionStore sessionStore,
            String sessionId,
            @Nullable String directory,
            @Nullable Integer limit,
            int offset) {
        if (!UUID_RE.matcher(sessionId).matches()) {
            return List.of();
        }
        Objects.requireNonNull(sessionStore, "sessionStore must not be null");
        String projectKey = projectKeyForDirectory(directory);
        SessionKey key = new SessionKey(projectKey, sessionId, null);
        List<SessionStoreEntry> entries = sessionStore.load(key);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entriesToSessionMessages(filterTranscriptEntries(entries), limit, offset);
    }

    /**
     * List subagent IDs for a session from a {@link SessionStore}.
     */
    public static List<String> listSubagentsFromStore(
            SessionStore sessionStore,
            String sessionId,
            @Nullable String directory) {
        if (!UUID_RE.matcher(sessionId).matches()) {
            return List.of();
        }
        Objects.requireNonNull(sessionStore, "sessionStore must not be null");
        if (!sessionStore.implementsListSubkeys()) {
            throw new IllegalStateException(
                    "session_store does not implement listSubkeys() -- cannot list "
                            + "subagents. Provide a store with a listSubkeys() method.");
        }
        String projectKey = projectKeyForDirectory(directory);
        List<String> subkeys = sessionStore.listSubkeys(
                new SessionListSubkeysKey(projectKey, sessionId));
        Set<String> seen = new HashSet<>();
        List<String> ids = new ArrayList<>();
        for (String subpath : subkeys) {
            if (!subpath.startsWith("subagents/")) {
                continue;
            }
            int slash = subpath.lastIndexOf('/');
            String last = slash >= 0 ? subpath.substring(slash + 1) : subpath;
            if (last.startsWith("agent-")) {
                String agentId = last.substring("agent-".length());
                if (seen.add(agentId)) {
                    ids.add(agentId);
                }
            }
        }
        return ids;
    }

    /**
     * Read a subagent's conversation messages from a {@link SessionStore}.
     *
     * <p>{@code parentToolUseId} / {@code parentAgentId} are taken from the
     * subagent's {@code agent_metadata} entry in the store (null if absent).
     */
    public static List<SessionMessage> getSubagentMessagesFromStore(
            SessionStore sessionStore,
            String sessionId,
            String agentId,
            @Nullable String directory,
            @Nullable Integer limit,
            int offset) {
        if (!UUID_RE.matcher(sessionId).matches()) {
            return List.of();
        }
        if (agentId == null || agentId.isEmpty()) {
            return List.of();
        }
        Objects.requireNonNull(sessionStore, "sessionStore must not be null");
        String projectKey = projectKeyForDirectory(directory);

        String subpath = "subagents/agent-" + agentId;
        if (sessionStore.implementsListSubkeys()) {
            List<String> subkeys = sessionStore.listSubkeys(
                    new SessionListSubkeysKey(projectKey, sessionId));
            String target = "agent-" + agentId;
            String match = null;
            for (String sk : subkeys) {
                if (sk.startsWith("subagents/")) {
                    int slash = sk.lastIndexOf('/');
                    String last = slash >= 0 ? sk.substring(slash + 1) : sk;
                    if (target.equals(last)) {
                        match = sk;
                        break;
                    }
                }
            }
            if (match == null) {
                return List.of();
            }
            subpath = match;
        }

        SessionKey key = new SessionKey(projectKey, sessionId, subpath);
        List<SessionStoreEntry> entries = sessionStore.load(key);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        // The synthetic agent_metadata entry (the store's copy of the
        // .meta.json sidecar) records which Agent tool_use spawned this
        // subagent. Recover the parent ids from it — last one wins, since the
        // metadata is rewritten on resume — then drop it: it is not a
        // transcript line.
        AgentMetadataSplit split = splitAgentMetadata(entries);
        if (split.transcript().isEmpty()) {
            return List.of();
        }
        Sessions.ParentIds parentIds = Sessions.parentIdsFromAgentMetadata(split.metadata());
        return entriesToSubagentMessages(
                filterTranscriptEntries(split.transcript()), limit, offset, parentIds);
    }

    /**
     * A subagent's store entries partitioned into its metadata and its
     * transcript lines.
     *
     * @param metadata   the last {@code agent_metadata} entry (it is rewritten
     *                   on resume, so last wins), or null when there is none
     * @param transcript everything else, in order
     */
    record AgentMetadataSplit(
            @Nullable Map<String, Object> metadata,
            List<SessionStoreEntry> transcript) {
    }

    /**
     * Separates the synthetic {@code agent_metadata} entry from transcript
     * lines.
     *
     * <p>A subagent's {@link SessionStore} stream carries its
     * {@code .meta.json} sidecar as {@code {"type": "agent_metadata", ...}}
     * entries alongside the transcript.
     */
    static AgentMetadataSplit splitAgentMetadata(List<SessionStoreEntry> entries) {
        Map<String, Object> metadata = null;
        List<SessionStoreEntry> transcript = new ArrayList<>();
        for (SessionStoreEntry e : entries) {
            if ("agent_metadata".equals(e.type())) {
                metadata = new LinkedHashMap<>(e.asMap());
            } else {
                transcript.add(e);
            }
        }
        return new AgentMetadataSplit(metadata, transcript);
    }

    // -------------------------------------------------------------------------
    // Mutation APIs
    // -------------------------------------------------------------------------

    /**
     * Rename a session by appending a custom-title entry to a {@link SessionStore}.
     */
    public static void renameSessionViaStore(
            SessionStore sessionStore,
            String sessionId,
            String title,
            @Nullable String directory) {
        if (!UUID_RE.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("Invalid session_id: " + sessionId);
        }
        String stripped = title == null ? "" : title.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("title must be non-empty");
        }
        Objects.requireNonNull(sessionStore, "sessionStore must not be null");
        String projectKey = projectKeyForDirectory(directory);
        SessionKey key = new SessionKey(projectKey, sessionId, null);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "custom-title");
        entry.put("customTitle", stripped);
        entry.put("sessionId", sessionId);
        entry.put("uuid", UUID.randomUUID().toString());
        entry.put("timestamp", isoNow());
        sessionStore.append(key, List.of(SessionStoreEntry.of(entry)));
    }

    /**
     * Tag a session by appending a tag entry to a {@link SessionStore}.
     * Pass {@code null} for {@code tag} to clear the tag.
     */
    public static void tagSessionViaStore(
            SessionStore sessionStore,
            String sessionId,
            @Nullable String tag,
            @Nullable String directory) {
        if (!UUID_RE.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("Invalid session_id: " + sessionId);
        }
        Objects.requireNonNull(sessionStore, "sessionStore must not be null");
        String tagValue;
        if (tag == null) {
            tagValue = "";
        } else {
            // Same sanitizer as the local-disk tagSession (NFKC plus Cf/Co/Cn
            // and explicit ranges), so both paths store the same tag.
            String sanitized = SessionMutations.sanitizeUnicode(tag).strip();
            if (sanitized.isEmpty()) {
                throw new IllegalArgumentException("tag must be non-empty (use null to clear)");
            }
            tagValue = sanitized;
        }
        String projectKey = projectKeyForDirectory(directory);
        SessionKey key = new SessionKey(projectKey, sessionId, null);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "tag");
        entry.put("tag", tagValue);
        entry.put("sessionId", sessionId);
        entry.put("uuid", UUID.randomUUID().toString());
        entry.put("timestamp", isoNow());
        sessionStore.append(key, List.of(SessionStoreEntry.of(entry)));
    }

    /**
     * Delete a session from a {@link SessionStore}.
     *
     * <p>If the store does not implement {@link SessionStore#delete(SessionKey)},
     * deletion is a no-op (appropriate for WORM/append-only backends).
     */
    public static void deleteSessionViaStore(
            SessionStore sessionStore,
            String sessionId,
            @Nullable String directory) {
        if (!UUID_RE.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("Invalid session_id: " + sessionId);
        }
        Objects.requireNonNull(sessionStore, "sessionStore must not be null");
        if (!sessionStore.implementsDelete()) {
            return;
        }
        String projectKey = projectKeyForDirectory(directory);
        sessionStore.delete(new SessionKey(projectKey, sessionId, null));
    }

    /**
     * Fork a session into a new branch with fresh UUIDs via a {@link SessionStore}.
     */
    public static ForkSessionResult forkSessionViaStore(
            SessionStore sessionStore,
            String sessionId,
            @Nullable String directory,
            @Nullable String upToMessageId,
            @Nullable String title) throws IOException {
        if (!UUID_RE.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("Invalid session_id: " + sessionId);
        }
        if ((upToMessageId != null) && upToMessageId.isEmpty()) {
            upToMessageId = null; // "" means no cutoff, as in Python's `if up_to_message_id`
        }
        if (upToMessageId != null && !UUID_RE.matcher(upToMessageId).matches()) {
            throw new IllegalArgumentException("Invalid up_to_message_id: " + upToMessageId);
        }
        Objects.requireNonNull(sessionStore, "sessionStore must not be null");
        String projectKey = projectKeyForDirectory(directory);
        SessionKey srcKey = new SessionKey(projectKey, sessionId, null);
        List<SessionStoreEntry> loaded = sessionStore.load(srcKey);
        if (loaded == null || loaded.isEmpty()) {
            throw new java.io.FileNotFoundException("Session " + sessionId + " not found");
        }

        // Partition into transcript entries (with uuid) and content-replacement
        // records, mirroring _parse_fork_transcript for the already-parsed path.
        List<Map<String, Object>> transcript = new ArrayList<>();
        List<Object> contentReplacements = new ArrayList<>();
        List<Map<String, Object>> raw = new ArrayList<>();
        for (SessionStoreEntry entry : loaded) {
            Map<String, Object> map = new LinkedHashMap<>(entry.asMap());
            raw.add(map);
            String entryType = entry.type();
            if (entryType != null && TRANSCRIPT_TYPES_FORK.contains(entryType)
                    && map.get("uuid") instanceof String) {
                transcript.add(map);
            } else if ("content-replacement".equals(entryType)
                    && sessionId.equals(map.get("sessionId"))
                    && map.get("replacements") instanceof List<?> reps) {
                contentReplacements.addAll(reps);
            }
        }

        // Build the fork using the shared transform.
        SessionMutations.BuildForkLinesResult result = SessionMutations.buildForkLines(
                transcript, contentReplacements, sessionId, upToMessageId, title,
                () -> deriveTitleFromEntries(raw));

        // Re-parse to objects and append to the destination key.
        List<SessionStoreEntry> outEntries = new ArrayList<>();
        for (String line : result.lines) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = (Map<String, Object>) MAPPER.readValue(line, Map.class);
                outEntries.add(SessionStoreEntry.of(parsed));
            } catch (JsonProcessingException e) {
                throw new IOException("Failed to re-parse fork line: " + line, e);
            }
        }
        SessionKey dstKey = new SessionKey(projectKey, result.forkedSessionId, null);
        sessionStore.append(dstKey, outEntries);
        return new ForkSessionResult(result.forkedSessionId);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Slot with mutable info for two-phase fill (summary first, gap-fill after). */
    private static final class Slot {
        private final long mtime;
        private final String sessionId;
        @Nullable
        private SDKSessionInfo info;

        Slot(long mtime, String sessionId, @Nullable SDKSessionInfo info) {
            this.mtime = mtime;
            this.sessionId = sessionId;
            this.info = info;
        }

        long mtime() {
            return mtime;
        }

        String sessionId() {
            return sessionId;
        }

        @Nullable
        SDKSessionInfo info() {
            return info;
        }

        void setInfo(@Nullable SDKSessionInfo info) {
            this.info = info;
        }
    }

    private static List<Slot> applyOffsetLimit(List<Slot> slots, int offset, @Nullable Integer limit) {
        List<Slot> view = slots;
        if (offset > 0) {
            view = offset >= slots.size() ? List.of() : new ArrayList<>(slots.subList(offset, slots.size()));
        }
        if (limit != null && limit > 0 && view.size() > limit) {
            view = new ArrayList<>(view.subList(0, limit));
        }
        return view;
    }

    /**
     * Derive {@link SDKSessionInfo} for each slot in {@code slots} via
     * concurrent {@code store.loadAsync()} calls, bounded by
     * {@link #STORE_LIST_LOAD_CONCURRENCY}. Returns a {@code sessionId →
     * SDKSessionInfo} map; sidechain/empty-summary sessions are absent.
     *
     * <p>Each session's entries are serialized to JSONL and lite-parsed exactly
     * as the disk path parses a transcript file, with {@code lastModified} set
     * to the listing's mtime. A load that fails yields a sentinel row (empty
     * summary, listing mtime, every other field null) instead of failing the
     * listing.
     *
     * <p>Mirrors Python's {@code _derive_infos_via_load} which uses
     * {@code asyncio.gather} with a {@code Semaphore(16)}.
     */
    private static Map<String, SDKSessionInfo> deriveInfosViaLoadConcurrent(
            SessionStore sessionStore,
            List<Slot> slots,
            @Nullable String directory,
            @Nullable String projectPath) {
        if (slots.isEmpty()) {
            return Map.of();
        }
        java.util.concurrent.Semaphore sem =
                new java.util.concurrent.Semaphore(STORE_LIST_LOAD_CONCURRENCY);
        String projectKey = projectKeyForDirectory(directory);

        // Issue the loads from *this* thread rather than from a task submitted to
        // the store executor. loadAsync()'s default implementation submits to that
        // same executor, so a task blocking there waits on a slot it is itself
        // occupying: with a bounded executor - the very thing SessionStoreExecutor's
        // javadoc suggests - every worker parks and the listing never completes.
        // Acquiring the permit here bounds concurrency exactly as before, but parks
        // the caller instead of an executor thread.
        List<Slot> issued = new ArrayList<>(slots.size());
        List<java.util.concurrent.CompletableFuture<@Nullable SDKSessionInfo>> futures =
                new ArrayList<>(slots.size());

        for (Slot slot : slots) {
            try {
                sem.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            SessionKey key = new SessionKey(projectKey, slot.sessionId(), null);
            java.util.concurrent.CompletableFuture<@Nullable List<SessionStoreEntry>> load;
            try {
                load = sessionStore.loadAsync(key);
            } catch (RuntimeException e) {
                // An adapter that throws outright rather than returning a failed future.
                sem.release();
                issued.add(slot);
                futures.add(java.util.concurrent.CompletableFuture.completedFuture(
                        sentinelOnError(slot)));
                continue;
            }
            issued.add(slot);
            futures.add(load.handle((entries, err) -> {
                sem.release();
                if (err != null) {
                    return sentinelOnError(slot);
                }
                if (entries == null || entries.isEmpty()) {
                    return null;
                }
                return Sessions.parseSessionInfoFromLite(
                        slot.sessionId(), jsonlToLite(entriesToJsonl(entries), slot.mtime()), projectPath);
            }));
        }

        Map<String, SDKSessionInfo> result = new java.util.LinkedHashMap<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                SDKSessionInfo info = futures.get(i).get();
                if (info != null) {
                    result.put(issued.get(i).sessionId(), info);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.util.concurrent.ExecutionException ee) {
                // handle() above already maps failures to a sentinel; anything still
                // failing here is not something a listing should surface.
            }
        }
        return result;
    }

    /**
     * The row a listing keeps for a session whose load failed: empty summary,
     * the listing's mtime, and every other field (cwd included) null. Matches
     * Python's {@code SDKSessionInfo(session_id=sid, summary="", last_modified=mtime)}.
     */
    private static SDKSessionInfo sentinelOnError(Slot slot) {
        return new SDKSessionInfo(slot.sessionId(), "", slot.mtime(),
                null, null, null, null, null, null, null);
    }

    /** JSON writer for {@link #entriesToJsonl}: escapes non-ASCII, as Python's {@code json.dumps} does. */
    private static final ObjectMapper JSONL_MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.core.json.JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature(), true);

    /**
     * Serialize store entries to a JSONL string, one compact JSON object per
     * line, with {@code type} hoisted to the front.
     *
     * <p>The {@code SessionStore.load} contract lets adapters reorder object
     * keys (e.g. Postgres JSONB), but the lite parser looks for
     * <code>{"type":"tag"</code> as a line prefix, so hoisting {@code type} gives
     * the store path the byte shape the disk path sees. Non-ASCII characters are
     * written as JSON unicode escapes, as Python's {@code json.dumps} does by
     * default, so {@code fileSize} matches the Python SDK for the same entries.
     * Mirrors Python's {@code _entries_to_jsonl}.
     */
    static String entriesToJsonl(List<SessionStoreEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (SessionStoreEntry entry : entries) {
            Map<String, Object> data = entry.asMap();
            Map<String, Object> ordered = data;
            if (data.containsKey("type")) {
                ordered = new LinkedHashMap<>();
                ordered.put("type", data.get("type"));
                ordered.putAll(data);
            }
            try {
                sb.append(JSONL_MAPPER.writeValueAsString(ordered));
            } catch (JsonProcessingException e) {
                // Not JSON-safe; the adapter contract rules this out. Keep the
                // line count so the tail line stays the last entry.
                sb.append("{}");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Build the head/tail/size lite shape from an in-memory JSONL string, with
     * the byte semantics of {@code Sessions.readSessionLite}: the head and tail
     * are the first and last {@link Sessions#LITE_READ_BUF_SIZE} bytes, decoded
     * with replacement. Mirrors Python's {@code _jsonl_to_lite}.
     */
    static Sessions.LiteSessionFile jsonlToLite(String jsonl, long mtime) {
        byte[] buf = jsonl.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int size = buf.length;
        int headLen = Math.min(size, Sessions.LITE_READ_BUF_SIZE);
        String head = new String(buf, 0, headLen, java.nio.charset.StandardCharsets.UTF_8);
        String tail = size > Sessions.LITE_READ_BUF_SIZE
                ? new String(buf, size - Sessions.LITE_READ_BUF_SIZE, Sessions.LITE_READ_BUF_SIZE,
                        java.nio.charset.StandardCharsets.UTF_8)
                : head;
        return new Sessions.LiteSessionFile(mtime, size, head, tail);
    }

    /**
     * Best-effort mtime: the {@code timestamp} of the last JSONL line, or the
     * current time when that line has none or it does not parse. Only the last
     * line is consulted, as in Python's {@code _mtime_from_jsonl_tail}.
     */
    static long mtimeFromJsonlTail(String jsonl) {
        String trimmed = jsonl.stripTrailing();
        String lastLine = trimmed.substring(trimmed.lastIndexOf('\n') + 1);
        try {
            @SuppressWarnings("null")
            Object obj = MAPPER.readValue(lastLine, Object.class);
            if (obj instanceof Map<?, ?> m) {
                Long ms = isoToEpochMs(m.get("timestamp"));
                if (ms != null) {
                    return ms;
                }
            }
        } catch (JsonProcessingException ignored) {
            // Fall through to now.
        }
        return System.currentTimeMillis();
    }

    /**
     * Parse an ISO-8601 timestamp to epoch milliseconds, or {@code null} when
     * {@code ts} is not a string or does not parse.
     *
     * <p>Accepts a {@code Z} or numeric offset, and also a timestamp with no
     * offset at all (date-time or bare date), which Python's
     * {@code datetime.fromisoformat} accepts too. Python then reads such a
     * naive value in the host's local zone; this reads it as UTC instead, so
     * the result does not depend on the machine. The CLI always writes
     * {@code Z} timestamps, so the difference only touches hand-written entries.
     */
    public static @Nullable Long isoToEpochMs(@Nullable Object ts) {
        if (!(ts instanceof String s)) {
            return null;
        }
        String norm = s.endsWith("Z") ? s.substring(0, s.length() - 1) + "+00:00" : s;
        try {
            return java.time.OffsetDateTime.parse(norm).toInstant().toEpochMilli();
        } catch (java.time.format.DateTimeParseException ignored) {
            // Try the offset-less forms below.
        }
        try {
            return java.time.LocalDateTime.parse(norm)
                    .toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        } catch (java.time.format.DateTimeParseException ignored) {
            // Try a bare date.
        }
        try {
            return java.time.LocalDate.parse(norm)
                    .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (java.time.format.DateTimeParseException ignored) {
            return null;
        }
    }

    private static List<Map<String, Object>> filterTranscriptEntries(List<SessionStoreEntry> entries) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SessionStoreEntry e : entries) {
            String type = e.type();
            if (type != null && TRANSCRIPT_ENTRY_TYPES.contains(type) && e.uuid() != null) {
                result.add(new LinkedHashMap<>(e.asMap()));
            }
        }
        return result;
    }

    private static List<SessionMessage> entriesToSessionMessages(
            List<Map<String, Object>> entries, @Nullable Integer limit, int offset) {
        List<Map<String, Object>> chain = Sessions.buildConversationChain(entries);
        List<SessionMessage> messages = new ArrayList<>();
        for (Map<String, Object> e : chain) {
            if (Sessions.isVisibleMessage(e)) {
                messages.add(Sessions.toSessionMessage(e));
            }
        }
        // Offset first, then limit, as on the local-disk path and in Python.
        return Sessions.applyLimitOffset(messages, limit, offset);
    }

    private static List<SessionMessage> entriesToSubagentMessages(
            List<Map<String, Object>> entries, @Nullable Integer limit, int offset,
            Sessions.ParentIds parentIds) {
        List<Map<String, Object>> chain = Sessions.buildSubagentChain(entries);
        List<SessionMessage> messages = new ArrayList<>();
        for (Map<String, Object> e : chain) {
            String type = (String) e.get("type");
            if ("user".equals(type) || "assistant".equals(type)) {
                // Every message in a subagent transcript shares the same
                // parent ids.
                messages.add(Sessions.toSessionMessage(
                        e, parentIds.toolUseId(), parentIds.parentAgentId()));
            }
        }
        // Offset first, then limit, as on the local-disk path and in Python.
        return Sessions.applyLimitOffset(messages, limit, offset);
    }

    /**
     * Mirror the disk path's head/tail title scan over parsed entry objects.
     *
     * <p>Last occurrence wins for both {@code customTitle} and {@code aiTitle};
     * {@code customTitle} beats {@code aiTitle}; the first user prompt is the
     * final fallback, taken with the disk path's head extractor over the
     * entries re-serialized as JSONL so skip patterns and truncation match.
     * Mirrors Python's {@code _derive_title_from_entries}.
     */
    @Nullable
    static String deriveTitleFromEntries(List<Map<String, Object>> raw) {
        String custom = null;
        String ai = null;
        for (Map<String, Object> e : raw) {
            Object ct = e.get("customTitle");
            if (ct instanceof String s && !s.isEmpty()) {
                custom = s;
            }
            Object at = e.get("aiTitle");
            if (at instanceof String s && !s.isEmpty()) {
                ai = s;
            }
        }
        if (custom != null) {
            return custom;
        }
        if (ai != null) {
            return ai;
        }
        // Python serializes every entry here, not only the first 64 KB.
        StringBuilder jsonl = new StringBuilder();
        for (Map<String, Object> e : raw) {
            try {
                jsonl.append(MAPPER.writeValueAsString(e)).append('\n');
            } catch (JsonProcessingException ignored) {
                // An entry Jackson cannot write cannot hold the first prompt either.
            }
        }
        String firstPrompt = Sessions.extractFirstPromptFromHead(jsonl.toString());
        return firstPrompt == null || firstPrompt.isEmpty() ? null : firstPrompt;
    }

    private static String isoNow() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    /**
     * Internal helper that exposes the fork-line builder so tests/store
     * variants don't need to duplicate the transform.
     */
    static Path createTempJsonl(List<String> lines) throws IOException {
        Path tmp = Files.createTempFile("claude-fork-", ".jsonl");
        try {
            Files.writeString(tmp, String.join("\n", lines) + "\n",
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (FileAlreadyExistsException e) {
            // unreachable for createTempFile, but keep for clarity
            throw new IOException(e);
        }
        return tmp;
    }
}
