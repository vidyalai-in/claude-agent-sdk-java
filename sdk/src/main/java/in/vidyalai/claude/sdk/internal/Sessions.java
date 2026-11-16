package in.vidyalai.claude.sdk.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import in.vidyalai.claude.sdk.types.message.SDKSessionInfo;
import in.vidyalai.claude.sdk.types.message.SessionMessage;

/**
 * Internal implementation of session listing and message retrieval.
 * Mirrors the Python SDK's {@code _internal/sessions.py} module.
 *
 * <p>
 * This class is internal and not part of the public API.
 */
public class Sessions {

    static final int LITE_READ_BUF_SIZE = 65536; // 64 KB
    static final int MAX_SANITIZED_LENGTH = 200;

    private static final Pattern UUID_RE = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SANITIZE_RE = Pattern.compile("[^a-zA-Z0-9]");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parses one JSON value and rejects trailing tokens, so a line holding two
     * concatenated objects is skipped as Python's {@code json.loads} skips it.
     */
    private static final ObjectReader STRICT_READER = MAPPER.readerFor(Object.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Sessions() {
    }

    // -------------------------------------------------------------------------
    // UUID validation + strict JSON line parsing
    // -------------------------------------------------------------------------

    /** Whether {@code value} is a UUID (case-insensitive, like Python's {@code _UUID_RE}). */
    static boolean isValidUuid(@Nullable String value) {
        return value != null && UUID_RE.matcher(value).matches();
    }

    /**
     * Parses {@code text} as exactly one JSON value.
     *
     * @return the JSON object, or {@code null} when the text is not valid JSON,
     *         has trailing tokens, or is not an object
     */
    @SuppressWarnings("unchecked")
    static @Nullable Map<String, Object> parseJsonObjectLine(String text) {
        try {
            Object parsed = STRICT_READER.readValue(text);
            return (parsed instanceof Map<?, ?> map) ? (Map<String, Object>) map : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Path helpers
    // -------------------------------------------------------------------------

    static Path getClaudeConfigHomeDir() {
        String envVar = System.getenv("CLAUDE_CONFIG_DIR");
        if (envVar != null && !envVar.isEmpty()) {
            return Path.of(nfc(envVar));
        }
        return Path.of(nfc(Path.of(System.getProperty("user.home"), ".claude").toString()));
    }

    static Path getProjectsDir() {
        return getClaudeConfigHomeDir().resolve("projects");
    }

    /**
     * Resolves the projects directory honoring {@code env} overrides before
     * the process environment.
     *
     * <p>Mirrors Python's {@code _get_projects_dir(env_override)} so callers
     * that pass {@code CLAUDE_CONFIG_DIR} via {@code options.env} resolve to
     * the same directory the subprocess will write to.
     */
    public static Path getProjectsDirForEnv(@Nullable Map<String, String> env) {
        if (env != null) {
            String override = env.get("CLAUDE_CONFIG_DIR");
            if (override != null && !override.isEmpty()) {
                return Path.of(nfc(override)).resolve("projects");
            }
        }
        return getProjectsDir();
    }

    static Path getProjectDir(String path) {
        return getProjectsDir().resolve(sanitizePath(path));
    }

    private static String nfc(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    /**
     * Resolves a directory path to its canonical form, as Python's
     * {@code _canonicalize_path} does with {@code os.path.realpath} + NFC.
     *
     * <p>Like {@code realpath}, a path that does not exist (in whole or in
     * part) is not an error: the longest existing prefix has its symlinks
     * resolved and the remaining components are appended as written, with
     * {@code .} dropped and {@code ..} taken lexically.
     */
    static String canonicalizePath(String dir) {
        Path abs;
        try {
            abs = Path.of(dir).toAbsolutePath();
        } catch (RuntimeException | java.io.IOError e) {
            return nfc(dir);
        }
        Path resolved = abs.getRoot();
        if (resolved == null) {
            return nfc(abs.toString());
        }
        boolean missing = false;
        try {
            resolved = resolved.toRealPath();
        } catch (IOException e) {
            missing = true;
        }
        for (Path name : abs) {
            String component = name.toString();
            if (component.isEmpty() || component.equals(".")) {
                continue;
            }
            if (component.equals("..")) {
                Path parent = resolved.getParent();
                if (parent != null) {
                    resolved = parent;
                }
                continue;
            }
            Path next = resolved.resolve(component);
            if (!missing) {
                try {
                    next = next.toRealPath();
                } catch (IOException e) {
                    missing = true;
                }
            }
            resolved = next;
        }
        return nfc(resolved.toString());
    }

    /**
     * Finds the project directory for a given (canonical) path.
     *
     * <p>Mirrors Python's {@code _find_project_dir}: the exact sanitized name
     * when it is a directory. Only for paths whose sanitized form exceeds
     * {@value #MAX_SANITIZED_LENGTH} chars (where the CLI's hash suffix may
     * differ from ours) does it fall back to the first directory named
     * {@code sanitized[:200] + "-"}.
     */
    static @Nullable Path findProjectDir(String path) {
        Path exact = getProjectDir(path);
        if (Files.isDirectory(exact)) {
            return exact;
        }

        // Exact match failed: for short paths this means no sessions exist.
        // For long paths, try prefix matching to handle hash mismatches.
        String sanitized = sanitizePath(path);
        if (sanitized.length() <= MAX_SANITIZED_LENGTH) {
            return null;
        }

        String prefix = sanitized.substring(0, MAX_SANITIZED_LENGTH) + "-";
        try (Stream<Path> children = Files.list(getProjectsDir())) {
            return children
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .findFirst()
                    .orElse(null);
        } catch (IOException | UncheckedIOException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Path sanitization
    // -------------------------------------------------------------------------

    /**
     * Sanitizes a file-system path for use as a directory name.
     * Replaces non-alphanumeric characters with {@code -}, then truncates to
     * {@value #MAX_SANITIZED_LENGTH} characters appending a hash suffix if needed.
     */
    static String sanitizePath(String name) {
        String sanitized = SANITIZE_RE.matcher(name).replaceAll("-");
        if (sanitized.length() <= MAX_SANITIZED_LENGTH) {
            return sanitized;
        }
        String truncated = sanitized.substring(0, MAX_SANITIZED_LENGTH);
        String hashSuffix = simpleHash(name);
        return truncated + "-" + hashSuffix;
    }

    /**
     * 32-bit integer hash to base36, matching the CLI's directory naming:
     * {@code h = (h << 5) - h + c} with signed 32-bit overflow, then
     * {@code Math.abs(h).toString(36)}.
     *
     * <p>Iterates UTF-16 code units, as the JS implementation does with
     * {@code charCodeAt}. Python iterates code points, so the two differ only
     * for paths with characters outside the BMP. The absolute value is taken
     * in 64 bits so {@code Integer.MIN_VALUE} hashes to {@code "zik0zk"}, as in
     * JS and Python, not to a negative number.
     */
    static String simpleHash(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = (h << 5) - h + s.charAt(i);
        }
        return Long.toString(Math.abs((long) h), 36);
    }

    // -------------------------------------------------------------------------
    // Lightweight file read
    // -------------------------------------------------------------------------

    record LiteSessionFile(long mtime, long size, String head, String tail) {
    }

    /**
     * Opens a session file, stats it, and reads head + tail.
     *
     * @return {@code null} on any I/O error or when the file is empty (a
     *         0-byte stub means "not here, keep searching")
     */
    static @Nullable LiteSessionFile readSessionLite(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            long mtime = Files.getLastModifiedTime(file).toMillis();

            ByteBuffer headBuf = ByteBuffer.allocate((int) Math.min(Math.max(size, 0), LITE_READ_BUF_SIZE));
            int headRead = readFully(channel, headBuf, 0);
            if (headRead <= 0) {
                return null;
            }
            headBuf.flip();
            String head = newLenientDecoder().decode(headBuf).toString();

            String tail;
            long tailOffset = Math.max(0, size - LITE_READ_BUF_SIZE);
            if (tailOffset == 0) {
                tail = head;
            } else {
                ByteBuffer tailBuf = ByteBuffer.allocate(LITE_READ_BUF_SIZE);
                readFully(channel, tailBuf, tailOffset);
                tailBuf.flip();
                tail = newLenientDecoder().decode(tailBuf).toString();
            }

            return new LiteSessionFile(mtime, size, head, tail);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static int readFully(FileChannel channel, ByteBuffer buf, long position) throws IOException {
        int total = 0;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, position + total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    private static CharsetDecoder newLenientDecoder() {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    // -------------------------------------------------------------------------
    // JSON string field extraction: no full parse, works on truncated lines
    // -------------------------------------------------------------------------

    /**
     * Extracts the first occurrence of a JSON string field from raw text,
     * without a full parse, so it works on lines cut off at a buffer edge.
     *
     * <p>Matches {@code "key":"value"} and, more leniently than Python's
     * {@code _extract_json_string_field} (which accepts only {@code ":"} and
     * {@code ": "}), any whitespace around the colon. A value with no closing
     * quote is not a match.
     */
    static @Nullable String extractJsonStringField(String text, String key) {
        return extractJsonStringFieldInternal(text, key, false);
    }

    /**
     * Extracts the last occurrence of a JSON string field from raw text.
     *
     * <p>An occurrence whose value has no closing quote (a line cut off at the
     * end of a head/tail buffer) is ignored, so the last complete value wins,
     * as in Python's {@code _extract_last_json_string_field}.
     */
    static @Nullable String extractLastJsonStringField(String text, String key) {
        return extractJsonStringFieldInternal(text, key, true);
    }

    private static @Nullable String extractJsonStringFieldInternal(
            String text, String key, boolean last) {
        String searchKey = "\"" + key + "\"";
        int searchFrom = 0;
        String found = null;

        while (true) {
            int keyIdx = text.indexOf(searchKey, searchFrom);
            if (keyIdx < 0) {
                break;
            }
            searchFrom = keyIdx + 1;
            int afterKey = keyIdx + searchKey.length();

            // Find colon
            int colon = -1;
            for (int i = afterKey; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == ':') {
                    colon = i;
                    break;
                }
                if (!Character.isWhitespace(c)) {
                    break;
                }
            }
            if (colon < 0) {
                continue;
            }

            // Find opening quote
            int quote = -1;
            for (int i = colon + 1; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '"') {
                    quote = i;
                    break;
                }
                if (!Character.isWhitespace(c)) {
                    break;
                }
            }
            if (quote < 0) {
                continue;
            }

            // Read the value up to its unescaped closing quote
            int valueStart = quote + 1;
            int closing = -1;
            int i = valueStart;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    closing = i;
                    break;
                }
                i++;
            }
            if (closing < 0) {
                // Truncated value: not a match, and nothing can follow it.
                break;
            }

            found = unescapeJsonString(text.substring(valueStart, closing));
            if (!last) {
                break;
            }
            // Continue after the value, so a key-like string inside it is not matched.
            searchFrom = closing + 1;
        }

        return found;
    }


    /** Unescapes a JSON string value extracted as raw text; returns it as-is if it is not valid. */
    @SuppressWarnings("null")
    static String unescapeJsonString(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        try {
            String result = MAPPER.readValue("\"" + raw + "\"", String.class);
            return result != null ? result : raw;
        } catch (IOException | RuntimeException e) {
            return raw;
        }
    }

    // -------------------------------------------------------------------------
    // First prompt extraction
    // -------------------------------------------------------------------------

    /** See {@link FirstPrompt#extractFromHead(String)}. */
    static String extractFirstPromptFromHead(String head) {
        return FirstPrompt.extractFromHead(head);
    }

    /** {@code s} when it is non-empty, else {@code null}: Python's {@code s or None}. */
    private static @Nullable String nonEmpty(@Nullable String s) {
        return (s != null && !s.isEmpty()) ? s : null;
    }

    // -------------------------------------------------------------------------
    // Field extraction — shared by listSessions and getSessionInfo
    // -------------------------------------------------------------------------

    /**
     * Parses SDKSessionInfo fields from a lite session read (head/tail/stat).
     *
     * <p>
     * Returns null for sidechain sessions or metadata-only sessions with no
     * extractable summary.
     *
     * <p>
     * Shared by {@code readSessionsFromDir} and {@code getSessionInfo}.
     */
    static @Nullable SDKSessionInfo parseSessionInfoFromLite(
            String sessionId, LiteSessionFile lite, @Nullable String projectPath) {
        String head = lite.head();
        String tail = lite.tail();

        // Check first line for sidechain sessions
        int firstNewline = head.indexOf('\n');
        String firstLine = (firstNewline >= 0) ? head.substring(0, firstNewline) : head;
        if (firstLine.contains("\"isSidechain\":true") || firstLine.contains("\"isSidechain\": true")) {
            return null;
        }

        // User-set title (customTitle) wins over AI-generated title (aiTitle).
        // Head fallback covers short sessions where the title entry may not be in tail.
        // Every fallback chain below is Python's `or` chain: an empty string
        // falls through like a missing value.
        String customTitle = nonEmpty(extractLastJsonStringField(tail, "customTitle"));
        if (customTitle == null) {
            customTitle = nonEmpty(extractLastJsonStringField(head, "customTitle"));
        }
        if (customTitle == null) {
            customTitle = nonEmpty(extractLastJsonStringField(tail, "aiTitle"));
        }
        if (customTitle == null) {
            customTitle = nonEmpty(extractLastJsonStringField(head, "aiTitle"));
        }

        String firstPrompt = nonEmpty(extractFirstPromptFromHead(head));

        // Summary priority: custom title > lastPrompt > summary > first prompt
        String displaySummary = customTitle;
        if (displaySummary == null) {
            displaySummary = nonEmpty(extractLastJsonStringField(tail, "lastPrompt"));
        }
        if (displaySummary == null) {
            displaySummary = nonEmpty(extractLastJsonStringField(tail, "summary"));
        }
        if (displaySummary == null) {
            displaySummary = firstPrompt;
        }

        // Skip metadata-only sessions (no title, no summary, no prompt)
        if (displaySummary == null) {
            return null;
        }

        String gitBranch = nonEmpty(extractLastJsonStringField(tail, "gitBranch"));
        if (gitBranch == null) {
            gitBranch = nonEmpty(extractJsonStringField(head, "gitBranch"));
        }
        String cwd = nonEmpty(extractJsonStringField(head, "cwd"));
        if (cwd == null) {
            cwd = nonEmpty(projectPath);
        }

        // Tag extraction scoped to {"type":"tag"} lines to avoid matching
        // tool_use inputs (git tag, Docker tags, etc.)
        String tag = extractTagFromTail(tail);

        // created_at from the first ISO timestamp found in the head (epoch ms).
        // Scans the whole head rather than only firstLine because the first
        // record may be a metadata-only entry (e.g. permission-mode) with no
        // timestamp field; the first user/assistant record that follows does
        // carry one. Mirrors Python SDK fix #907.
        Long createdAt = extractCreatedAtFromFirstLine(head);

        return new SDKSessionInfo(
                sessionId,
                displaySummary,
                lite.mtime(),
                lite.size(),
                customTitle,
                firstPrompt,
                gitBranch,
                cwd,
                tag,
                createdAt);
    }

    /**
     * Extracts tag from tail by finding lines starting with {@code {"type":"tag"}
     * and extracting the last "tag" field value.
     */
    static @Nullable String extractTagFromTail(String tail) {
        String lastTagLine = null;
        String[] lines = tail.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].startsWith("{\"type\":\"tag\"")) {
                lastTagLine = lines[i];
                break;
            }
        }
        if (lastTagLine == null) {
            return null;
        }
        String tagValue = extractLastJsonStringField(lastTagLine, "tag");
        // Empty string tag (clear marker) resolves to null
        return (tagValue != null && !tagValue.isEmpty()) ? tagValue : null;
    }

    /**
     * Extracts the {@code created_at} epoch-ms from the first ISO timestamp
     * found in {@code text}. Callers pass the full head buffer rather than
     * just the first line, since the first JSONL record may be a metadata-only
     * entry (e.g. {@code permission-mode}) without a {@code timestamp} field;
     * the first user/assistant record that follows does carry one. Mirrors
     * Python SDK fix #907.
     */
    static @Nullable Long extractCreatedAtFromFirstLine(String text) {
        String timestamp = extractJsonStringField(text, "timestamp");
        if (timestamp == null) {
            return null;
        }
        try {
            // Handle trailing 'Z' by converting to +00:00 offset format
            String ts = timestamp.endsWith("Z")
                    ? timestamp.substring(0, timestamp.length() - 1) + "+00:00"
                    : timestamp;
            OffsetDateTime odt = OffsetDateTime.parse(ts);
            return odt.toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Session directory scanning
    // -------------------------------------------------------------------------

    static List<SDKSessionInfo> readSessionsFromDir(Path projectDir, @Nullable String projectPath) {
        List<SDKSessionInfo> sessions = new ArrayList<>();
        if (!Files.exists(projectDir)) {
            return sessions;
        }

        try (Stream<Path> files = Files.list(projectDir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".jsonl"))
                    .forEach(file -> {
                        String name = file.getFileName().toString();
                        String sessionId = name.substring(0, name.length() - 6);

                        if (!UUID_RE.matcher(sessionId).matches())
                            return;

                        LiteSessionFile lite = readSessionLite(file);
                        if (lite == null)
                            return;

                        SDKSessionInfo info = parseSessionInfoFromLite(sessionId, lite, projectPath);
                        if (info != null) {
                            sessions.add(info);
                        }
                    });
        } catch (IOException | UncheckedIOException e) {
            // Return what we have
        }

        return sessions;
    }

    // -------------------------------------------------------------------------
    // Worktree detection
    // -------------------------------------------------------------------------

    static List<String> getWorktreePaths(String cwd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "worktree", "list", "--porcelain");
            pb.directory(Path.of(cwd).toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            List<String> paths = Collections.synchronizedList(new ArrayList<>());

            Thread reader = Threads.start("Sessions-WorktreeReader-", () -> {
                try (var scanner = new java.util.Scanner(process.getInputStream(), StandardCharsets.UTF_8)) {
                    while (scanner.hasNextLine()) {
                        String ln = scanner.nextLine();
                        if (ln.startsWith("worktree ")) {
                            String wpath = ln.substring("worktree ".length()).trim();
                            paths.add(Normalizer.normalize(wpath, Normalizer.Form.NFC));
                        }
                    }
                }
            });

            boolean done = process.waitFor(5, TimeUnit.SECONDS);
            reader.join(5000);

            if (!done) {
                process.destroyForcibly();
                return List.of();
            }
            // Like Python: a failed `git worktree list` (not a repo) means no worktrees.
            if (process.exitValue() != 0) {
                return List.of();
            }

            return Collections.unmodifiableList(paths);
        } catch (Exception e) {
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Deduplication + sorting
    // -------------------------------------------------------------------------

    static List<SDKSessionInfo> deduplicateBySessionId(List<SDKSessionInfo> sessions) {
        Map<String, SDKSessionInfo> best = new LinkedHashMap<>();
        for (SDKSessionInfo s : sessions) {
            SDKSessionInfo existing = best.get(s.sessionId());
            if (existing == null || s.lastModified() > existing.lastModified()) {
                best.put(s.sessionId(), s);
            }
        }
        return new ArrayList<>(best.values());
    }

    static List<SDKSessionInfo> applySortAndLimit(
            List<SDKSessionInfo> sessions, @Nullable Integer limit) {
        return applySortLimitOffset(sessions, limit, 0);
    }

    /**
     * Sorts sessions by last_modified descending and applies offset + limit.
     */
    static List<SDKSessionInfo> applySortLimitOffset(
            List<SDKSessionInfo> sessions, @Nullable Integer limit, int offset) {
        sessions.sort(Comparator.comparingLong((SDKSessionInfo s) -> s.lastModified()).reversed());
        if (offset > 0) {
            if (offset >= sessions.size()) {
                return new ArrayList<>();
            }
            sessions = new ArrayList<>(sessions.subList(offset, sessions.size()));
        }
        if (limit != null && limit > 0 && sessions.size() > limit) {
            return new ArrayList<>(sessions.subList(0, limit));
        }
        return sessions;
    }

    // -------------------------------------------------------------------------
    // Public entry points (package-private, called by ClaudeSDK)
    // -------------------------------------------------------------------------

    /**
     * Lists sessions across project directories.
     *
     * @param directory        working directory of the project to filter by (null =
     *                         all projects)
     * @param limit            maximum number of sessions to return (null = no
     *                         limit)
     * @param includeWorktrees whether to include git worktree directories
     * @return session list sorted by last-modified descending
     */
    public static List<SDKSessionInfo> listSessions(
            @Nullable String directory,
            @Nullable Integer limit,
            boolean includeWorktrees) {
        return listSessions(directory, limit, 0, includeWorktrees);
    }

    /**
     * Lists sessions across project directories with offset for pagination.
     *
     * @param directory        working directory of the project to filter by (null =
     *                         all projects)
     * @param limit            maximum number of sessions to return (null = no
     *                         limit)
     * @param offset           number of sessions to skip from the start of the
     *                         sorted result set. Use with {@code limit} for
     *                         pagination. Defaults to 0.
     * @param includeWorktrees whether to include git worktree directories
     * @return session list sorted by last-modified descending
     */
    public static List<SDKSessionInfo> listSessions(
            @Nullable String directory,
            @Nullable Integer limit,
            int offset,
            boolean includeWorktrees) {

        // Python truthiness: an empty directory means "all projects".
        if (directory != null && !directory.isEmpty()) {
            return listSessionsForProject(directory, limit, offset, includeWorktrees);
        }

        List<SDKSessionInfo> allSessions = new ArrayList<>();
        Path projectsDir = getProjectsDir();
        if (Files.exists(projectsDir)) {
            try (Stream<Path> dirs = Files.list(projectsDir)) {
                dirs.filter(Files::isDirectory)
                        .forEach(dir -> allSessions.addAll(readSessionsFromDir(dir, null)));
            } catch (IOException | UncheckedIOException e) {
                // Return what we have
            }
        }

        List<SDKSessionInfo> deduped = deduplicateBySessionId(allSessions);
        return applySortLimitOffset(new ArrayList<>(deduped), limit, offset);
    }

    /**
     * Lists sessions for one project directory and, optionally, its git
     * worktrees. Mirrors Python's {@code _list_sessions_for_project}.
     *
     * <p>With at most one worktree (or worktree scanning off, or no git), only
     * the directory's own project dir is read. Otherwise every project dir
     * whose name is exactly a worktree's sanitized path is read (prefix
     * matching only for truncated paths over {@value #MAX_SANITIZED_LENGTH}
     * chars, which carry a hash suffix), plus the directory's own project dir
     * so a subdirectory such as {@code /repo/packages/app} is still covered.
     * Names compare case-insensitively on Windows.
     */
    private static List<SDKSessionInfo> listSessionsForProject(
            String directory, @Nullable Integer limit, int offset, boolean includeWorktrees) {
        String canonicalDir = canonicalizePath(directory);

        List<String> worktreePaths = includeWorktrees ? getWorktreePaths(canonicalDir) : List.of();

        // No worktrees (or git not available / scanning disabled): just scan
        // the single project dir.
        if (worktreePaths.size() <= 1) {
            Path projectDir = findProjectDir(canonicalDir);
            if (projectDir == null) {
                return new ArrayList<>();
            }
            return applySortLimitOffset(readSessionsFromDir(projectDir, canonicalDir), limit, offset);
        }

        // Worktree-aware scanning: find all project dirs matching any worktree.
        boolean caseInsensitive = isWindows();

        // Sort worktree paths by sanitized prefix length (longest first) so
        // more specific matches take priority over shorter ones.
        List<Map.Entry<String, String>> indexed = new ArrayList<>();
        for (String wt : worktreePaths) {
            String sanitized = sanitizePath(wt);
            String prefix = caseInsensitive ? sanitized.toLowerCase(Locale.ROOT) : sanitized;
            indexed.add(Map.entry(wt, prefix));
        }
        indexed.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getValue().length()).reversed());

        List<Path> allDirents;
        try (Stream<Path> dirs = Files.list(getProjectsDir())) {
            allDirents = dirs.filter(Files::isDirectory).toList();
        } catch (IOException | UncheckedIOException e) {
            // Fall back to the single project dir.
            Path projectDir = findProjectDir(canonicalDir);
            if (projectDir == null) {
                return new ArrayList<>();
            }
            return applySortLimitOffset(readSessionsFromDir(projectDir, canonicalDir), limit, offset);
        }

        List<SDKSessionInfo> allSessions = new ArrayList<>();
        Set<String> seenDirs = new HashSet<>();

        // Always include the user's actual directory (handles subdirectories
        // like /repo/packages/my-app that won't match worktree root prefixes).
        Path canonicalProjectDir = findProjectDir(canonicalDir);
        if (canonicalProjectDir != null) {
            String dirBase = canonicalProjectDir.getFileName().toString();
            seenDirs.add(caseInsensitive ? dirBase.toLowerCase(Locale.ROOT) : dirBase);
            allSessions.addAll(readSessionsFromDir(canonicalProjectDir, canonicalDir));
        }

        for (Path entry : allDirents) {
            String name = entry.getFileName().toString();
            String dirName = caseInsensitive ? name.toLowerCase(Locale.ROOT) : name;
            if (seenDirs.contains(dirName)) {
                continue;
            }
            for (Map.Entry<String, String> wt : indexed) {
                String prefix = wt.getValue();
                // Only use startsWith for truncated paths (> MAX_SANITIZED_LENGTH)
                // where a hash suffix follows. For short paths, require an exact
                // match so /root/project does not match /root/project-foo.
                boolean isMatch = dirName.equals(prefix)
                        || (prefix.length() >= MAX_SANITIZED_LENGTH && dirName.startsWith(prefix + "-"));
                if (isMatch) {
                    seenDirs.add(dirName);
                    allSessions.addAll(readSessionsFromDir(entry, wt.getKey()));
                    break;
                }
            }
        }

        List<SDKSessionInfo> deduped = deduplicateBySessionId(allSessions);
        return applySortLimitOffset(deduped, limit, offset);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * Reads metadata for a single session by ID.
     *
     * <p>
     * No O(n) directory scan — reads only the target session file.
     * Directory resolution matches {@code getSessionMessages}: {@code directory}
     * is the project path; when omitted, all project directories are searched.
     *
     * @param sessionId UUID of the session to look up
     * @param directory project directory path (same semantics as
     *                  {@code listSessions}). When null, all project directories
     *                  are searched.
     * @return {@code SDKSessionInfo} for the session, or null if not found,
     *         is a sidechain session, or has no extractable summary
     */
    public static @Nullable SDKSessionInfo getSessionInfo(
            String sessionId, @Nullable String directory) {

        if (!UUID_RE.matcher(sessionId).matches()) {
            return null;
        }

        String filename = sessionId + ".jsonl";

        if (directory != null && !directory.isEmpty()) {
            String canonical = canonicalizePath(directory);
            Path projectDir = findProjectDir(canonical);
            if (projectDir != null) {
                // A missing or 0-byte file reads as null: keep searching.
                LiteSessionFile lite = readSessionLite(projectDir.resolve(filename));
                if (lite != null) {
                    return parseSessionInfoFromLite(sessionId, lite, canonical);
                }
            }

            // Worktree fallback — matches getSessionMessages semantics
            List<String> worktreePaths = getWorktreePaths(canonical);
            for (String wt : worktreePaths) {
                if (wt.equals(canonical)) {
                    continue;
                }
                Path wtProjectDir = findProjectDir(wt);
                if (wtProjectDir != null) {
                    LiteSessionFile lite = readSessionLite(wtProjectDir.resolve(filename));
                    if (lite != null) {
                        return parseSessionInfoFromLite(sessionId, lite, wt);
                    }
                }
            }

            return null;
        }

        // No directory — search all project directories
        Path projectsDir = getProjectsDir();
        if (!Files.exists(projectsDir)) {
            return null;
        }
        try (Stream<Path> dirs = Files.list(projectsDir)) {
            List<Path> dirents = dirs.filter(Files::isDirectory).toList();
            for (Path entry : dirents) {
                LiteSessionFile lite = readSessionLite(entry.resolve(filename));
                if (lite != null) {
                    return parseSessionInfoFromLite(sessionId, lite, null);
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /**
     * Returns the messages for a specific session.
     *
     * @param sessionId UUID of the session
     * @param directory project working directory to search in (null = all projects)
     * @param limit     maximum messages to return (null = no limit)
     * @param offset    number of messages to skip from the start
     * @return ordered list of visible conversation messages
     */
    public static List<SessionMessage> getSessionMessages(
            String sessionId,
            @Nullable String directory,
            @Nullable Integer limit,
            int offset) {

        if (!UUID_RE.matcher(sessionId).matches()) {
            return List.of();
        }

        String content = readSessionFile(sessionId, directory);
        if (content == null) {
            return List.of();
        }

        List<Map<String, Object>> entries = parseTranscriptEntries(content);
        List<Map<String, Object>> chain = buildConversationChain(entries);

        List<SessionMessage> messages = new ArrayList<>();
        for (Map<String, Object> entry : chain) {
            if (isVisibleMessage(entry)) {
                messages.add(toSessionMessage(entry));
            }
        }

        return Collections.unmodifiableList(applyLimitOffset(messages, limit, offset));
    }

    // -------------------------------------------------------------------------
    // Full transcript reconstruction
    // -------------------------------------------------------------------------

    static List<Map<String, Object>> parseTranscriptEntries(String content) {
        Set<String> validTypes = Set.of("user", "assistant", "progress", "system", "attachment");
        List<Map<String, Object>> entries = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty())
                    continue;
                // Corrupt lines (including two concatenated objects) are skipped
                Map<String, Object> entry = parseJsonObjectLine(line);
                if (entry == null)
                    continue;
                if (!(entry.get("type") instanceof String type) || !validTypes.contains(type))
                    continue;
                if (!(entry.get("uuid") instanceof String))
                    continue;
                entries.add(entry);
            }
        } catch (IOException e) {
            // StringReader never throws
        }

        return entries;
    }

    static List<Map<String, Object>> buildConversationChain(List<Map<String, Object>> entries) {
        if (entries.isEmpty())
            return List.of();

        // Index by uuid for O(1) parent lookup. A later entry with the same
        // uuid replaces the earlier one but keeps its position, as a Python
        // dict does. Entries without a string uuid are skipped (the readers
        // already drop them).
        Map<String, Map<String, Object>> byUuid = new LinkedHashMap<>();
        Map<String, Integer> indexByUuid = new HashMap<>();
        List<Map<String, Object>> withUuid = new ArrayList<>();
        Set<String> parentUuids = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            Map<String, Object> entry = entries.get(i);
            if (!(entry.get("uuid") instanceof String uuid)) {
                continue;
            }
            byUuid.put(uuid, entry);
            indexByUuid.put(uuid, i);
            withUuid.add(entry);
            String parentUuid = parentUuidOf(entry);
            if (parentUuid != null) {
                parentUuids.add(parentUuid);
            }
        }

        // From each terminal (an entry no other entry names as its parent),
        // walk back to the nearest user/assistant entry: that is a leaf.
        List<Map<String, Object>> leaves = new ArrayList<>();
        for (Map<String, Object> terminal : withUuid) {
            if (parentUuids.contains(uuidOf(terminal))) {
                continue;
            }
            Map<String, Object> walk = terminal;
            Set<String> walkSeen = new HashSet<>();
            while ((walk != null) && walkSeen.add(uuidOf(walk))) {
                Object type = walk.get("type");
                if ("user".equals(type) || "assistant".equals(type)) {
                    leaves.add(walk);
                    break;
                }
                String parentUuid = parentUuidOf(walk);
                walk = (parentUuid != null) ? byUuid.get(parentUuid) : null;
            }
        }
        if (leaves.isEmpty()) {
            return List.of();
        }

        // Prefer a main-chain leaf (not sidechain, team or meta), the most
        // recent in file order; with none, the most recent leaf of any kind.
        List<Map<String, Object>> mainLeaves = new ArrayList<>();
        for (Map<String, Object> leaf : leaves) {
            if (!truthy(leaf.get("isSidechain")) && !truthy(leaf.get("teamName")) && !truthy(leaf.get("isMeta"))) {
                mainLeaves.add(leaf);
            }
        }
        Map<String, Object> bestLeaf = null;
        int bestIndex = -1;
        for (Map<String, Object> candidate : (mainLeaves.isEmpty() ? leaves : mainLeaves)) {
            int idx = indexByUuid.getOrDefault(uuidOf(candidate), -1);
            if ((bestLeaf == null) || (idx > bestIndex)) {
                bestLeaf = candidate;
                bestIndex = idx;
            }
        }

        // Walk from bestLeaf to root, collecting the chain
        List<Map<String, Object>> chain = new ArrayList<>();
        Map<String, Object> current = bestLeaf;
        Set<String> visited = new HashSet<>();

        while ((current != null) && visited.add(uuidOf(current))) { // cycle guard
            chain.add(current);
            String parentUuid = parentUuidOf(current);
            current = (parentUuid != null) ? byUuid.get(parentUuid) : null;
        }

        Collections.reverse(chain);
        return recoverOrphanedParallelToolResults(byUuid, chain, visited);
    }

    /** Returns the API {@code message.id} of an assistant entry, if it has one. */
    @Nullable
    private static String assistantMessageId(Map<String, Object> entry) {
        if (!"assistant".equals(entry.get("type")) || !(entry.get("message") instanceof Map<?, ?> message)) {
            return null;
        }
        return ((message.get("id") instanceof String id) && !id.isEmpty()) ? id : null;
    }

    /** Returns {@code idField} of each {@code blockType} content block in an entry. */
    private static List<String> contentBlockIds(Map<String, Object> entry, String blockType, String idField) {
        if (!(entry.get("message") instanceof Map<?, ?> message) || !(message.get("content") instanceof List<?> content)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Object block : content) {
            if ((block instanceof Map<?, ?> b) && blockType.equals(b.get("type"))
                    && (b.get(idField) instanceof String id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** Returns true for a user entry with a parent that carries a tool_result. */
    private static boolean isToolResultEntry(Map<String, Object> entry) {
        if (!"user".equals(entry.get("type"))
                || (parentUuidOf(entry) == null)
                || !(entry.get("message") instanceof Map<?, ?> message)
                || !(message.get("content") instanceof List<?> content)) {
            return false;
        }
        for (Object block : content) {
            if ((block instanceof Map<?, ?> b) && "tool_result".equals(b.get("type"))) {
                return true;
            }
        }
        return false;
    }

    /** Returns true if two entries were written by the same (sub)agent. */
    private static boolean sameWriter(Map<String, Object> a, Map<String, Object> b) {
        return (truthy(a.get("isSidechain")) == truthy(b.get("isSidechain")))
                && Objects.equals(a.get("agentId"), b.get("agentId"));
    }

    private static String timestampSortKey(Map<String, Object> entry) {
        return (entry.get("timestamp") instanceof String ts) ? ts : "";
    }

    private static String uuidOf(Map<String, Object> entry) {
        return (String) entry.get("uuid");
    }

    /**
     * Puts back the tool calls and results a parallel turn left off the chain.
     *
     * <p>
     * The CLI writes one assistant entry per content block, so a response with
     * N parallel tool calls is N assistant entries that share
     * {@code message.id} and are chained one after another. Each tool_result
     * entry's {@code parentUuid} is its own call's assistant entry, so the
     * results fan out from different parents and the transcript forks. The
     * {@code parentUuid} walk follows only the branch the next turn continued
     * from: it keeps one result, and drops every call written after the one
     * that result answers.
     *
     * <p>
     * For each response on the chain, this collects its assistant entries and
     * their tool results that the walk did not reach, and inserts them right
     * after the response's last entry on the chain, so the response stays in
     * one piece and every result follows its call. Mirrors the TypeScript and
     * Python SDKs' {@code getSessionMessages}.
     *
     * @param byUuid every entry by uuid, in file order
     * @param chain  the walked chain, root to leaf
     * @param seen   the uuids on {@code chain}; recovered uuids are added to it
     */
    static List<Map<String, Object>> recoverOrphanedParallelToolResults(
            Map<String, Map<String, Object>> byUuid,
            List<Map<String, Object>> chain,
            Set<String> seen) {
        List<Map<String, Object>> chainAssistants = new ArrayList<>();
        for (Map<String, Object> e : chain) {
            if ("assistant".equals(e.get("type"))) {
                chainAssistants.add(e);
            }
        }
        if (chainAssistants.isEmpty()) {
            return chain;
        }

        // Anchor = the last on-chain entry of each response. chainAssistants is
        // in chain order, so later entries overwrite earlier ones.
        Map<String, Map<String, Object>> anchorByMessageId = new HashMap<>();
        for (Map<String, Object> e : chainAssistants) {
            String messageId = assistantMessageId(e);
            if (messageId != null) {
                anchorByMessageId.put(messageId, e);
            }
        }
        if (anchorByMessageId.isEmpty()) {
            return chain;
        }

        // Group every assistant entry by response, and record which entry made
        // each tool call. A tool_use id claimed by two different responses is
        // ambiguous (null) and is never used to place a result.
        Map<String, List<Map<String, Object>>> siblingsByMessageId = new HashMap<>();
        Map<String, Map<String, Object>> callOwner = new HashMap<>();
        List<Map<String, Object>> toolResultEntries = new ArrayList<>();
        for (Map<String, Object> e : byUuid.values()) {
            String messageId = assistantMessageId(e);
            if (messageId != null) {
                siblingsByMessageId.computeIfAbsent(messageId, k -> new ArrayList<>()).add(e);
                for (String callId : contentBlockIds(e, "tool_use", "id")) {
                    if (!callOwner.containsKey(callId)) {
                        callOwner.put(callId, e);
                        continue;
                    }
                    Map<String, Object> owner = callOwner.get(callId);
                    boolean sameResponse = (owner != null) && messageId.equals(assistantMessageId(owner));
                    callOwner.put(callId, sameResponse ? e : null);
                }
            } else if (isToolResultEntry(e)) {
                toolResultEntries.add(e);
            }
        }

        // Index each tool result under the assistant entries it may belong to:
        // its parent, its sourceToolAssistantUUID, and the owner of each call it
        // answers. The last two only count when written by the same agent.
        Map<String, List<Map<String, Object>>> resultsByAssistant = new HashMap<>();
        for (Map<String, Object> result : toolResultEntries) {
            resultsByAssistant.computeIfAbsent(parentUuidOf(result), k -> new ArrayList<>()).add(result);
            if (result.get("sourceToolAssistantUUID") instanceof String source) {
                Map<String, Object> sourceEntry = byUuid.get(source);
                if ((sourceEntry != null) && sameWriter(result, sourceEntry)) {
                    resultsByAssistant.computeIfAbsent(source, k -> new ArrayList<>()).add(result);
                }
            }
            for (String callId : contentBlockIds(result, "tool_result", "tool_use_id")) {
                Map<String, Object> owner = callOwner.get(callId);
                if ((owner != null) && sameWriter(result, owner)) {
                    resultsByAssistant.computeIfAbsent(uuidOf(owner), k -> new ArrayList<>()).add(result);
                }
            }
        }

        // Only needed to place strays, which are rare: built on first use.
        Set<String> answeredOnChain = null;
        Map<String, Integer> filePosition = null;

        Set<String> processed = new HashSet<>();
        Map<String, List<Map<String, Object>>> inserts = new HashMap<>();
        for (Map<String, Object> assistant : chainAssistants) {
            String messageId = assistantMessageId(assistant);
            if ((messageId == null) || !processed.add(messageId)) {
                continue;
            }

            List<Map<String, Object>> group = siblingsByMessageId.getOrDefault(messageId, List.of(assistant));
            Set<String> memberUuids = new HashSet<>();
            List<Map<String, Object>> orphanedSiblings = new ArrayList<>();
            for (Map<String, Object> member : group) {
                memberUuids.add(uuidOf(member));
                if (!seen.contains(uuidOf(member))) {
                    orphanedSiblings.add(member);
                }
            }
            List<Map<String, Object>> orphanedResults = new ArrayList<>();
            // Results indexed under this response whose parent is not one of its
            // entries: recovered only if they answer a still-unanswered call.
            List<Map<String, Object>> strays = new ArrayList<>();
            Set<String> collected = new HashSet<>();
            for (Map<String, Object> member : group) {
                for (Map<String, Object> result : resultsByAssistant.getOrDefault(uuidOf(member), List.of())) {
                    String resultUuid = uuidOf(result);
                    if (seen.contains(resultUuid) || !collected.add(resultUuid)) {
                        continue;
                    }
                    if (memberUuids.contains(result.get("parentUuid"))) {
                        orphanedResults.add(result);
                    } else {
                        strays.add(result);
                    }
                }
            }

            if (!strays.isEmpty()) {
                if ((answeredOnChain == null) || (filePosition == null)) {
                    answeredOnChain = new HashSet<>();
                    for (Map<String, Object> e : chain) {
                        answeredOnChain.addAll(contentBlockIds(e, "tool_result", "tool_use_id"));
                    }
                    filePosition = new HashMap<>();
                    int i = 0;
                    for (String uid : byUuid.keySet()) {
                        filePosition.put(uid, i++);
                    }
                }
                Set<String> answered = new HashSet<>(answeredOnChain);
                for (Map<String, Object> result : orphanedResults) {
                    answered.addAll(contentBlockIds(result, "tool_result", "tool_use_id"));
                }
                Map<String, Integer> positions = filePosition;
                strays.sort(Comparator.comparingInt(e -> positions.get(uuidOf(e))));
                for (Map<String, Object> result : strays) {
                    List<String> callIds = contentBlockIds(result, "tool_result", "tool_use_id");
                    boolean answersOpenCall = false;
                    for (String callId : callIds) {
                        Map<String, Object> owner = callOwner.get(callId);
                        if (!answered.contains(callId) && (owner != null) && memberUuids.contains(uuidOf(owner))) {
                            answersOpenCall = true;
                            break;
                        }
                    }
                    if (!answersOpenCall) {
                        continue;
                    }
                    answered.addAll(callIds);
                    orphanedResults.add(result);
                }
            }

            if (orphanedSiblings.isEmpty() && orphanedResults.isEmpty()) {
                continue;
            }

            // Timestamp order keeps content-block and completion order. The sort
            // is stable, so on a tie siblings keep file order and results keep
            // the order of the calls they answer.
            orphanedSiblings.sort(Comparator.comparing(Sessions::timestampSortKey));
            orphanedResults.sort(Comparator.comparing(Sessions::timestampSortKey));
            List<Map<String, Object>> recovered = new ArrayList<>(orphanedSiblings);
            recovered.addAll(orphanedResults);
            for (Map<String, Object> e : recovered) {
                seen.add(uuidOf(e));
            }
            inserts.put(uuidOf(anchorByMessageId.get(messageId)), recovered);
        }

        if (inserts.isEmpty()) {
            return chain;
        }

        List<Map<String, Object>> resultChain = new ArrayList<>(chain.size());
        for (Map<String, Object> e : chain) {
            resultChain.add(e);
            resultChain.addAll(inserts.getOrDefault(uuidOf(e), List.of()));
        }
        return resultChain;
    }

    static boolean isVisibleMessage(Map<String, Object> entry) {
        Object type = entry.get("type");
        if (!"user".equals(type) && !"assistant".equals(type))
            return false;
        if (truthy(entry.get("isMeta")))
            return false;
        if (truthy(entry.get("isSidechain")))
            return false;
        return !truthy(entry.get("teamName"));
    }

    /**
     * Python truthiness, which the transcript readers follow so that flags
     * written as something other than a JSON boolean ({@code "true"},
     * {@code 1}, an empty {@code teamName}) are read the same way in both SDKs.
     */
    static boolean truthy(@Nullable Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value instanceof CharSequence cs) {
            return cs.length() > 0;
        }
        if (value instanceof java.util.Collection<?> c) {
            return !c.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    /** {@code parentUuid} when it is a non-empty string, else null (no parent). */
    @Nullable
    private static String parentUuidOf(Map<String, Object> entry) {
        return ((entry.get("parentUuid") instanceof String parent) && !parent.isEmpty()) ? parent : null;
    }

    /**
     * Applies {@code offset}, then {@code limit}, as Python's
     * {@code messages[offset:offset + limit]}. A null or non-positive limit
     * means no limit.
     */
    static <T> List<T> applyLimitOffset(List<T> messages, @Nullable Integer limit, int offset) {
        int from = Math.min(Math.max(offset, 0), messages.size());
        int to = ((limit != null) && (limit > 0)) ? Math.min(messages.size(), from + limit) : messages.size();
        return new ArrayList<>(messages.subList(from, to));
    }

    static SessionMessage toSessionMessage(Map<String, Object> entry) {
        return toSessionMessage(entry, null, null);
    }

    /**
     * Converts a transcript entry into a {@link SessionMessage}.
     *
     * <p>The parent ids are not carried by the transcript entries themselves —
     * they describe the whole subagent, and callers read them from the
     * subagent's metadata (see {@link #parentIdsFromAgentMetadata}). Top-level
     * session reads pass {@code null} for both.
     */
    static SessionMessage toSessionMessage(
            Map<String, Object> entry,
            @Nullable String parentToolUseId,
            @Nullable String parentAgentId) {
        String type = (String) entry.get("type");
        String uuid = (String) entry.get("uuid");
        String sessionId = (String) entry.get("sessionId");
        if (sessionId == null)
            sessionId = "";
        Object message = entry.get("message");
        return new SessionMessage(type, uuid, sessionId, message, parentToolUseId, parentAgentId);
    }

    /**
     * The parent ids describing a subagent, read from its metadata.
     *
     * @param toolUseId     id of the Agent {@code tool_use} that spawned the
     *                      subagent, or null when unknown
     * @param parentAgentId id of the spawning subagent (nested subagents), or
     *                      null when the main session spawned it
     */
    record ParentIds(@Nullable String toolUseId, @Nullable String parentAgentId) {

        static final ParentIds NONE = new ParentIds(null, null);
    }

    /**
     * {@code agent-<id>.jsonl} -> {@code agent-<id>.meta.json} (same directory).
     *
     * <p>The single definition of the sidecar naming convention, shared by the
     * read path here, session import, and resume materialization.
     */
    static Path agentMetadataSidecarPath(Path transcriptPath) {
        String name = transcriptPath.getFileName().toString();
        if (name.endsWith(".jsonl")) {
            name = name.substring(0, name.length() - ".jsonl".length());
        }
        return transcriptPath.resolveSibling(name + ".meta.json");
    }

    /**
     * Reads the {@code .meta.json} sidecar beside a subagent transcript.
     *
     * <p>Returns null when the sidecar is missing, not valid JSON, or not a
     * JSON object — an unusable optional sidecar degrades to an absent one.
     * Other {@link IOException}s (e.g. permission denied) propagate.
     */
    static @Nullable Map<String, Object> readAgentMetadataSidecar(Path transcriptPath) throws IOException {
        String text;
        try {
            text = Files.readString(agentMetadataSidecarPath(transcriptPath), StandardCharsets.UTF_8);
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        }
        // Not valid JSON (trailing tokens included) or not an object: no metadata.
        return parseJsonObjectLine(text);
    }

    /**
     * Extracts {@code (toolUseId, parentAgentId)} from an agent metadata map.
     *
     * <p>Works for both the on-disk {@code .meta.json} sidecar and the
     * synthetic {@code agent_metadata} entry a
     * {@link in.vidyalai.claude.sdk.types.session.SessionStore} receives in its
     * place. Non-string values are treated as absent.
     */
    static ParentIds parentIdsFromAgentMetadata(@Nullable Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return ParentIds.NONE;
        }
        Object toolUseId = meta.get("toolUseId");
        Object parentAgentId = meta.get("parentAgentId");
        return new ParentIds(
                (toolUseId instanceof String s) ? s : null,
                (parentAgentId instanceof String s) ? s : null);
    }

    static @Nullable String readSessionFile(String sessionId, @Nullable String directory) {
        String filename = sessionId + ".jsonl";

        // A missing, unreadable or empty file (a 0-byte stub) means "not
        // here": every lookup below keeps searching, as Python's does.
        if (directory != null && !directory.isEmpty()) {
            String canonicalDir = canonicalizePath(directory);

            // Try the exact/prefix-matched project directory first
            Path projectDir = findProjectDir(canonicalDir);
            if (projectDir != null) {
                String content = readFileContent(projectDir.resolve(filename));
                if (content != null && !content.isEmpty()) {
                    return content;
                }
            }

            // Try worktree paths: sessions may live under a different worktree root
            for (String worktreePath : getWorktreePaths(canonicalDir)) {
                if (worktreePath.equals(canonicalDir)) {
                    continue; // already tried above
                }
                Path worktreeDir = findProjectDir(worktreePath);
                if (worktreeDir != null) {
                    String content = readFileContent(worktreeDir.resolve(filename));
                    if (content != null && !content.isEmpty()) {
                        return content;
                    }
                }
            }
            return null;
        }

        // No directory provided: search all project directories
        List<Path> dirents;
        try (Stream<Path> dirs = Files.list(getProjectsDir())) {
            dirents = dirs.filter(Files::isDirectory).toList();
        } catch (IOException | UncheckedIOException e) {
            return null;
        }
        for (Path dir : dirents) {
            String content = readFileContent(dir.resolve(filename));
            if (content != null && !content.isEmpty()) {
                return content;
            }
        }
        return null;
    }

    private static @Nullable String readFileContent(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // listSubagents / getSubagentMessages — subagent transcript reading
    // -------------------------------------------------------------------------

    /**
     * Resolves the on-disk path of a session JSONL file, matching
     * {@link #readSessionFile} semantics (worktree-aware when
     * {@code directory} is provided; full project scan otherwise).
     *
     * @return path of the first non-empty match, or {@code null}.
     */
    @SuppressWarnings("null")
    public static @Nullable Path resolveSessionFilePath(String sessionId, @Nullable String directory) {
        String filename = sessionId + ".jsonl";

        if (directory != null && !directory.isEmpty()) {
            String canonicalDir = canonicalizePath(directory);
            Path projectDir = findProjectDir(canonicalDir);
            Path found = statCandidate(projectDir, filename);
            if (found != null) {
                return found;
            }

            List<String> worktreePaths = getWorktreePaths(canonicalDir);
            for (String worktreePath : worktreePaths) {
                if (!worktreePath.equals(canonicalDir)) {
                    Path worktreeDir = findProjectDir(worktreePath);
                    Path wtFound = statCandidate(worktreeDir, filename);
                    if (wtFound != null) {
                        return wtFound;
                    }
                }
            }
            return null;
        }

        Path projectsDir = getProjectsDir();
        if (!Files.exists(projectsDir)) {
            return null;
        }
        try (Stream<Path> dirs = Files.list(projectsDir)) {
            return dirs
                    .filter(Files::isDirectory)
                    .map(dir -> statCandidate(dir, filename))
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (IOException | UncheckedIOException e) {
            return null;
        }
    }

    private static @Nullable Path statCandidate(@Nullable Path projectDir, String filename) {
        if (projectDir == null) {
            return null;
        }
        Path candidate = projectDir.resolve(filename);
        try {
            if (Files.size(candidate) > 0) {
                return candidate;
            }
        } catch (IOException ignored) {
            // file missing or unreadable
        }
        return null;
    }

    /**
     * Resolves the subagents directory for a given session. The session
     * file lives at {@code <projectDir>/<sessionId>.jsonl} and the
     * subagents directory at
     * {@code <projectDir>/<sessionId>/subagents/}.
     */
    static @Nullable Path resolveSubagentsDir(String sessionId, @Nullable String directory) {
        Path resolved = resolveSessionFilePath(sessionId, directory);
        if (resolved == null) {
            return null;
        }
        String name = resolved.getFileName().toString();
        // Strip the .jsonl suffix to derive the session directory.
        String sessionDirName = name.endsWith(".jsonl")
                ? name.substring(0, name.length() - ".jsonl".length())
                : name;
        return resolved.resolveSibling(sessionDirName).resolve("subagents");
    }

    /**
     * Recursively collects {@code agent-*.jsonl} files. Subagent
     * transcripts may live directly in {@code subagents/} or in nested
     * subdirectories such as {@code subagents/workflows/<runId>/}.
     */
    static List<AgentFile> collectAgentFiles(Path baseDir) {
        List<AgentFile> results = new ArrayList<>();
        if (!Files.isDirectory(baseDir)) {
            return results;
        }
        walkAgentDir(baseDir, results);
        return results;
    }

    private static void walkAgentDir(Path currentDir, List<AgentFile> results) {
        try (Stream<Path> children = Files.list(currentDir)) {
            List<Path> sorted = children
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path entry : sorted) {
                String name = entry.getFileName().toString();
                if (Files.isRegularFile(entry)
                        && name.startsWith("agent-")
                        && name.endsWith(".jsonl")) {
                    String agentId = name.substring("agent-".length(),
                            name.length() - ".jsonl".length());
                    results.add(new AgentFile(agentId, entry));
                } else if (Files.isDirectory(entry)) {
                    walkAgentDir(entry, results);
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    record AgentFile(String agentId, Path filePath) {
    }

    /**
     * Builds the conversation chain for a subagent transcript. Subagent
     * transcripts are simpler than main sessions — no compaction, no
     * sidechains, no preserved segments. Find the last user/assistant
     * entry and walk {@code parentUuid} links back to the root.
     */
    static List<Map<String, Object>> buildSubagentChain(List<Map<String, Object>> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }

        Map<String, Map<String, Object>> byUuid = new HashMap<>();
        for (Map<String, Object> entry : entries) {
            String uuid = (String) entry.get("uuid");
            if (uuid != null) {
                byUuid.put(uuid, entry);
            }
        }

        // Subagent transcripts are linear — the last user/assistant entry
        // is the leaf.
        Map<String, Object> leaf = null;
        for (int i = entries.size() - 1; i >= 0; i--) {
            String type = (String) entries.get(i).get("type");
            if ("user".equals(type) || "assistant".equals(type)) {
                leaf = entries.get(i);
                break;
            }
        }
        if (leaf == null) {
            return List.of();
        }

        List<Map<String, Object>> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, Object> current = leaf;
        while (current != null) {
            String uuid = (String) current.get("uuid");
            if (uuid == null || !seen.add(uuid)) {
                break;
            }
            chain.add(current);
            String parentUuid = (String) current.get("parentUuid");
            current = parentUuid != null ? byUuid.get(parentUuid) : null;
        }
        Collections.reverse(chain);
        return chain;
    }

    /**
     * Lists subagent IDs for a given session by scanning the subagents
     * directory.
     *
     * <p>Subagent transcripts are stored at
     * {@code ~/.claude/projects/<project>/<sessionId>/subagents/agent-<agentId>.jsonl}
     * (and may be nested in subdirectories such as
     * {@code workflows/<runId>/}).
     *
     * @param sessionId UUID of the parent session
     * @param directory project directory to find the session in. When null,
     *                  searches all project directories under
     *                  {@code ~/.claude/projects/}.
     * @return list of subagent ID strings; empty list when the session is
     *         not found, the {@code sessionId} is not a valid UUID, or the
     *         session has no subagents.
     */
    public static List<String> listSubagents(String sessionId, @Nullable String directory) {
        if (!UUID_RE.matcher(sessionId).matches()) {
            return List.of();
        }
        Path subagentsDir = resolveSubagentsDir(sessionId, directory);
        if (subagentsDir == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (AgentFile af : collectAgentFiles(subagentsDir)) {
            ids.add(af.agentId());
        }
        return ids;
    }

    /**
     * Reads a subagent's conversation messages from its JSONL transcript
     * file.
     *
     * <p>Each message's {@code parentToolUseId} is the id of the Agent
     * {@code tool_use} in the parent session that spawned this subagent (and
     * {@code parentAgentId} the spawning subagent, for nested subagents), read
     * from the {@code agent-<agentId>.meta.json} sidecar next to the
     * transcript; both are null if the sidecar is missing or unusable.
     *
     * @param sessionId UUID of the parent session
     * @param agentId   ID of the subagent (as returned by
     *                  {@link #listSubagents})
     * @param directory project directory to find the session in
     * @param limit     maximum number of messages to return (null = no
     *                  limit)
     * @param offset    number of messages to skip from the start
     * @return ordered list of subagent user/assistant messages; empty when
     *         the session or subagent is not found, the {@code sessionId}
     *         is not a valid UUID, or the transcript contains no
     *         user/assistant entries.
     */
    public static List<SessionMessage> getSubagentMessages(
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

        Path subagentsDir = resolveSubagentsDir(sessionId, directory);
        if (subagentsDir == null) {
            return List.of();
        }

        Path match = null;
        for (AgentFile af : collectAgentFiles(subagentsDir)) {
            if (agentId.equals(af.agentId())) {
                match = af.filePath();
                break;
            }
        }
        if (match == null) {
            return List.of();
        }

        String content = readFileContent(match);
        if (content == null || content.isEmpty()) {
            return List.of();
        }

        // The .meta.json sidecar next to the transcript records which Agent
        // tool_use spawned this subagent (and, for nested subagents, the parent
        // agent id). Like the transcript read above, any failure to read it
        // (missing, unreadable, corrupt) degrades to "no metadata" rather than
        // raising from this best-effort read helper.
        Map<String, Object> meta;
        try {
            meta = readAgentMetadataSidecar(match);
        } catch (IOException e) {
            meta = null;
        }
        ParentIds parentIds = parentIdsFromAgentMetadata(meta);

        List<Map<String, Object>> entries = parseTranscriptEntries(content);
        List<Map<String, Object>> chain = buildSubagentChain(entries);
        List<SessionMessage> messages = new ArrayList<>();
        for (Map<String, Object> entry : chain) {
            String type = (String) entry.get("type");
            if ("user".equals(type) || "assistant".equals(type)) {
                messages.add(toSessionMessage(entry, parentIds.toolUseId(), parentIds.parentAgentId()));
            }
        }

        if (limit != null && limit > 0) {
            int end = Math.min(messages.size(), offset + limit);
            if (offset >= messages.size()) {
                return List.of();
            }
            return Collections.unmodifiableList(messages.subList(offset, end));
        }
        if (offset > 0) {
            if (offset >= messages.size()) {
                return List.of();
            }
            return Collections.unmodifiableList(messages.subList(offset, messages.size()));
        }
        return Collections.unmodifiableList(messages);
    }
}
