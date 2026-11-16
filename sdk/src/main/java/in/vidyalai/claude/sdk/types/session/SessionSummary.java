package in.vidyalai.claude.sdk.types.session;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

import in.vidyalai.claude.sdk.internal.FirstPrompt;
import in.vidyalai.claude.sdk.internal.SessionStores;
import in.vidyalai.claude.sdk.types.message.SDKSessionInfo;

/**
 * Helpers for incremental {@link SessionSummaryEntry} maintenance.
 *
 * <p>Mirrors the Python SDK's {@code session_summary} module: stores call
 * {@link #foldSessionSummary(SessionSummaryEntry, SessionKey, List)} from inside
 * {@link SessionStore#append(SessionKey, List)} to keep a per-session sidecar up
 * to date without re-reading the transcript. Stores then return the full
 * sidecar set from {@link SessionStore#listSessionSummaries(String)}.
 *
 * <p>All derived state lives in the opaque {@link SessionSummaryEntry#data()}
 * map; stores persist it verbatim and do not interpret it.
 *
 * <p>The {@code mtime} field is NOT touched by the fold — it is the sidecar's
 * storage write time and must be stamped by the adapter after persisting. For
 * a new session ({@code prev == null}) the fold returns {@code mtime=0} as a
 * placeholder; the adapter is expected to overwrite it.
 */
public final class SessionSummary {

    /** Map of JSONL entry keys to summary data keys for last-wins string fields. */
    private static final Map<String, String> LAST_WINS_FIELDS = Map.of(
            "customTitle", "custom_title",
            "aiTitle", "ai_title",
            "lastPrompt", "last_prompt",
            "summary", "summary_hint",
            "gitBranch", "git_branch");

    private SessionSummary() {
    }

    /**
     * Fold a batch of appended entries into the running summary for {@code key}.
     *
     * <p>Do not call this for keys with a {@code subpath} — subagent transcripts
     * must not contribute to the main session's summary. Guard with
     * {@code if (key.subpath() == null)} before calling.
     *
     * @param prev    the previous summary for the same key (or {@code null} for
     *                the first append)
     * @param key     the session key being appended to
     * @param entries the entries being appended
     * @return updated summary with {@code mtime=0} placeholder; the caller must
     *         stamp {@code mtime} with their storage write time before persisting.
     */
    public static SessionSummaryEntry foldSessionSummary(
            @Nullable SessionSummaryEntry prev,
            SessionKey key,
            List<SessionStoreEntry> entries) {

        Map<String, Object> data;
        String sessionId;
        long mtime;
        if (prev != null) {
            sessionId = prev.sessionId();
            mtime = prev.mtime();
            data = new LinkedHashMap<>(prev.data());
        } else {
            sessionId = key.sessionId();
            mtime = 0L;
            data = new LinkedHashMap<>();
        }

        for (SessionStoreEntry raw : entries) {
            Map<String, Object> entry = raw.asMap();

            Long ms = isoToEpochMs(entry.get("timestamp"));

            if (!data.containsKey("is_sidechain")) {
                data.put("is_sidechain", entry.get("isSidechain") == Boolean.TRUE);
            }
            if (!data.containsKey("created_at") && ms != null) {
                data.put("created_at", ms);
            }

            if (!data.containsKey("cwd")) {
                Object cwd = entry.get("cwd");
                if (cwd instanceof String s && !s.isEmpty()) {
                    data.put("cwd", s);
                }
            }

            foldFirstPrompt(data, entry);

            for (Map.Entry<String, String> mapping : LAST_WINS_FIELDS.entrySet()) {
                Object val = entry.get(mapping.getKey());
                if (val instanceof String s) {
                    data.put(mapping.getValue(), s);
                }
            }

            if ("tag".equals(entry.get("type"))) {
                Object tagVal = entry.get("tag");
                if (tagVal instanceof String s && !s.isEmpty()) {
                    data.put("tag", s);
                } else {
                    data.remove("tag");
                }
            }
        }

        return new SessionSummaryEntry(sessionId, mtime, data);
    }

