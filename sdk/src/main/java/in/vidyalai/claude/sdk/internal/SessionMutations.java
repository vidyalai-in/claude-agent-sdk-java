package in.vidyalai.claude.sdk.internal;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Internal implementation of session mutation functions (rename, tag).
 *
 * <p>
 * Mirrors the Python SDK's {@code _internal/session_mutations.py} module.
 * Ported from TypeScript SDK {@code sessionMutationsImpl.ts}.
 *
 * <p>
 * Rename/tag append typed metadata entries to the session's JSONL (matching
 * the CLI pattern). Safe to call from any SDK host process.
 *
 * <p>
 * Concurrent writers: if the target session is currently open in a CLI
 * process, the CLI's reAppendSessionMetadata() tail-reads before re-appending
 * its cached metadata. If an SDK write (e.g. a custom-title entry) is in the
 * tail scan window, the CLI absorbs it into its cache and re-appends the SDK
 * value — not the stale CLI value.
 */
public class SessionMutations {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Dangerous Unicode character ranges for sanitization
    private static final Pattern UNICODE_STRIP_RE = Pattern.compile(
            "[\u200b-\u200f\u202a-\u202e\u2066-\u2069\ufeff\ue000-\uf8ff]");

    private SessionMutations() {
    }

    /**
     * Rename a session by appending a custom-title entry.
     *
     * <p>
     * {@code listSessions} reads the LAST custom-title from the file tail, so
     * repeated calls are safe — the most recent wins.
     *
     * @param sessionId the UUID of the session to rename
     * @param title     new session title. Leading/trailing whitespace is stripped.
     *                  Must be non-empty after stripping.
     * @param directory project directory path (same semantics as
     *                  {@code listSessions(directory=...)}). When {@code null}, all
     *                  project directories are searched for the session file.
     * @throws IllegalArgumentException if {@code sessionId} is not a valid UUID, or
     *                                  if {@code title} is empty/whitespace-only.
     * @throws FileNotFoundException    if the session file cannot be found.
     * @throws IOException              if the write fails.
     */
    public static void renameSession(String sessionId, String title, @Nullable String directory)
            throws IOException {
        if (!isValidUuid(sessionId)) {
            throw new IllegalArgumentException("Invalid session_id: " + sessionId);
        }
        String stripped = title.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("title must be non-empty");
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "custom-title");
        entry.put("customTitle", stripped);
        entry.put("sessionId", sessionId);
        String data = MAPPER.writeValueAsString(entry) + "\n";

