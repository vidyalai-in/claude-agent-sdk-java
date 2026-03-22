package in.vidyalai.claude.sdk.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;

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

    private static final Pattern SKIP_FIRST_PROMPT_PATTERN = Pattern.compile(
            "^(?:<local-command-stdout>|<local-command-stderr>|<local-command-control>" +
                    "|<bash-input>|<bash-stdout>|<bash-stderr>)");

    private static final Pattern COMMAND_NAME_RE = Pattern.compile(
            "<command-name>(.*?)</command-name>");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Sessions() {
    }

    // -------------------------------------------------------------------------
    // Path helpers
    // -------------------------------------------------------------------------

    static Path getClaudeConfigHomeDir() {
        String envVar = System.getenv("CLAUDE_CONFIG_DIR");
        if (envVar != null && !envVar.isBlank()) {
            return Path.of(envVar);
        }
        return Path.of(System.getProperty("user.home"), ".claude");
    }

    static Path getProjectsDir() {
        return getClaudeConfigHomeDir().resolve("projects");
    }

    static Path getProjectDir(String path) {
        return getProjectsDir().resolve(sanitizePath(path));
    }

    static @Nullable Path findProjectDir(String path) {
        Path exact = getProjectDir(path);
        if (Files.exists(exact)) {
            return exact;
        }
        // Fallback prefix-scan for long paths where sanitization may differ
        Path projectsDir = getProjectsDir();
        if (!Files.exists(projectsDir)) {
            return null;
        }
        String rawSanitized = SANITIZE_RE.matcher(path).replaceAll("-");
        int prefixLen = Math.min(50, rawSanitized.length());
        String prefix = rawSanitized.substring(0, prefixLen);
        try (Stream<Path> children = Files.list(projectsDir)) {
            return children
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
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
     * Hash function that exactly mirrors the Python/JavaScript SDK implementation.
     * Uses signed 32-bit integer overflow: {@code h = (h << 5) - h + c}.
     */
    static String simpleHash(String s) {
        int h = 0;
        for (char c : s.toCharArray()) {
            h = (h << 5) - h + c;
        }
        return Integer.toString(Math.abs(h), 36);
    }

    // -------------------------------------------------------------------------
    // Lightweight file read
    // -------------------------------------------------------------------------

    record LiteSessionFile(long mtime, long size, String head, String tail) {
    }

    static @Nullable LiteSessionFile readSessionLite(Path file) {
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);

            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);

            String head;
            String tail;

            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                int headSize = (int) Math.min(size, LITE_READ_BUF_SIZE);
                ByteBuffer headBuf = ByteBuffer.allocate(headSize);
                channel.read(headBuf, 0);
                headBuf.flip();
                head = decoder.decode(headBuf).toString();

                if (size <= LITE_READ_BUF_SIZE) {
                    tail = head;
                } else {
                    long tailStart = size - LITE_READ_BUF_SIZE;
                    ByteBuffer tailBuf = ByteBuffer.allocate(LITE_READ_BUF_SIZE);
                    channel.read(tailBuf, tailStart);
                    tailBuf.flip();
                    decoder.reset();
                    tail = decoder.decode(tailBuf).toString();
                }
            }

            return new LiteSessionFile(mtime, size, head, tail);
        } catch (IOException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Regex-based JSON field extraction (avoids full parse on hot path)
    // -------------------------------------------------------------------------

    /**
     * Extracts the first occurrence of a JSON string field from raw text.
     */
    static @Nullable String extractJsonStringField(String text, String key) {
        return extractJsonStringFieldInternal(text, key, false);
    }

    /**
     * Extracts the last occurrence of a JSON string field from raw text.
     */
    static @Nullable String extractLastJsonStringField(String text, String key) {
        return extractJsonStringFieldInternal(text, key, true);
    }

    private static @Nullable String extractJsonStringFieldInternal(
            String text, String key, boolean last) {
        String searchKey = "\"" + key + "\"";
        int searchFrom = 0;
        int bestValueStart = -1;

        while (true) {
            int keyIdx = text.indexOf(searchKey, searchFrom);
            if (keyIdx < 0)
                break;

            int afterKey = keyIdx + searchKey.length();

            // Find colon
            int colon = -1;
            for (int i = afterKey; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == ':') {
                    colon = i;
                    break;
                }
                if (!Character.isWhitespace(c))
                    break;
            }
            if (colon < 0) {
                searchFrom = keyIdx + 1;
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
                if (!Character.isWhitespace(c))
                    break;
            }
            if (quote < 0) {
                searchFrom = keyIdx + 1;
                continue;
            }

            bestValueStart = quote + 1;
            if (!last)
                break;
            searchFrom = keyIdx + 1;
        }

        if (bestValueStart < 0)
            return null;

        // Read value until unescaped closing quote
        StringBuilder sb = new StringBuilder();
        int i = bestValueStart;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"')
                break;
            if (c == '\\' && i + 1 < text.length()) {
                sb.append(c).append(text.charAt(i + 1));
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }

        return unescapeJsonString(sb.toString());
    }

    static String unescapeJsonString(String raw) {
        try {
            return MAPPER.readValue("\"" + raw + "\"", String.class);
        } catch (Exception e) {
            return raw.replace("\\n", "\n").replace("\\t", "\t")
                    .replace("\\\"", "\"").replace("\\\\", "\\")
                    .replace("\\/", "/");
        }
    }

    // -------------------------------------------------------------------------
    // First prompt extraction
    // -------------------------------------------------------------------------

    static String extractFirstPromptFromHead(String head) {
        String slashCommandFallback = null;

        try (BufferedReader reader = new BufferedReader(new StringReader(head))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty())
                    continue;

                // Quick pre-checks before JSON parse
                if (!line.contains("\"user\""))
                    continue;
                if (line.contains("tool_use_id") || line.contains("\"tool_result\""))
                    continue;
                if (line.contains("\"isMeta\":true") || line.contains("\"isMeta\": true"))
                    continue;
                if (line.contains("\"isCompact\":true") || line.contains("\"isCompact\": true"))
                    continue;

                try {
                    @SuppressWarnings({ "unchecked", "null" })
                    Map<String, Object> entry = MAPPER.readValue(line, Map.class);
                    if (!"user".equals(entry.get("type")))
                        continue;

                    Object message = entry.get("message");
                    if (!(message instanceof Map<?, ?> msg))
                        continue;

                    Object content = msg.get("content");
                    String text = null;

                    if (content instanceof String s) {
                        text = s;
                    } else if (content instanceof List<?> list) {
                        for (Object block : list) {
                            if (!(block instanceof Map<?, ?> b))
                                continue;
                            if ("text".equals(b.get("type")) && b.get("text") instanceof String s) {
                                text = s;
                                break;
                            }
                        }
                    }

                    if (text == null || text.isBlank())
                        continue;
                    if (SKIP_FIRST_PROMPT_PATTERN.matcher(text).find())
                        continue;

                    if (text.startsWith("/")) {
                        Matcher m = COMMAND_NAME_RE.matcher(text);
                        String cmdName = m.find() ? m.group(1) : text.split("\\s+")[0];
                        if (slashCommandFallback == null) {
                            slashCommandFallback = truncatePrompt(cmdName);
                        }
                        continue;
                    }

                    return truncatePrompt(text);
                } catch (Exception e) {
                    // Skip malformed lines
                }
            }
        } catch (IOException e) {
            // StringReader never throws
        }

        return slashCommandFallback != null ? slashCommandFallback : "";
    }

    private static String truncatePrompt(String text) {
        if (text.length() <= MAX_SANITIZED_LENGTH)
            return text;
        return text.substring(0, MAX_SANITIZED_LENGTH) + "\u2026";
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
        String customTitle = extractLastJsonStringField(tail, "customTitle");
        if (customTitle == null) {
            customTitle = extractLastJsonStringField(head, "customTitle");
        }
        if (customTitle == null) {
            customTitle = extractLastJsonStringField(tail, "aiTitle");
            if (customTitle == null) {
                customTitle = extractLastJsonStringField(head, "aiTitle");
            }
        }

        String firstPrompt = extractFirstPromptFromHead(head);
        if (firstPrompt != null && firstPrompt.isEmpty()) {
            firstPrompt = null;
        }

        // Summary priority: custom title > lastPrompt > summary > first prompt
        String displaySummary = customTitle;
        if (displaySummary == null || displaySummary.isBlank()) {
            displaySummary = extractLastJsonStringField(tail, "lastPrompt");
        }
        if (displaySummary == null || displaySummary.isBlank()) {
            displaySummary = extractLastJsonStringField(tail, "summary");
        }
        if (displaySummary == null || displaySummary.isBlank()) {
            displaySummary = firstPrompt;
        }

        // Skip metadata-only sessions (no title, no summary, no prompt)
        if (displaySummary == null || displaySummary.isBlank()) {
            return null;
        }

        String gitBranch = extractLastJsonStringField(tail, "gitBranch");
        if (gitBranch == null) {
            gitBranch = extractJsonStringField(head, "gitBranch");
        }
        String cwd = extractJsonStringField(head, "cwd");
        if (cwd == null) {
            cwd = projectPath;
        }

        // Tag extraction scoped to {"type":"tag"} lines to avoid matching
        // tool_use inputs (git tag, Docker tags, etc.)
        String tag = extractTagFromTail(tail);

        // created_at from first entry's ISO timestamp (epoch ms)
        Long createdAt = extractCreatedAtFromFirstLine(firstLine);

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
     * Extracts created_at timestamp from the first line's ISO timestamp field.
     */
    static @Nullable Long extractCreatedAtFromFirstLine(String firstLine) {
        String timestamp = extractJsonStringField(firstLine, "timestamp");
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
        if (!Files.exists(projectDir)) {
            return List.of();
        }

        List<SDKSessionInfo> sessions = new ArrayList<>();

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
        } catch (IOException e) {
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

            Thread reader = Thread.ofVirtual().start(() -> {
                try (var scanner = new java.util.Scanner(process.getInputStream())) {
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
        sessions.sort(Comparator.comparingLong(SDKSessionInfo::lastModified).reversed());
        if (limit != null && sessions.size() > limit) {
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

        List<SDKSessionInfo> allSessions = new ArrayList<>();

        if (directory != null) {
            Path projectDir = findProjectDir(directory);
            if (projectDir != null) {
                allSessions.addAll(readSessionsFromDir(projectDir, directory));
            }

            if (includeWorktrees) {
                List<String> worktreePaths = getWorktreePaths(directory);
                for (String worktreePath : worktreePaths) {
                    if (!worktreePath.equals(directory)) {
                        Path worktreeDir = findProjectDir(worktreePath);
                        if (worktreeDir != null) {
                            allSessions.addAll(readSessionsFromDir(worktreeDir, worktreePath));
                        }
                    }
                }
            }
        } else {
            Path projectsDir = getProjectsDir();
            if (Files.exists(projectsDir)) {
                try (Stream<Path> dirs = Files.list(projectsDir)) {
                    dirs.filter(Files::isDirectory)
                            .forEach(dir -> allSessions.addAll(readSessionsFromDir(dir, null)));
                } catch (IOException e) {
                    // Return what we have
                }
            }
        }

        List<SDKSessionInfo> deduped = deduplicateBySessionId(allSessions);
        return applySortAndLimit(new ArrayList<>(deduped), limit);
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

        if (directory != null) {
            Path projectDir = findProjectDir(directory);
            if (projectDir != null) {
                LiteSessionFile lite = readSessionLite(projectDir.resolve(filename));
                if (lite != null) {
                    return parseSessionInfoFromLite(sessionId, lite, directory);
                }
            }

            // Worktree fallback — matches getSessionMessages semantics
            List<String> worktreePaths;
            try {
                worktreePaths = getWorktreePaths(directory);
            } catch (Exception e) {
                worktreePaths = List.of();
            }
            for (String wt : worktreePaths) {
                if (wt.equals(directory)) {
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

        if (offset > 0) {
            if (offset >= messages.size())
                return List.of();
            messages = new ArrayList<>(messages.subList(offset, messages.size()));
        }

        if (limit != null && messages.size() > limit) {
            messages = new ArrayList<>(messages.subList(0, limit));
        }

        return Collections.unmodifiableList(messages);
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
                try {
                    @SuppressWarnings({ "unchecked", "null" })
                    Map<String, Object> entry = MAPPER.readValue(line, Map.class);
                    String type = (String) entry.get("type");
                    if (type == null || !validTypes.contains(type))
                        continue;
                    if (entry.get("uuid") == null)
                        continue;
                    entries.add(entry);
                } catch (Exception e) {
                    // Skip corrupt lines
                }
            }
        } catch (IOException e) {
            // StringReader never throws
        }

        return entries;
    }

    static List<Map<String, Object>> buildConversationChain(List<Map<String, Object>> entries) {
        if (entries.isEmpty())
            return List.of();

        // Build maps for O(1) lookup
        Map<String, Map<String, Object>> byUuid = new LinkedHashMap<>();
        Map<String, Integer> indexByUuid = new HashMap<>();
        Set<String> parentUuids = new HashSet<>();

        for (int i = 0; i < entries.size(); i++) {
            Map<String, Object> entry = entries.get(i);
            String uuid = (String) entry.get("uuid");
            if (uuid != null) {
                byUuid.put(uuid, entry);
                indexByUuid.put(uuid, i);
                String parentUuid = (String) entry.get("parentUuid");
                if (parentUuid != null) {
                    parentUuids.add(parentUuid);
                }
            }
        }

        // Terminals: entries whose uuid is not a parentUuid of any other entry
        List<Map<String, Object>> terminals = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            String uuid = (String) entry.get("uuid");
            if (uuid != null && !parentUuids.contains(uuid)) {
                terminals.add(entry);
            }
        }

        // Walk back from each terminal to find the best main-chain leaf
        Map<String, Object> bestLeaf = null;
        int bestIndex = -1;

        for (Map<String, Object> terminal : terminals) {
            Map<String, Object> candidate = terminal;
            while (candidate != null) {
                String type = (String) candidate.get("type");
                boolean isSidechain = Boolean.TRUE.equals(candidate.get("isSidechain"));
                boolean isMeta = Boolean.TRUE.equals(candidate.get("isMeta"));
                boolean hasTeamName = candidate.get("teamName") != null;

                if (("user".equals(type) || "assistant".equals(type))
                        && !isSidechain && !isMeta && !hasTeamName) {
                    String candidateUuid = (String) candidate.get("uuid");
                    int idx = indexByUuid.getOrDefault(candidateUuid, -1);
                    if (idx > bestIndex) {
                        bestIndex = idx;
                        bestLeaf = candidate;
                    }
                    break;
                }

                String parentUuid = (String) candidate.get("parentUuid");
                candidate = parentUuid != null ? byUuid.get(parentUuid) : null;
            }
        }

        if (bestLeaf == null)
            return List.of();

        // Walk from bestLeaf to root, collecting the chain
        List<Map<String, Object>> chain = new ArrayList<>();
        Map<String, Object> current = bestLeaf;
        Set<String> visited = new HashSet<>();

        while (current != null) {
            String uuid = (String) current.get("uuid");
            if (uuid == null || !visited.add(uuid))
                break; // cycle guard
            chain.add(current);
            String parentUuid = (String) current.get("parentUuid");
            current = parentUuid != null ? byUuid.get(parentUuid) : null;
        }

        Collections.reverse(chain);
        return chain;
    }

    static boolean isVisibleMessage(Map<String, Object> entry) {
        String type = (String) entry.get("type");
        if (!"user".equals(type) && !"assistant".equals(type))
            return false;
        if (Boolean.TRUE.equals(entry.get("isMeta")))
            return false;
        if (Boolean.TRUE.equals(entry.get("isSidechain")))
            return false;
        if (entry.get("teamName") != null)
            return false;
        return true;
    }

    static SessionMessage toSessionMessage(Map<String, Object> entry) {
        String type = (String) entry.get("type");
        String uuid = (String) entry.get("uuid");
        String sessionId = (String) entry.get("sessionId");
        if (sessionId == null)
            sessionId = "";
        Object message = entry.get("message");
        String parentToolUseId = (String) entry.get("parentToolUseId");
        return new SessionMessage(type, uuid, sessionId, message, parentToolUseId);
    }

    static @Nullable String readSessionFile(String sessionId, @Nullable String directory) {
        String filename = sessionId + ".jsonl";

        if (directory != null) {
            Path projectDir = findProjectDir(directory);
            if (projectDir != null) {
                Path file = projectDir.resolve(filename);
                if (Files.exists(file)) {
                    return readFileContent(file);
                }
            }

            List<String> worktreePaths = getWorktreePaths(directory);
            for (String worktreePath : worktreePaths) {
                if (!worktreePath.equals(directory)) {
                    Path worktreeDir = findProjectDir(worktreePath);
                    if (worktreeDir != null) {
                        Path file = worktreeDir.resolve(filename);
                        if (Files.exists(file)) {
                            return readFileContent(file);
                        }
                    }
                }
            }
        } else {
            Path projectsDir = getProjectsDir();
            if (Files.exists(projectsDir)) {
                try (Stream<Path> dirs = Files.list(projectsDir)) {
                    Optional<Path> found = dirs
                            .filter(Files::isDirectory)
                            .map(dir -> dir.resolve(filename))
                            .filter(Files::exists)
                            .findFirst();
                    if (found.isPresent()) {
                        return readFileContent(found.get());
                    }
                } catch (IOException e) {
                    return null;
                }
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
}
