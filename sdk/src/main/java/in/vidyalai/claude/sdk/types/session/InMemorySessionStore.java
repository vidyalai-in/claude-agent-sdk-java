package in.vidyalai.claude.sdk.types.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * In-memory {@link SessionStore} implementation for testing and development.
 *
 * <p>The static helper {@link #filePathToSessionKey(String, String)} is the
 * Java equivalent of Python's {@code file_path_to_session_key} — it derives a
 * {@link SessionKey} from an on-disk transcript path so the mirror batcher can
 * route ``transcript_mirror`` frames to the right key.
 *
 * <p>Stores entries in a map keyed by a composite
 * {@code projectKey/sessionId} string (with an optional {@code /subpath}
 * suffix). Not suitable for production — data is lost when the process exits.
 *
 * <p>Maintains an incremental {@link SessionSummaryEntry} sidecar inside
 * {@link #append(SessionKey, List)} so {@link #listSessionSummaries(String)}
 * never re-reads. Subagent subpaths don't contribute to the main session's
 * summary.
 *
 * <p>This class is thread-safe via internal synchronization. Concurrent
 * appends to the same key are serialized to keep the summary fold consistent.
 */
public final class InMemorySessionStore implements SessionStore {

    private final Map<String, List<SessionStoreEntry>> store = new HashMap<>();
    private final Map<String, Long> mtimes = new HashMap<>();
    private final Map<String, SessionSummaryEntry> summaries = new HashMap<>();
    private long lastMtime = 0L;

    private static String keyToString(SessionKey key) {
        StringBuilder sb = new StringBuilder(key.projectKey()).append('/').append(key.sessionId());
        if (key.subpath() != null) {
            sb.append('/').append(key.subpath());
        }
        return sb.toString();
    }

    private static String summaryKey(String projectKey, String sessionId) {
        return projectKey + "/" + sessionId;
    }

    /**
     * Storage write time for this adapter, in Unix epoch ms.
     *
     * <p>Guaranteed strictly monotonically increasing across calls within the
     * process so back-to-back appends always produce distinct mtimes.
     */
    private synchronized long nextMtime() {
        long nowMs = System.currentTimeMillis();
        if (nowMs <= lastMtime) {
            nowMs = lastMtime + 1;
        }
        lastMtime = nowMs;
        return nowMs;
    }

    @Override
    public synchronized void append(SessionKey key, List<SessionStoreEntry> entries) {
        String k = keyToString(key);
        store.computeIfAbsent(k, ignored -> new ArrayList<>()).addAll(entries);
        long nowMs = nextMtime();
        if (key.subpath() == null) {
            String sk = summaryKey(key.projectKey(), key.sessionId());
            SessionSummaryEntry folded = SessionSummary.foldSessionSummary(
                    summaries.get(sk), key, entries);
            // Stamp with this adapter's storage write time so the staleness
            // check in list-from-store sees fresh sidecars.
            summaries.put(sk, new SessionSummaryEntry(folded.sessionId(), nowMs, folded.data()));
        }
        mtimes.put(k, nowMs);
    }

    @Nullable
    @Override
    public synchronized List<SessionStoreEntry> load(SessionKey key) {
        List<SessionStoreEntry> entries = store.get(keyToString(key));
        return entries == null ? null : new ArrayList<>(entries);
    }

    @Override
    public synchronized List<SessionStoreListEntry> listSessions(String projectKey) {
        List<SessionStoreListEntry> results = new ArrayList<>();
        String prefix = projectKey + "/";
        for (Map.Entry<String, List<SessionStoreEntry>> entry : store.entrySet()) {
            String k = entry.getKey();
            if (!k.startsWith(prefix)) {
                continue;
            }
            String rest = k.substring(prefix.length());
            // Only include main transcripts (no subpath, so no second '/')
            if (!rest.contains("/")) {
                results.add(new SessionStoreListEntry(rest, mtimes.getOrDefault(k, 0L)));
            }
        }
        return results;
    }

    @Override
    public synchronized List<SessionSummaryEntry> listSessionSummaries(String projectKey) {
        String prefix = projectKey + "/";
        List<SessionSummaryEntry> results = new ArrayList<>();
        for (Map.Entry<String, SessionSummaryEntry> e : summaries.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                results.add(e.getValue());
            }
        }
        return results;
    }

    @Override
    public synchronized void delete(SessionKey key) {
        String k = keyToString(key);
        store.remove(k);
        mtimes.remove(k);
        // Deleting the main transcript cascades to subkeys (subagent
        // transcripts, metadata) so they aren't orphaned.
        if (key.subpath() == null) {
            summaries.remove(summaryKey(key.projectKey(), key.sessionId()));
            String prefix = key.projectKey() + "/" + key.sessionId() + "/";
            List<String> toRemove = new ArrayList<>();
            for (String storeKey : store.keySet()) {
                if (storeKey.startsWith(prefix)) {
                    toRemove.add(storeKey);
                }
            }
            for (String r : toRemove) {
                store.remove(r);
                mtimes.remove(r);
            }
        }
    }

    @Override
    public synchronized List<String> listSubkeys(SessionListSubkeysKey key) {
        String prefix = key.projectKey() + "/" + key.sessionId() + "/";
        List<String> results = new ArrayList<>();
        for (String k : store.keySet()) {
            if (k.startsWith(prefix)) {
                results.add(k.substring(prefix.length()));
            }
        }
        return results;
    }

    @Override
    public boolean implementsListSessions() {
        return true;
    }

    @Override
    public boolean implementsListSessionSummaries() {
        return true;
    }

    @Override
    public boolean implementsDelete() {
        return true;
    }

    @Override
    public boolean implementsListSubkeys() {
        return true;
    }

    // --------------------------------------------------------------------
    // Test helpers
    // --------------------------------------------------------------------

    /**
     * Test helper — returns all entries for {@code key} (empty list if absent).
     */
    public synchronized List<SessionStoreEntry> getEntries(SessionKey key) {
        List<SessionStoreEntry> entries = store.get(keyToString(key));
        return entries == null ? List.of() : new ArrayList<>(entries);
    }

    /**
     * Test helper — number of stored sessions (main transcripts only).
     */
    public synchronized int size() {
        int count = 0;
        for (String k : store.keySet()) {
            int firstSlash = k.indexOf('/');
            if (firstSlash != -1 && k.indexOf('/', firstSlash + 1) == -1) {
                count++;
            }
        }
        return count;
    }

    /**
     * Test helper — clears all stored data.
     */
    public synchronized void clear() {
        store.clear();
        mtimes.clear();
        summaries.clear();
        lastMtime = 0L;
    }

    /**
     * Test helper — returns a snapshot of all summary entries keyed by
     * {@code projectKey/sessionId}. Useful for assertions.
     */
    public synchronized Map<String, SessionSummaryEntry> snapshotSummaries() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(summaries));
    }

    /**
     * Derive a {@link SessionKey} from an absolute transcript file path.
     *
     * <p>Main transcripts: {@code <projectsDir>/<projectKey>/<sessionId>.jsonl}<br>
     * Subagent transcripts: {@code <projectsDir>/<projectKey>/<sessionId>/subagents/agent-<id>.jsonl}
     *
     * <p>Returns {@code null} if {@code filePath} is not under
     * {@code projectsDir} or has an unrecognized shape. Subpaths are
     * always {@code /}-joined regardless of the host OS separator so keys are
     * portable across platforms.
     *
     * <p>Mirrors Python's {@code file_path_to_session_key}.
     */
    public static @org.jspecify.annotations.Nullable SessionKey filePathToSessionKey(
            String filePath, String projectsDir) {
        java.nio.file.Path file;
        java.nio.file.Path projects;
        try {
            file = java.nio.file.Path.of(filePath).toAbsolutePath().normalize();
            projects = java.nio.file.Path.of(projectsDir).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
        java.nio.file.Path rel;
        try {
            rel = projects.relativize(file);
        } catch (IllegalArgumentException e) {
            // Different roots (Windows: different drives)
            return null;
        }
        if (rel.toString().isEmpty()) {
            return null;
        }

        java.util.List<String> parts = new java.util.ArrayList<>();
        for (int i = 0; i < rel.getNameCount(); i++) {
            String p = rel.getName(i).toString();
            parts.add(p);
        }
        if (parts.isEmpty() || "..".equals(parts.get(0))) {
            return null;
        }
        if (parts.size() < 2) {
            return null;
        }

        String projectKey = parts.get(0);
        String second = parts.get(1);

        // Main transcript: <projectKey>/<sessionId>.jsonl
        if (parts.size() == 2 && second.endsWith(".jsonl")) {
            return new SessionKey(projectKey, second.substring(0, second.length() - 6), null);
        }

        // Subagent transcript: <projectKey>/<sessionId>/subagents/.../agent-<id>.jsonl
        if (parts.size() >= 4) {
            java.util.List<String> subpathParts = new java.util.ArrayList<>(parts.subList(2, parts.size()));
            String last = subpathParts.get(subpathParts.size() - 1);
            if (last.endsWith(".jsonl")) {
                subpathParts.set(subpathParts.size() - 1, last.substring(0, last.length() - 6));
            }
            return new SessionKey(projectKey, second, String.join("/", subpathParts));
        }

        return null;
    }

}
