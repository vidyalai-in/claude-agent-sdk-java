package in.vidyalai.claude.sdk.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionStore;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;

/**
 * Replay a local on-disk session transcript into a {@link SessionStore}.
 *
 * <p>This is the inverse of {@link SessionResume} — where
 * {@link SessionResume#materializeResumeSession(in.vidyalai.claude.sdk.ClaudeAgentOptions)}
 * reads a store and writes a temp {@code ~/.claude} tree,
 * {@link #importSessionToStore} reads the local
 * {@code ~/.claude/projects/<dir>/<sessionId>.jsonl} (plus subagent
 * transcripts) and replays each line into {@code store.append(...)}.
 *
 * <p>Mirrors Python SDK's {@code session_import.py}.
 */
public final class SessionImport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern UUID_RE = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);

    private SessionImport() {
    }

    /**
     * Replay a local session transcript into a {@link SessionStore}.
     *
     * <p>Streams the on-disk JSONL line-by-line and calls
     * {@code store.append(key, batch)} every {@code batchSize} entries (or
     * 1 MiB of line bytes, whichever comes first). Useful for migrating
     * existing local sessions to a remote store, or for catching a store up
     * after a {@code MirrorErrorMessage} indicated a live-mirror gap.
     * Adapters should treat {@code entry.uuid()} as an idempotency key so
     * re-import is duplicate-safe.
     */
    public static void importSessionToStore(
            String sessionId,
            SessionStore store,
            @Nullable String directory,
            boolean includeSubagents,
            int batchSize) throws IOException {
        if (!UUID_RE.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("Invalid session_id: " + sessionId);
        }
        Path resolved = Sessions.resolveSessionFilePath(sessionId, directory);
        if (resolved == null) {
            throw new NoSuchFileException("Session " + sessionId + " not found");
        }
        // Project key = on-disk project directory name. Matches what
        // file_path_to_session_key would produce for the same path.
        String projectKey = resolved.getParent().getFileName().toString();
        if (batchSize <= 0) {
            batchSize = TranscriptMirrorBatcher.MAX_PENDING_ENTRIES;
        }

        SessionKey mainKey = new SessionKey(projectKey, sessionId, null);
        appendJsonlInBatches(resolved, mainKey, store, batchSize);

        if (!includeSubagents) {
            return;
        }

        // Subagent transcripts live at <projectDir>/<sessionId>/subagents/**.
        Path sessionDir = stripExtension(resolved);
        Path subagentsDir = sessionDir.resolve("subagents");
        for (Path filePath : collectJsonlFiles(subagentsDir)) {
            // subpath = path relative to sessionDir, '/'-joined, sans .jsonl
            Path rel = sessionDir.relativize(filePath);
            List<String> relParts = new ArrayList<>();
            for (int i = 0; i < rel.getNameCount(); i++) {
                relParts.add(rel.getName(i).toString());
            }
            String last = relParts.get(relParts.size() - 1);
            if (last.endsWith(".jsonl")) {
                relParts.set(relParts.size() - 1, last.substring(0, last.length() - 6));
            }
            String subpath = String.join("/", relParts);
            SessionKey subKey = new SessionKey(projectKey, sessionId, subpath);
            appendJsonlInBatches(filePath, subKey, store, batchSize);

            // Import the .meta.json sidecar so resume can recreate it.
            Path metaPath = filePath.resolveSibling(
                    filePath.getFileName().toString().replaceAll("\\.jsonl$", ".meta.json"));
            try {
                String metaText = Files.readString(metaPath, StandardCharsets.UTF_8);
                @SuppressWarnings("unchecked")
                Map<String, Object> metaMap = MAPPER.readValue(metaText, Map.class);
                Map<String, Object> entry = new java.util.LinkedHashMap<>();
                entry.put("type", "agent_metadata");
                entry.putAll(metaMap);
                store.append(subKey, List.of(SessionStoreEntry.of(entry)));
            } catch (NoSuchFileException ignored) {
                // No sidecar — fine.
            } catch (JsonProcessingException e) {
                throw new IOException("Failed to parse " + metaPath, e);
            }
        }
    }

    /**
     * Convenience overload with default options: include subagents,
     * default batch size of {@link TranscriptMirrorBatcher#MAX_PENDING_ENTRIES}.
     */
    public static void importSessionToStore(
            String sessionId, SessionStore store, @Nullable String directory)
            throws IOException {
        importSessionToStore(sessionId, store, directory, true, TranscriptMirrorBatcher.MAX_PENDING_ENTRIES);
    }

    private static Path stripExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return path;
        }
        return path.resolveSibling(name.substring(0, dot));
    }

    private static void appendJsonlInBatches(
            Path filePath,
            SessionKey key,
            SessionStore store,
            int batchSize) throws IOException {
        List<SessionStoreEntry> batch = new ArrayList<>();
        int nbytes = 0;
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = MAPPER.readValue(line, Map.class);
                    batch.add(SessionStoreEntry.of(map));
                    nbytes += line.length();
                } catch (JsonProcessingException e) {
                    throw new IOException("Failed to parse line in " + filePath + ": " + e.getMessage(), e);
                }
                if (batch.size() >= batchSize || nbytes >= TranscriptMirrorBatcher.MAX_PENDING_BYTES) {
                    store.append(key, batch);
                    batch = new ArrayList<>();
                    nbytes = 0;
                }
            }
        }
        if (!batch.isEmpty()) {
            store.append(key, batch);
        }
    }

    private static List<Path> collectJsonlFiles(Path baseDir) {
        List<Path> result = new ArrayList<>();
        if (!Files.exists(baseDir)) {
            return result;
        }
        collectJsonlFilesRecursive(baseDir, result);
        return result;
    }

    private static void collectJsonlFilesRecursive(Path dir, List<Path> result) {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> entries = stream
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    collectJsonlFilesRecursive(entry, result);
                } else if (Files.isRegularFile(entry)
                        && entry.getFileName().toString().endsWith(".jsonl")) {
                    result.add(entry);
                }
            }
        } catch (IOException ignored) {
            // Best-effort
        }
    }

}
