package in.vidyalai.claude.sdk.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionListSubkeysKey;
import in.vidyalai.claude.sdk.types.session.SessionStore;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;
import in.vidyalai.claude.sdk.types.session.SessionStoreFlushMode;
import in.vidyalai.claude.sdk.types.session.SessionStoreListEntry;

/**
 * Materialize a {@link SessionStore}-backed resume into a temp
 * {@code CLAUDE_CONFIG_DIR}.
 *
 * <p>When {@link ClaudeAgentOptions#resume()} (or
 * {@link ClaudeAgentOptions#continueConversation()}) is paired with
 * {@link ClaudeAgentOptions#sessionStore()}, the session JSONL almost
 * certainly does not exist on local disk — it lives in the external store.
 * The CLI subprocess only knows how to resume from a local file. This class
 * bridges the gap: it loads the session from the store, writes it to a
 * temporary directory laid out exactly like {@code ~/.claude/}, and returns
 * the path so the caller can point the subprocess at it via
 * {@code CLAUDE_CONFIG_DIR}.
 *
 * <p>Mirrors Python SDK's {@code session_resume.py}.
 */
public final class SessionResume {

    private static final Logger logger = Logger.getLogger(SessionResume.class.getName());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern UUID_RE = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);

    private SessionResume() {
    }

    /**
     * Result of {@link #materializeResumeSession(ClaudeAgentOptions)}.
     */
    public static final class MaterializedResume implements AutoCloseable {

        private final Path configDir;
        private final String resumeSessionId;

        public MaterializedResume(Path configDir, String resumeSessionId) {
            this.configDir = configDir;
            this.resumeSessionId = resumeSessionId;
        }

        /**
         * Temporary directory laid out like {@code ~/.claude/}; point the
         * subprocess at it via {@code CLAUDE_CONFIG_DIR}.
         */
        public Path configDir() {
            return configDir;
        }

        /**
         * Session ID to pass as {@code --resume}. When the input was
         * {@code continueConversation}, this is the most-recent session
         * resolved via {@link SessionStore#listSessions(String)}.
         */
        public String resumeSessionId() {
            return resumeSessionId;
        }

        /** Best-effort recursive removal. Never raises. */
        public void cleanup() {
            rmtreeWithRetry(configDir, 4, 100L);
        }

        @Override
        public void close() {
            cleanup();
        }
    }

    /**
     * Apply a {@link MaterializedResume} to {@link ClaudeAgentOptions} —
     * sets {@code CLAUDE_CONFIG_DIR} in {@code env}, sets {@code resume} to
     * the materialized session id, and clears {@code continueConversation}.
     */
    public static ClaudeAgentOptions applyMaterializedOptions(
            ClaudeAgentOptions options, MaterializedResume materialized) {
        Map<String, String> env = new HashMap<>(options.env());
        env.put("CLAUDE_CONFIG_DIR", materialized.configDir().toString());
        return options.toBuilder()
                .env(env)
                .resume(materialized.resumeSessionId())
                .continueConversation(false)
                .build();
    }

    /**
     * Construct the {@link TranscriptMirrorBatcher} for a session.
     *
     * <p>Resolves {@code projectsDir} to the materialized temp dir when
     * present (so file-path → key resolution matches what the subprocess
     * writes), otherwise to the standard projects directory under the
     * effective {@code CLAUDE_CONFIG_DIR}.
     *
     * <p>Defaults the flush mode to {@link SessionStoreFlushMode#BATCHED}.
     */
    public static TranscriptMirrorBatcher buildMirrorBatcher(
            SessionStore store,
            @Nullable MaterializedResume materialized,
            @Nullable Map<String, String> env,
            BiConsumer<@Nullable SessionKey, String> onError) {
        return buildMirrorBatcher(store, materialized, env, onError, SessionStoreFlushMode.BATCHED);
    }

    /**
     * Construct the {@link TranscriptMirrorBatcher} for a session.
     *
     * <p>{@code flushMode = EAGER} zeroes the batcher's pending thresholds so
     * every enqueued frame schedules a background flush; {@code BATCHED} keeps
     * the defaults (flush on {@code result} or 500-entry / 1 MiB overflow).
     */
    public static TranscriptMirrorBatcher buildMirrorBatcher(
            SessionStore store,
            @Nullable MaterializedResume materialized,
            @Nullable Map<String, String> env,
            BiConsumer<@Nullable SessionKey, String> onError,
            SessionStoreFlushMode flushMode) {
        Path projectsDir;
        if (materialized != null) {
            projectsDir = materialized.configDir().resolve("projects");
        } else {
            projectsDir = Sessions.getProjectsDirForEnv(env);
        }
        boolean eager = flushMode == SessionStoreFlushMode.EAGER;
        int maxEntries = eager ? 0 : TranscriptMirrorBatcher.MAX_PENDING_ENTRIES;
        int maxBytes = eager ? 0 : TranscriptMirrorBatcher.MAX_PENDING_BYTES;
        return new TranscriptMirrorBatcher(
                store,
                projectsDir.toString(),
                onError,
                TranscriptMirrorBatcher.SEND_TIMEOUT_MS,
                maxEntries,
                maxBytes,
                in.vidyalai.claude.sdk.types.session.SessionStoreExecutor.getDefault());
    }

    /**
     * Load a session from {@code options.sessionStore()} and write it to a
     * temp dir.
     *
     * <p>Returns {@code null} when no materialization is needed (no store, no
     * resume/continue, store has no entries, or the resolved session ID is
     * not a valid UUID).
     */
    @SuppressWarnings("null")
    public static @Nullable MaterializedResume materializeResumeSession(ClaudeAgentOptions options)
            throws IOException {
        SessionStore store = options.sessionStore();
        if (store == null) {
            return null;
        }
        if (options.resume() == null && !options.continueConversation()) {
            return null;
        }

        long timeoutMs = options.loadTimeoutMs();
        String projectKey = SessionStores.projectKeyForDirectory(
                options.cwd() != null ? options.cwd().toString() : null);

        // Resolve the session ID — explicit resume wins; otherwise pick the
        // most-recently-modified non-sidechain session from the store.
        ResolvedSession resolved;
        if (options.resume() != null) {
            if (!UUID_RE.matcher(options.resume()).matches()) {
                return null;
            }
            resolved = loadCandidate(store, projectKey, options.resume(), timeoutMs);
        } else {
            resolved = resolveContinueCandidate(store, projectKey, timeoutMs);
        }
        if (resolved == null) {
            return null;
        }

        Path tmpBase = Files.createTempDirectory("claude-resume-");
        try {
            Path projectDir = tmpBase.resolve("projects").resolve(projectKey);
            Files.createDirectories(projectDir);
            writeJsonl(projectDir.resolve(resolved.sessionId() + ".jsonl"), resolved.entries());

            // Copy auth config so the subprocess can authenticate.
            copyAuthFiles(tmpBase, options.env());

            if (store.implementsListSubkeys()) {
                materializeSubkeys(store, projectDir, projectKey, resolved.sessionId(), timeoutMs);
            }
        } catch (RuntimeException | IOException e) {
            // Any failure leaves tmpBase on disk with no path for the caller
            // to clean it up. Remove it before rethrowing.
            rmtreeWithRetry(tmpBase, 4, 100L);
            throw e;
        }

        return new MaterializedResume(tmpBase, resolved.sessionId());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private record ResolvedSession(String sessionId, List<SessionStoreEntry> entries) {
    }

    private static @Nullable ResolvedSession loadCandidate(
            SessionStore store, String projectKey, String sessionId, long timeoutMs) {
        SessionKey key = new SessionKey(projectKey, sessionId, null);
        List<SessionStoreEntry> entries = withTimeout(
                () -> store.loadAsync(key).get(timeoutMs, TimeUnit.MILLISECONDS),
                timeoutMs,
                "SessionStore.load() for session " + sessionId);
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        return new ResolvedSession(sessionId, entries);
    }

    private static @Nullable ResolvedSession resolveContinueCandidate(
            SessionStore store, String projectKey, long timeoutMs) {
        List<SessionStoreListEntry> sessions = withTimeout(
                () -> store.listSessionsAsync(projectKey).get(timeoutMs, TimeUnit.MILLISECONDS),
                timeoutMs,
                "SessionStore.listSessions()");
        if (sessions == null || sessions.isEmpty()) {
            return null;
        }
        List<SessionStoreListEntry> sorted = new ArrayList<>(sessions);
        sorted.sort(Comparator.comparingLong((SessionStoreListEntry e) -> e.mtime()).reversed());
        for (SessionStoreListEntry cand : sorted) {
            String sid = cand.sessionId();
            if (!UUID_RE.matcher(sid).matches()) {
                continue;
            }
            ResolvedSession loaded = loadCandidate(store, projectKey, sid, timeoutMs);
            if (loaded == null) {
                continue;
            }
            // Skip sidechain sessions — they often have the highest mtime
            // (their append lands after the main session's).
            Map<String, Object> first = loaded.entries().get(0).asMap();
            if (Boolean.TRUE.equals(first.get("isSidechain"))) {
                continue;
            }
            return loaded;
        }
        return null;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws InterruptedException, ExecutionException, TimeoutException;
    }

    private static <T> T withTimeout(ThrowingSupplier<T> task, long timeoutMs, String what) {
        try {
            return task.get();
        } catch (TimeoutException e) {
            throw new RuntimeException(
                    what + " timed out after " + timeoutMs + "ms during resume materialization", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(what + " interrupted during resume materialization", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException(
                    what + " failed during resume materialization: " + cause.getMessage(), cause);
        }
    }

    private static void writeJsonl(Path path, List<SessionStoreEntry> entries) throws IOException {
        Files.createDirectories(path.getParent());
        try (var writer = Files.newBufferedWriter(path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (SessionStoreEntry entry : entries) {
                String line;
                try {
                    line = MAPPER.writeValueAsString(entry.asMap());
                } catch (JsonProcessingException e) {
                    throw new IOException("Failed to serialize entry", e);
                }
                writer.write(line);
                writer.write('\n');
            }
        }
        setOwnerOnlyPermissions(path);
    }

    private static void setOwnerOnlyPermissions(Path path) {
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows or unsupported filesystem — best effort.
        }
    }

    /**
     * Seed {@code tmpBase} with the caller's auth and user config:
     * {@code .credentials.json} (refreshToken redacted), {@code .claude.json},
     * and user {@code settings.json} / {@code cowork_settings.json} (plugin
     * declarations stripped).
     *
     * <p>Source resolution mirrors the CLI:
     * <ul>
     * <li>{@code .credentials.json}, {@code settings.json} and
     * {@code cowork_settings.json} live under the config dir (default
     * {@code ~/.claude/})</li>
     * <li>{@code .claude.json} lives at {@code $CLAUDE_CONFIG_DIR/.claude.json}
     * when set, else {@code ~/.claude.json} (NOT
     * {@code ~/.claude/.claude.json})</li>
     * </ul>
     */
    private static void copyAuthFiles(Path tmpBase, Map<String, String> optEnv) {
        String callerConfigDir = optEnv.get("CLAUDE_CONFIG_DIR");
        if (callerConfigDir == null) {
            callerConfigDir = System.getenv("CLAUDE_CONFIG_DIR");
        }
        Path sourceConfigDir = callerConfigDir != null
                ? Path.of(callerConfigDir)
                : Path.of(System.getProperty("user.home"), ".claude");

        // Copy .credentials.json with refreshToken redacted.
        byte[] credsBytes = readIfPresent(sourceConfigDir.resolve(".credentials.json"));
        String credsJson = credsBytes != null ? new String(credsBytes, StandardCharsets.UTF_8) : null;
        writeRedactedCredentials(credsJson, tmpBase.resolve(".credentials.json"));

        // Copy .claude.json from CLAUDE_CONFIG_DIR or ~/.claude.json (NOT ~/.claude/.claude.json).
        Path claudeJsonSrc = callerConfigDir != null
                ? Path.of(callerConfigDir).resolve(".claude.json")
                : Path.of(System.getProperty("user.home"), ".claude.json");
        copyIfPresent(claudeJsonSrc, tmpBase.resolve(".claude.json"));

        // User settings carry `apiKeyHelper` (a fourth auth mechanism alongside
        // .credentials.json / Keychain / env) plus env/hooks/permissions.
        // Without it the resumed subprocess sees no user settings at all, and an
        // apiKeyHelper-only host fails with "Not logged in". cowork_settings.json
        // is the alternate filename the CLI reads in cowork-plugins mode. Both
        // pass through stripSettingsForResume so plugin declarations don't
        // reconcile against the empty tmpBase plugin cache.
        for (String name : SETTINGS_FILE_NAMES) {
            copyIfPresent(sourceConfigDir.resolve(name), tmpBase.resolve(name),
                    SessionResume::stripSettingsForResume);
        }
    }

    /** User-settings filenames seeded into the temp config dir on resume. */
    private static final List<String> SETTINGS_FILE_NAMES =
            List.of("settings.json", "cowork_settings.json");

    /**
     * User-settings keys that only misbehave under the redirected
     * {@code CLAUDE_CONFIG_DIR}: plugin declarations reconcile against the
     * always-empty {@code tmpBase/plugins} cache and would network-install each
     * declared marketplace on every resume.
     */
    private static final List<String> RESUME_SETTINGS_STRIPPED_KEYS =
            List.of("enabledPlugins", "extraKnownMarketplaces");

    /**
     * Drop settings keys that misbehave under a redirected config dir.
     *
     * <p>Removes {@link #RESUME_SETTINGS_STRIPPED_KEYS} and
     * {@code env.CLAUDE_CONFIG_DIR} (which would point the subprocess's config
     * reads away from {@code tmpBase}). Content that isn't valid UTF-8, or
     * doesn't parse as a JSON object, is returned untouched so the subprocess
     * sees exactly what the CLI would have read.
     */
    @SuppressWarnings("null")
    static byte[] stripSettingsForResume(byte[] content) {
        Map<String, Object> parsed;
        try {
            // Decoding reports malformed input rather than substituting U+FFFD:
            // non-UTF-8 content must be passed through byte-for-byte, not
            // silently rewritten with replacement characters. Mirrors Python,
            // where a UnicodeDecodeError falls back to the original bytes.
            String text = StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(content))
                    .toString();
            // The CLI's settings reader tolerates a UTF-8 BOM (PowerShell
            // writes settings.json with one), which a plain JSON parse rejects.
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            Object value = MAPPER.readValue(text, Object.class);
            if (!(value instanceof Map<?, ?> map)) {
                return content;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> asObject = (Map<String, Object>) map;
            parsed = asObject;
        } catch (java.nio.charset.CharacterCodingException | JsonProcessingException
                | RuntimeException e) {
            return content;
        }

        boolean stripped = false;
        for (String key : RESUME_SETTINGS_STRIPPED_KEYS) {
            if (parsed.containsKey(key)) {
                parsed.remove(key);
                stripped = true;
            }
        }
        if (parsed.get("env") instanceof Map<?, ?> envBlock
                && envBlock.containsKey("CLAUDE_CONFIG_DIR")) {
            envBlock.remove("CLAUDE_CONFIG_DIR");
            stripped = true;
        }
        if (!stripped) {
            return content;
        }
        // A spec-valid overflow like 1e999 parses to infinity, and
        // re-serializing it would produce JSON the CLI rejects. Fall back to
        // the original bytes instead. (Python guards this with allow_nan=False.)
        if (containsNonFiniteNumber(parsed)) {
            return content;
        }
        try {
            return MAPPER.writeValueAsBytes(parsed);
        } catch (JsonProcessingException e) {
            return content;
        }
    }

    /** Whether {@code value} holds a NaN or infinite number at any depth. */
    private static boolean containsNonFiniteNumber(@Nullable Object value) {
        return switch (value) {
            case Double d -> d.isNaN() || d.isInfinite();
            case Float f -> f.isNaN() || f.isInfinite();
            case Map<?, ?> m -> m.values().stream().anyMatch(SessionResume::containsNonFiniteNumber);
            case List<?> l -> l.stream().anyMatch(SessionResume::containsNonFiniteNumber);
            case null, default -> false;
        };
    }

    private static void writeRedactedCredentials(@Nullable String credsJson, Path dst) {
        if (credsJson == null) {
            return;
        }
        String out = credsJson;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) MAPPER.readValue(credsJson, Map.class);
            Object oauth = data.get("claudeAiOauth");
            if (oauth instanceof Map<?, ?> oauthMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mutable = new LinkedHashMap<>((Map<String, Object>) oauthMap);
                if (mutable.remove("refreshToken") != null) {
                    data.put("claudeAiOauth", mutable);
                    out = MAPPER.writeValueAsString(data);
                }
            }
        } catch (JsonProcessingException ignored) {
            // Unparseable — write through; subprocess will fail to parse it too.
        }
        try {
            Files.writeString(dst, out, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            setOwnerOnlyPermissions(dst);
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    /**
     * Read a regular file, or return {@code null}.
     *
     * <p>A missing source is skipped silently. Any other reason it can't be
     * read (permissions, a directory or FIFO where a file was expected, ...) is
     * logged and skipped: these files are best-effort enrichment of the temp
     * config dir, so an unreadable one must not abort — or, for a FIFO, hang —
     * the resume.
     */
    @SuppressWarnings("null")
    private static byte @Nullable [] readIfPresent(Path src) {
        try {
            if (!Files.readAttributes(src, BasicFileAttributes.class).isRegularFile()) {
                logger.warning("[SessionStore] resume: skipping " + src + " (not a regular file)");
                return null;
            }
            return Files.readAllBytes(src);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            logger.warning("[SessionStore] resume: skipping " + src + " (" + e + ")");
            return null;
        }
    }

    private static void copyIfPresent(Path src, Path dst) {
        copyIfPresent(src, dst, null);
    }

    /**
     * Copy {@code src} to {@code dst} (mode {@code 0600}) if it exists, through
     * an optional {@code transform}. See {@link #readIfPresent} for the skip
     * policy.
     */
    private static void copyIfPresent(Path src, Path dst,
            @Nullable UnaryOperator<byte[]> transform) {
        byte[] content = readIfPresent(src);
        if (content == null) {
            return;
        }
        try {
            Files.write(dst, transform != null ? transform.apply(content) : content);
            setOwnerOnlyPermissions(dst);
        } catch (IOException | RuntimeException e) {
            // Don't leave a truncated dst behind for the subprocess to misparse.
            try {
                Files.deleteIfExists(dst);
            } catch (IOException ignored) {
                // Best effort.
            }
            logger.warning("[SessionStore] resume: skipping " + src + " (" + e + ")");
        }
    }

    private static void materializeSubkeys(
            SessionStore store,
            Path projectDir,
            String projectKey,
            String sessionId,
            long timeoutMs) throws IOException {
        Path sessionDir = projectDir.resolve(sessionId);
        SessionListSubkeysKey listKey = new SessionListSubkeysKey(projectKey, sessionId);
        List<String> subkeys = withTimeout(
                () -> store.listSubkeysAsync(listKey).get(timeoutMs, TimeUnit.MILLISECONDS),
                timeoutMs,
                "SessionStore.listSubkeys() for session " + sessionId);
        if (subkeys == null) {
            return;
        }

        for (String subpath : subkeys) {
            if (!isSafeSubpath(subpath, sessionDir)) {
                logger.warning("[SessionStore] skipping unsafe subpath from listSubkeys: "
                        + subpath);
                continue;
            }

            SessionKey subKey = new SessionKey(projectKey, sessionId, subpath);
            List<SessionStoreEntry> subEntries = withTimeout(
                    () -> store.loadAsync(subKey).get(timeoutMs, TimeUnit.MILLISECONDS),
                    timeoutMs,
                    "SessionStore.load() for session " + sessionId + " subpath " + subpath);
            if (subEntries == null || subEntries.isEmpty()) {
                continue;
            }

            // agent_metadata entries describe the .meta.json sidecar (last one
            // wins); everything else is a transcript line.
            SessionStores.AgentMetadataSplit split = SessionStores.splitAgentMetadata(subEntries);
            List<SessionStoreEntry> transcript = split.transcript();

            Path subFile = sessionDir.resolve(subpath + ".jsonl");
            if (!transcript.isEmpty()) {
                writeJsonl(subFile, transcript);
            }

            if (split.metadata() != null) {
                // Strip the synthetic "type" field.
                Map<String, Object> metaContent = new LinkedHashMap<>(split.metadata());
                metaContent.remove("type");
                Path metaFile = Sessions.agentMetadataSidecarPath(subFile);
                Files.createDirectories(metaFile.getParent());
                try {
                    Files.writeString(metaFile, MAPPER.writeValueAsString(metaContent),
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                    setOwnerOnlyPermissions(metaFile);
                } catch (JsonProcessingException ex) {
                    throw new IOException("Failed to serialize agent_metadata", ex);
                }
            }
        }
    }

    /** Reject empty, absolute, or {@code ..}-containing subpaths. */
    static boolean isSafeSubpath(String subpath, Path sessionDir) {
        if (subpath == null || subpath.isEmpty()) {
            return false;
        }
        if (subpath.startsWith("/") || subpath.startsWith("\\")) {
            return false;
        }
        // Drive-prefixed and UNC paths
        if (subpath.length() >= 2 && Character.isLetter(subpath.charAt(0)) && subpath.charAt(1) == ':') {
            return false;
        }
        for (String part : subpath.split("[\\\\/]")) {
            if (".".equals(part) || "..".equals(part)) {
                return false;
            }
        }
        if (subpath.indexOf(' ') >= 0) {
            return false;
        }
        try {
            Path target = sessionDir.resolve(subpath + ".jsonl").toAbsolutePath().normalize();
            Path normalizedSession = sessionDir.toAbsolutePath().normalize();
            return target.startsWith(normalizedSession);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Best-effort recursive removal with retries on transient lock errors.
     */
    static void rmtreeWithRetry(Path path, int retries, long delayMs) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        for (int i = 0; i < retries; i++) {
            try {
                deleteRecursive(path);
                return;
            } catch (IOException e) {
                // Retry on Windows AV/indexer transient locks.
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                deleteRecursiveQuietly(path);
                return;
            }
        }
        deleteRecursiveQuietly(path);
    }

    private static void deleteRecursive(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                List<Path> children = stream.toList();
                for (Path child : children) {
                    deleteRecursive(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    private static void deleteRecursiveQuietly(Path path) {
        try {
            deleteRecursive(path);
        } catch (IOException ignored) {
            // Final fallback — give up.
        }
    }

}
