package in.vidyalai.claude.sdk.internal;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
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

    private static final Pattern UNICODE_STRIP_RE = Pattern.compile(
            "[​-‏‪-‮⁦-⁩﻿-]");

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
        Path p = Path.of(dir != null ? dir : ".").toAbsolutePath().normalize();
        try {
            p = p.toRealPath();
        } catch (IOException ignored) {
            // Fall through to the absolute/normalized path.
        }
        return Normalizer.normalize(p.toString(), Normalizer.Form.NFC);
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
        String projectPath = canonicalizePath(directory);
        return deriveInfoViaLoad(sessionStore, sessionId, directory, projectPath, null);
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

        // Drop synthetic agent_metadata entries injected by the mirror hook —
        // they describe the .meta.json sidecar, not transcript lines.
        List<SessionStoreEntry> transcript = new ArrayList<>();
        for (SessionStoreEntry e : entries) {
            if (!"agent_metadata".equals(e.type())) {
                transcript.add(e);
            }
        }
        if (transcript.isEmpty()) {
            return List.of();
        }
        return entriesToSubagentMessages(filterTranscriptEntries(transcript), limit, offset);
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
            String sanitized = sanitizeUnicode(tag).strip();
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
            String entryType = (String) map.get("type");
            if (TRANSCRIPT_TYPES_FORK.contains(entryType) && map.get("uuid") instanceof String) {
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
                Map<String, Object> parsed = MAPPER.readValue(line, Map.class);
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

    @Nullable
    private static SDKSessionInfo deriveInfoViaLoad(
            SessionStore sessionStore,
            String sessionId,
            @Nullable String directory,
            @Nullable String projectPath,
            @Nullable Long knownMtime) {
        SessionKey key = new SessionKey(projectKeyForDirectory(directory), sessionId, null);
        List<SessionStoreEntry> entries;
        try {
            entries = sessionStore.load(key);
        } catch (RuntimeException e) {
            // Adapter errors degrade to an empty summary entry instead of failing the whole list.
            return knownMtime != null
                    ? new SDKSessionInfo(sessionId, "", knownMtime, null, null, null, null, projectPath, null, null)
                    : null;
        }
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        long mtime = knownMtime != null ? knownMtime : mtimeFromEntriesTail(entries);
        // Build the summary by folding entries — produces same fields as lite-parse path.
        SessionSummaryEntry folded = SessionSummary.foldSessionSummary(null, key, entries);
        SessionSummaryEntry stamped = new SessionSummaryEntry(folded.sessionId(), mtime, folded.data());
        return SessionSummary.summaryEntryToSdkInfo(stamped, projectPath);
    }

    /**
     * Derive {@link SDKSessionInfo} for each slot in {@code slots} via
     * concurrent {@code store.loadAsync()} calls, bounded by
     * {@link #STORE_LIST_LOAD_CONCURRENCY}. Returns a {@code sessionId →
     * SDKSessionInfo} map; sidechain/empty-summary sessions are absent.
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
        java.util.List<java.util.concurrent.CompletableFuture<Map.Entry<String, SDKSessionInfo>>> futures =
                new java.util.ArrayList<>(slots.size());

        for (Slot slot : slots) {
            futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    sem.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return java.util.Map.<String, SDKSessionInfo>entry(slot.sessionId(),
                            sentinelOnError(slot, projectPath));
                }
                try {
                    SessionKey key = new SessionKey(projectKey, slot.sessionId(), null);
                    List<SessionStoreEntry> entries;
                    try {
                        entries = sessionStore.loadAsync(key).get();
                    } catch (java.util.concurrent.ExecutionException ee) {
                        return java.util.Map.<String, SDKSessionInfo>entry(slot.sessionId(),
                                sentinelOnError(slot, projectPath));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return java.util.Map.<String, SDKSessionInfo>entry(slot.sessionId(),
                                sentinelOnError(slot, projectPath));
                    }
                    if (entries == null || entries.isEmpty()) {
                        return java.util.Map.<String, SDKSessionInfo>entry(slot.sessionId(), null);
                    }
                    long mtime = slot.mtime() != 0
                            ? slot.mtime()
                            : mtimeFromEntriesTail(entries);
                    SessionSummaryEntry folded = SessionSummary.foldSessionSummary(null, key, entries);
                    SessionSummaryEntry stamped = new SessionSummaryEntry(
                            folded.sessionId(), mtime, folded.data());
                    SDKSessionInfo info = SessionSummary.summaryEntryToSdkInfo(stamped, projectPath);
                    return java.util.Map.<String, SDKSessionInfo>entry(slot.sessionId(), info);
                } finally {
                    sem.release();
                }
            }, in.vidyalai.claude.sdk.types.session.SessionStoreExecutor.getDefault()));
        }

        Map<String, SDKSessionInfo> result = new java.util.LinkedHashMap<>();
        for (java.util.concurrent.CompletableFuture<Map.Entry<String, SDKSessionInfo>> f : futures) {
            try {
                Map.Entry<String, SDKSessionInfo> entry = f.get();
                if (entry.getValue() != null) {
                    result.put(entry.getKey(), entry.getValue());
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.util.concurrent.ExecutionException ee) {
                // already converted to sentinel above; skip silently
            }
        }
        return result;
    }

    @Nullable
    private static SDKSessionInfo sentinelOnError(Slot slot, @Nullable String projectPath) {
        return slot.mtime() != 0
                ? new SDKSessionInfo(slot.sessionId(), "", slot.mtime(),
                        null, null, null, null, projectPath, null, null)
                : null;
    }

    private static long mtimeFromEntriesTail(List<SessionStoreEntry> entries) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            String ts = entries.get(i).timestamp();
            if (ts != null) {
                try {
                    String norm = ts.endsWith("Z") ? ts.substring(0, ts.length() - 1) + "+00:00" : ts;
                    return java.time.OffsetDateTime.parse(norm).toInstant().toEpochMilli();
                } catch (java.time.format.DateTimeParseException ignored) {
                    // Try previous entry
                }
            }
        }
        return System.currentTimeMillis();
    }

    private static List<Map<String, Object>> filterTranscriptEntries(List<SessionStoreEntry> entries) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SessionStoreEntry e : entries) {
            if (TRANSCRIPT_ENTRY_TYPES.contains(e.type()) && e.uuid() != null) {
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
        if (limit != null && limit > 0 && messages.size() > limit) {
            messages = messages.subList(0, limit);
        }
        if (offset > 0) {
            if (offset >= messages.size()) {
                return List.of();
            }
            return new ArrayList<>(messages.subList(offset, messages.size()));
        }
        return messages;
    }

    private static List<SessionMessage> entriesToSubagentMessages(
            List<Map<String, Object>> entries, @Nullable Integer limit, int offset) {
        List<Map<String, Object>> chain = Sessions.buildSubagentChain(entries);
        List<SessionMessage> messages = new ArrayList<>();
        for (Map<String, Object> e : chain) {
            String type = (String) e.get("type");
            if ("user".equals(type) || "assistant".equals(type)) {
                messages.add(Sessions.toSessionMessage(e));
            }
        }
        if (limit != null && limit > 0 && messages.size() > limit) {
            messages = messages.subList(0, limit);
        }
        if (offset > 0) {
            if (offset >= messages.size()) {
                return List.of();
            }
            return new ArrayList<>(messages.subList(offset, messages.size()));
        }
        return messages;
    }

    @Nullable
    private static String deriveTitleFromEntries(List<Map<String, Object>> raw) {
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
        return null;
    }

    private static String isoNow() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    private static String sanitizeUnicode(String value) {
        String previous;
        String current = value;
        for (int i = 0; i < 10; i++) {
            previous = current;
            current = UNICODE_STRIP_RE.matcher(Normalizer.normalize(current, Normalizer.Form.NFC)).replaceAll("");
            if (current.equals(previous)) {
                break;
            }
        }
        return current;
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