        appendToSession(sessionId, data, directory);
    }

    /**
     * Tag a session. Pass {@code null} to clear the tag.
     *
     * <p>
     * Appends a {@code {type:'tag',tag:<tag>,sessionId:<id>}} JSONL entry.
     * {@code listSessions} reads the LAST tag from the file tail — most recent
     * wins. Passing {@code null} appends an empty-string tag entry which
     * {@code listSessions} treats as {@code null} (cleared).
     *
     * <p>
     * Tags are Unicode-sanitized before storing (removes zero-width chars,
     * directional marks, private-use characters, etc.) for CLI filter
     * compatibility.
     *
     * @param sessionId the UUID of the session to tag
     * @param tag       tag string, or {@code null} to clear. Leading/trailing
     *                  whitespace is stripped. Must be non-empty after
     *                  sanitization and stripping (unless {@code null}).
     * @param directory project directory path (same semantics as
     *                  {@code listSessions(directory=...)}). When {@code null}, all
     *                  project directories are searched for the session file.
     * @throws IllegalArgumentException if {@code sessionId} is not a valid UUID, or
     *                                  if {@code tag} is empty/whitespace-only
     *                                  after
     *                                  sanitization.
     * @throws FileNotFoundException    if the session file cannot be found.
     * @throws IOException              if the write fails.
     */
    public static void tagSession(String sessionId, @Nullable String tag, @Nullable String directory)
            throws IOException {
        if (!isValidUuid(sessionId)) {
            throw new IllegalArgumentException("Invalid session_id: " + sessionId);
        }
        if (tag != null) {
            String sanitized = sanitizeUnicode(tag).strip();
            if (sanitized.isEmpty()) {
                throw new IllegalArgumentException("tag must be non-empty (use null to clear)");
            }
            tag = sanitized;
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "tag");
        entry.put("tag", tag != null ? tag : "");
        entry.put("sessionId", sessionId);
        String data = MAPPER.writeValueAsString(entry) + "\n";

        appendToSession(sessionId, data, directory);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static void appendToSession(String sessionId, String data, @Nullable String directory)
            throws IOException {
        String fileName = sessionId + ".jsonl";

        if (directory != null) {
            Sessions.sanitizePath(directory);
            // Use the path directly (not sanitized as a project dir)
            Path projectDir = Sessions.findProjectDir(directory);
            if (projectDir != null && tryAppend(projectDir.resolve(fileName), data)) {
                return;
            }
            throw new FileNotFoundException(
                    "Session " + sessionId + " not found in project directory for " + directory);
        }

        // No directory — search all project directories
        Path projectsDir = Sessions.getProjectsDir();
        if (!Files.exists(projectsDir)) {
            throw new FileNotFoundException("Session " + sessionId + " not found (no projects directory)");
        }

        try (Stream<Path> dirs = Files.list(projectsDir)) {
            List<Path> dirList = dirs.filter(Files::isDirectory).toList();
            for (Path dir : dirList) {
                if (tryAppend(dir.resolve(fileName), data)) {
                    return;
                }
            }
        }

        throw new FileNotFoundException("Session " + sessionId + " not found in any project directory");
    }

    /**
     * Try appending to a path. Opens with append mode (no creation), so fails if
     * file doesn't exist. Returns {@code true} on success, {@code false} if the
     * file doesn't exist or is empty (0-byte stub).
     */
    static boolean tryAppend(Path path, String data) {
        if (!Files.exists(path)) {
            return false;
        }
        try {
            long size = Files.size(path);
            if (size == 0) {
                return false; // 0-byte stub, keep searching
            }
            // Use RandomAccessFile for append
            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
                raf.seek(raf.length());
                raf.write(data.getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Unicode sanitization — ported from TS sanitization.ts / Python SDK
    // -------------------------------------------------------------------------

    // Unicode format/private-use categories
    private static final java.util.Set<Integer> FORMAT_TYPES = java.util.Set.of(
            (int) Character.FORMAT,
            (int) Character.PRIVATE_USE,
            (int) Character.UNASSIGNED);

    /**
     * Sanitize a string by removing dangerous Unicode characters.
     *
     * <p>
     * Ported from Python {@code _sanitize_unicode} / TS
     * {@code partiallySanitizeUnicode}.
     * Iteratively applies NFKC normalization and strips
     * format/private-use/unassigned
     * characters until no more changes occur (max 10 iterations).
     */
    static String sanitizeUnicode(String value) {
        String current = value;
        for (int i = 0; i < 10; i++) {
            String previous = current;
            // NFKC normalization
            current = Normalizer.normalize(current, Normalizer.Form.NFKC);
            // Strip Cf (format), Co (private use), Cn (unassigned) categories
            StringBuilder sb = new StringBuilder(current.length());
            for (int j = 0; j < current.length();) {
                int cp = current.codePointAt(j);
                int type = Character.getType(cp);
                if (!FORMAT_TYPES.contains(type)) {
                    sb.appendCodePoint(cp);
                }
                j += Character.charCount(cp);
            }
            current = sb.toString();
            // Explicit ranges (matches Python)
            current = UNICODE_STRIP_RE.matcher(current).replaceAll("");
            if (current.equals(previous)) {
                break;
            }
        }
        return current;
    }

    private static boolean isValidUuid(String value) {
        return value != null && value.matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

}