    /**
     * Convert a {@link SessionSummaryEntry} to {@link SDKSessionInfo}.
     *
     * <p>Returns {@code null} for sidechain sessions or sessions with no
     * extractable summary, matching the disk path's lite-parse filter.
     */
    @Nullable
    public static SDKSessionInfo summaryEntryToSdkInfo(
            SessionSummaryEntry entry, @Nullable String projectPath) {
        Map<String, Object> data = entry.data();
        if (data.get("is_sidechain") == Boolean.TRUE) {
            return null;
        }

        Object firstPromptLocked = data.get("first_prompt_locked");
        Object firstPromptObj = firstPromptLocked == Boolean.TRUE
                ? data.get("first_prompt")
                : data.get("command_fallback");
        String firstPrompt = (firstPromptObj instanceof String s && !s.isEmpty()) ? s : null;

        String customTitle = nonEmpty(data, "custom_title");
        if (customTitle == null) {
            customTitle = nonEmpty(data, "ai_title");
        }

        String summary = customTitle;
        if (summary == null) {
            summary = nonEmpty(data, "last_prompt");
        }
        if (summary == null) {
            summary = nonEmpty(data, "summary_hint");
        }
        if (summary == null) {
            summary = firstPrompt;
        }
        if (summary == null) {
            return null;
        }

        Long createdAt = data.get("created_at") instanceof Number n ? n.longValue() : null;
        String gitBranch = nonEmpty(data, "git_branch");
        String cwd = nonEmpty(data, "cwd");
        if (cwd == null) {
            cwd = projectPath;
        }
        String tag = nonEmpty(data, "tag");

        return new SDKSessionInfo(
                entry.sessionId(),
                summary,
                entry.mtime(),
                null,
                customTitle,
                firstPrompt,
                gitBranch,
                cwd,
                tag,
                createdAt);
    }

    @Nullable
    private static String nonEmpty(Map<String, Object> data, String key) {
        Object v = data.get(key);
        return (v instanceof String s && !s.isEmpty()) ? s : null;
    }

    /**
     * Epoch ms for an ISO-8601 timestamp; one with no offset is read as UTC.
     * See {@link SessionStores#isoToEpochMs(Object)}.
     */
    @Nullable
    private static Long isoToEpochMs(@Nullable Object ts) {
        return SessionStores.isoToEpochMs(ts);
    }

    /** Python truthiness, for the JSON values a summary's data map can hold. */
    private static boolean isFalsy(@Nullable Object v) {
        if (v == null || Boolean.FALSE.equals(v)) {
            return true;
        }
        if (v instanceof String s) {
            return s.isEmpty();
        }
        if (v instanceof Number n) {
            return n.doubleValue() == 0.0;
        }
        if (v instanceof java.util.Collection<?> c) {
            return c.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return m.isEmpty();
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void foldFirstPrompt(Map<String, Object> data, Map<String, Object> entry) {
        if (data.get("first_prompt_locked") == Boolean.TRUE) {
            return;
        }
        if (!"user".equals(entry.get("type"))) {
            return;
        }
        if (entry.get("isMeta") == Boolean.TRUE || entry.get("isCompactSummary") == Boolean.TRUE) {
            return;
        }

        Object messageObj = entry.get("message");
        if (!(messageObj instanceof Map<?, ?> message)) {
            return;
        }
        Object content = ((Map<String, Object>) message).get("content");

        // Skip tool_result-carrying user messages.
        if (content instanceof List<?> list) {
            for (Object b : list) {
                if (b instanceof Map<?, ?> bm && "tool_result".equals(bm.get("type"))) {
                    return;
                }
            }
        }

        // Same per-text rules (skip pattern, command fallback, truncation) as
        // the disk path's head scan: one shared implementation.
        String result = FirstPrompt.fromTexts(FirstPrompt.textBlocks(content), name -> {
            // Falsy, not absent: a stored "" (or null) is replaced, as in
            // Python's `if not data.get("command_fallback")`.
            if (isFalsy(data.get("command_fallback"))) {
                data.put("command_fallback", name);
            }
        });
        if (result != null) {
            data.put("first_prompt", result);
            data.put("first_prompt_locked", Boolean.TRUE);
        }
    }

    // Suppress unused warning for shared fields-extracted-by-helpers.
    @SuppressWarnings("unused")
    private static final Map<String, Object> EMPTY_DATA = new HashMap<>();
    @SuppressWarnings("unused")
    private static final List<String> EMPTY_LIST = Arrays.asList();

}
