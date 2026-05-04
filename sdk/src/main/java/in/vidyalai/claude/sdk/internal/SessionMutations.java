package in.vidyalai.claude.sdk.internal;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.types.message.ForkSessionResult;

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

    /**
     * Delete a session by removing its JSONL file and subagent transcripts.
     *
     * <p>
     * This is a hard delete — the {@code {sessionId}.jsonl} file is removed
     * permanently, along with the sibling {@code {sessionId}/} subdirectory
     * that holds subagent transcripts (if it exists). SDK users who need
     * soft-delete semantics can use
     * {@code tagSession(id, "__hidden", directory)} and filter on listing
     * instead.
     *
     * @param sessionId the UUID of the session to delete
     * @param directory project directory path (same semantics as
     *                  {@code listSessions(directory=...)}). When {@code null}, all
     *                  project directories are searched for the session file.
     * @throws IllegalArgumentException if {@code sessionId} is not a valid UUID.
     * @throws FileNotFoundException    if the session file cannot be found.
     * @throws IOException              if the delete fails.
     */
    public static void deleteSession(String sessionId, @Nullable String directory)
            throws IOException {
        if (!isValidUuid(sessionId)) {
            throw new IllegalArgumentException("Invalid session_id: " + sessionId);
        }

        Path path = findSessionFile(sessionId, directory);
        if (path == null) {
            throw new FileNotFoundException(
                    "Session " + sessionId + " not found"
                            + (directory != null ? " in project directory for " + directory : ""));
        }
        try {
            Files.delete(path);
        } catch (java.nio.file.NoSuchFileException e) {
            throw new FileNotFoundException("Session " + sessionId + " not found");
        }
        // Subagent transcripts live in a sibling {sessionId}/ dir; often absent.
        Path subagentDir = path.resolveSibling(sessionId);
        deleteRecursivelyIgnoreErrors(subagentDir);
    }

    private static void deleteRecursivelyIgnoreErrors(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort
                        }
                    });
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * Fork a session into a new branch with fresh UUIDs.
     *
     * <p>
     * Copies transcript messages from the source session into a new session
     * file, remapping every message UUID and preserving the {@code parentUuid}
     * chain. Supports {@code upToMessageId} for branching from a specific
     * point in the conversation.
     *
     * <p>
     * Forked sessions start without undo history (file-history snapshots are
     * not copied).
     *
     * @param sessionId     UUID of the source session to fork.
     * @param directory     project directory path (same semantics as
     *                      {@code listSessions(directory=...)}). When
     *                      {@code null}, all project directories are searched.
     * @param upToMessageId slice transcript up to this message UUID
     *                      (inclusive). If {@code null}, copies the full
     *                      transcript.
     * @param title         custom title for the fork. If {@code null}, derives
     *                      from the original title + " (fork)".
     * @return {@link ForkSessionResult} with the new session's UUID.
     * @throws IllegalArgumentException if {@code sessionId} or
     *                                  {@code upToMessageId} is not a valid
     *                                  UUID.
     * @throws FileNotFoundException    if the source session file cannot be
     *                                  found.
     * @throws IllegalStateException    if the session has no messages to fork, or
     *                                  if {@code upToMessageId} is not found in
     *                                  the transcript.
     * @throws IOException              if the read or write fails.
     */
    public static ForkSessionResult forkSession(String sessionId,
            @Nullable String directory, @Nullable String upToMessageId,
            @Nullable String title) throws IOException {
        if (!isValidUuid(sessionId)) {
            throw new IllegalArgumentException("Invalid session_id: " + sessionId);
        }
        if (upToMessageId != null && !isValidUuid(upToMessageId)) {
            throw new IllegalArgumentException("Invalid up_to_message_id: " + upToMessageId);
        }

        Path[] source = findSessionFileWithDir(sessionId, directory);
        if (source == null) {
            throw new FileNotFoundException(
                    "Session " + sessionId + " not found"
                            + (directory != null ? " in project directory for " + directory : ""));
        }
        Path filePath = source[0];
        Path projectDir = source[1];

        byte[] contentBytes = Files.readAllBytes(filePath);
        if (contentBytes.length == 0) {
            throw new IllegalStateException(
                    "Session " + sessionId + " has no messages to fork");
        }
        String content = new String(contentBytes, StandardCharsets.UTF_8);

        ForkTranscriptResult parsed = parseForkTranscript(content, sessionId);
        List<Map<String, Object>> transcript = parsed.transcript;
        List<Object> contentReplacements = parsed.contentReplacements;

        BuildForkLinesResult result = buildForkLines(
                transcript, contentReplacements, sessionId, upToMessageId, title,
                () -> deriveTitleFromBytes(contentBytes));

        // Write new session file
        Path forkPath = projectDir.resolve(result.forkedSessionId + ".jsonl");
        String forkContent = String.join("\n", result.lines) + "\n";
        Files.writeString(forkPath, forkContent,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);

        return new ForkSessionResult(result.forkedSessionId);
    }

    /**
     * Result of {@link #buildForkLines}. Package-private so {@link SessionStores} can
     * reuse the fork transform without going through disk I/O.
     */
    static final class BuildForkLinesResult {
        final String forkedSessionId;
        final List<String> lines;

        BuildForkLinesResult(String forkedSessionId, List<String> lines) {
            this.forkedSessionId = forkedSessionId;
            this.lines = lines;
        }
    }

    /**
     * Core fork transform — remap UUIDs and produce serialized JSONL lines.
     *
     * <p>Shared by the filesystem path ({@link #forkSession}) and the
     * {@link SessionStore}-backed path ({@link SessionStores#forkSessionViaStore}).
     * Each line is a compact JSON string without a trailing newline.
     *
     * @param deriveTitle invoked only when no explicit {@code title} is given,
     *                    so callers don't pay for the derivation when not needed.
     */
    static BuildForkLinesResult buildForkLines(
            List<Map<String, Object>> transcript,
            List<Object> contentReplacements,
            String sessionId,
            @Nullable String upToMessageId,
            @Nullable String title,
            java.util.function.Supplier<String> deriveTitle) throws IOException {

        // Filter out sidechains
        transcript = transcript.stream()
                .filter(e -> !Boolean.TRUE.equals(e.get("isSidechain")))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        if (transcript.isEmpty()) {
            throw new IllegalStateException(
                    "Session " + sessionId + " has no messages to fork");
        }

        if (upToMessageId != null) {
            int cutoff = -1;
            for (int i = 0; i < transcript.size(); i++) {
                if (upToMessageId.equals(transcript.get(i).get("uuid"))) {
                    cutoff = i;
                    break;
                }
            }
            if (cutoff == -1) {
                throw new IllegalStateException(
                        "Message " + upToMessageId + " not found in session " + sessionId);
            }
            transcript = new ArrayList<>(transcript.subList(0, cutoff + 1));
        }

        Map<String, String> uuidMapping = new LinkedHashMap<>();
        for (Map<String, Object> entry : transcript) {
            uuidMapping.put((String) entry.get("uuid"), UUID.randomUUID().toString());
        }

        List<Map<String, Object>> writable = transcript.stream()
                .filter(e -> !"progress".equals(e.get("type")))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        if (writable.isEmpty()) {
            throw new IllegalStateException(
                    "Session " + sessionId + " has no messages to fork");
        }

        Map<String, Map<String, Object>> byUuid = new LinkedHashMap<>();
        for (Map<String, Object> entry : transcript) {
            byUuid.put((String) entry.get("uuid"), entry);
        }

        String forkedSessionId = UUID.randomUUID().toString();
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                .replace("+00:00", "Z");

        List<String> lines = new ArrayList<>();

        for (int i = 0; i < writable.size(); i++) {
            Map<String, Object> original = writable.get(i);
            String newUuid = uuidMapping.get(original.get("uuid"));

            String newParentUuid = null;
            String parentId = (String) original.get("parentUuid");
            while (parentId != null) {
                Map<String, Object> parent = byUuid.get(parentId);
                if (parent == null) {
                    break;
                }
                if (!"progress".equals(parent.get("type"))) {
                    newParentUuid = uuidMapping.get(parentId);
                    break;
                }
                parentId = (String) parent.get("parentUuid");
            }

            String timestamp = (i == writable.size() - 1)
                    ? now
                    : (String) original.getOrDefault("timestamp", now);

            String logicalParent = (String) original.get("logicalParentUuid");
            String newLogicalParent = logicalParent != null
                    ? uuidMapping.get(logicalParent)
                    : null;

            Map<String, Object> forked = new LinkedHashMap<>(original);
            forked.put("uuid", newUuid);
            forked.put("parentUuid", newParentUuid);
            forked.put("logicalParentUuid", newLogicalParent);
            forked.put("sessionId", forkedSessionId);
            forked.put("timestamp", timestamp);
            forked.put("isSidechain", false);
            forked.put("forkedFrom", Map.of(
                    "sessionId", sessionId,
                    "messageUuid", original.get("uuid")));

            for (String key : List.of("teamName", "agentName", "slug", "sourceToolAssistantUUID")) {
                forked.remove(key);
            }

            lines.add(MAPPER.writeValueAsString(forked));
        }

        if (!contentReplacements.isEmpty()) {
            Map<String, Object> crEntry = new LinkedHashMap<>();
            crEntry.put("type", "content-replacement");
            crEntry.put("sessionId", forkedSessionId);
            crEntry.put("replacements", contentReplacements);
            crEntry.put("uuid", UUID.randomUUID().toString());
            crEntry.put("timestamp", now);
            lines.add(MAPPER.writeValueAsString(crEntry));
        }

        String forkTitle = (title != null) ? title.strip() : null;
        if (forkTitle == null || forkTitle.isEmpty()) {
            String base = deriveTitle.get();
            if (base == null || base.isEmpty()) {
                base = "Forked session";
            }
            forkTitle = base + " (fork)";
        }

        Map<String, Object> titleEntry = new LinkedHashMap<>();
        titleEntry.put("type", "custom-title");
        titleEntry.put("sessionId", forkedSessionId);
        titleEntry.put("customTitle", forkTitle);
        titleEntry.put("uuid", UUID.randomUUID().toString());
        titleEntry.put("timestamp", now);
        lines.add(MAPPER.writeValueAsString(titleEntry));

        return new BuildForkLinesResult(forkedSessionId, lines);
    }

    /**
     * Title derivation for the disk path — head/tail byte scan over the
     * source JSONL file. Mirrors the Python SDK's {@code _derive_title} closure.
     */
    private static @Nullable String deriveTitleFromBytes(byte[] contentBytes) {
        int bufLen = contentBytes.length;
        int headLen = Math.min(bufLen, Sessions.LITE_READ_BUF_SIZE);
        String head = new String(contentBytes, 0, headLen, StandardCharsets.UTF_8);
        int tailStart = Math.max(0, bufLen - Sessions.LITE_READ_BUF_SIZE);
        String tail = new String(contentBytes, tailStart,
                bufLen - tailStart, StandardCharsets.UTF_8);

        String base = Sessions.extractLastJsonStringField(tail, "customTitle");
        if (base == null) {
            base = Sessions.extractLastJsonStringField(head, "customTitle");
        }
        if (base == null) {
            base = Sessions.extractLastJsonStringField(tail, "aiTitle");
        }
        if (base == null) {
            base = Sessions.extractLastJsonStringField(head, "aiTitle");
        }
        if (base == null) {
            base = Sessions.extractFirstPromptFromHead(head);
        }
        if (base == null || base.isEmpty()) {
            return null;
        }
        return base;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Transcript types valid for fork operations.
     */
    private static final Set<String> TRANSCRIPT_TYPES = Set.of(
            "user", "assistant", "attachment", "system", "progress");

    /**
     * Result of parsing a session's JSONL for fork.
     */
    private static class ForkTranscriptResult {
        final List<Map<String, Object>> transcript;
        final List<Object> contentReplacements;

        ForkTranscriptResult(List<Map<String, Object>> transcript,
                List<Object> contentReplacements) {
            this.transcript = transcript;
            this.contentReplacements = contentReplacements;
        }
    }

    /**
     * Parses JSONL content into transcript entries + content-replacement records.
     */
    @SuppressWarnings({ "unchecked", "null" })
    private static ForkTranscriptResult parseForkTranscript(String content, String sessionId) {
        List<Map<String, Object>> transcript = new ArrayList<>();
        List<Object> contentReplacements = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    Map<String, Object> entry = MAPPER.readValue(line, Map.class);
                    String entryType = (String) entry.get("type");
                    if (TRANSCRIPT_TYPES.contains(entryType)
                            && entry.get("uuid") instanceof String) {
                        transcript.add(entry);
                    } else if ("content-replacement".equals(entryType)
                            && sessionId.equals(entry.get("sessionId"))
                            && entry.get("replacements") instanceof List<?> replacements) {
                        contentReplacements.addAll(replacements);
                    }
                } catch (Exception e) {
                    // Skip corrupt lines
                }
            }
        } catch (IOException e) {
            // StringReader never throws
        }

        return new ForkTranscriptResult(transcript, contentReplacements);
    }

    /**
     * Finds a session's JSONL file path.
     *
     * @return the path if found, {@code null} otherwise
     */
    private static @Nullable Path findSessionFile(String sessionId,
            @Nullable String directory) {
        Path[] result = findSessionFileWithDir(sessionId, directory);
        return result != null ? result[0] : null;
    }

    /**
     * Finds a session file and its containing project directory.
     *
     * @return {@code {filePath, projectDir}} or {@code null}
     */
    private static Path @Nullable [] findSessionFileWithDir(String sessionId,
            @Nullable String directory) {
        String fileName = sessionId + ".jsonl";

        if (directory != null) {
            Sessions.sanitizePath(directory);
            Path projectDir = Sessions.findProjectDir(directory);
            if (projectDir != null) {
                Path[] result = tryDir(projectDir, fileName);
                if (result != null) {
                    return result;
                }
            }

            // Worktree fallback
            try {
                List<String> worktreePaths = Sessions.getWorktreePaths(directory);
                for (String wt : worktreePaths) {
                    if (wt.equals(directory)) {
                        continue;
                    }
                    Path wtProjectDir = Sessions.findProjectDir(wt);
                    if (wtProjectDir != null) {
                        Path[] result = tryDir(wtProjectDir, fileName);
                        if (result != null) {
                            return result;
                        }
                    }
                }
            } catch (Exception e) {
                // Worktree detection failure is non-fatal
            }

            return null;
        }

        // No directory — search all project directories
        Path projectsDir = Sessions.getProjectsDir();
        if (!Files.exists(projectsDir)) {
            return null;
        }
        try (Stream<Path> dirs = Files.list(projectsDir)) {
            List<Path> dirList = dirs.filter(Files::isDirectory).toList();
            for (Path dir : dirList) {
                Path[] result = tryDir(dir, fileName);
                if (result != null) {
                    return result;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /**
     * Tries to find a non-empty session file in the given directory.
     */
    private static Path @Nullable [] tryDir(Path projectDir, String fileName) {
        Path path = projectDir.resolve(fileName);
        try {
            if (Files.exists(path) && Files.size(path) > 0) {
                return new Path[] { path, projectDir };
            }
        } catch (IOException e) {
            // Ignore
        }
        return null;
    }

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
