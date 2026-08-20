package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;
import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionStore;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;

/**
 * Tests for {@link SessionImport}. Uses {@code user.home} override to point
 * the SDK at a per-test temp directory rather than touching the real
 * {@code ~/.claude}. The override is restored after each test.
 *
 * <p>Skipped automatically if {@code CLAUDE_CONFIG_DIR} is set in the
 * environment (which would override {@code user.home}); the SDK's
 * {@link Sessions#getClaudeConfigHomeDir()} consults the env var first.
 */
class SessionImportTest {

    @TempDir
    Path tempDir;

    private String originalUserHome;

    @BeforeEach
    void overrideHome() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void restoreHome() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    private static String json(String type, String uuid, String content) {
        return "{\"type\":\"" + type + "\",\"uuid\":\"" + uuid
                + "\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"" + content + "\"}]}}";
    }

    /** Layout: {tempDir}/.claude/projects/{projectKey}/{sessionId}.jsonl */
    private Path writeMainTranscript(String projectKey, String sessionId, List<String> lines) throws IOException {
        Path projectsDir = tempDir.resolve(".claude").resolve("projects").resolve(projectKey);
        Files.createDirectories(projectsDir);
        Path file = projectsDir.resolve(sessionId + ".jsonl");
        Files.writeString(file, String.join("\n", lines) + "\n");
        return file;
    }

    private static boolean hasEnvOverride() {
        String env = System.getenv("CLAUDE_CONFIG_DIR");
        return env != null && !env.isBlank();
    }

    @SuppressWarnings("null")
    @Test
    void importsMainTranscript() throws Exception {
        if (hasEnvOverride()) {
            return; // skip when env overrides user.home
        }
        String sessionId = UUID.randomUUID().toString();
        String projectKey = "myproj";
        writeMainTranscript(projectKey, sessionId, List.of(
                json("user", "u1", "First"),
                json("user", "u2", "Second")));

        InMemorySessionStore store = new InMemorySessionStore();
        SessionImport.importSessionToStore(sessionId, store, null, false, 0);

        SessionKey key = new SessionKey(projectKey, sessionId, null);
        List<SessionStoreEntry> loaded = store.load(key);
        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).uuid()).isEqualTo("u1");
        assertThat(loaded.get(1).uuid()).isEqualTo("u2");
    }

    @Test
    void batchingCallsAppendPerChunk() throws Exception {
        if (hasEnvOverride()) {
            return;
        }
        String sessionId = UUID.randomUUID().toString();
        String projectKey = "myproj";
        // 7 entries with batchSize=3 → 3 batches: [3, 3, 1]
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            lines.add(json("user", "u" + i, "msg-" + i));
        }
        writeMainTranscript(projectKey, sessionId, lines);

        AtomicInteger calls = new AtomicInteger();
        ConcurrentHashMap<SessionKey, List<SessionStoreEntry>> received = new ConcurrentHashMap<>();
        SessionStore counting = new SessionStore() {
            @Override
            public void append(SessionKey key, List<SessionStoreEntry> entries) {
                calls.incrementAndGet();
                received.computeIfAbsent(key, k -> new ArrayList<>()).addAll(entries);
            }

            @Override
            public List<SessionStoreEntry> load(SessionKey key) {
                return received.get(key);
            }
        };

        SessionImport.importSessionToStore(sessionId, counting, null, false, 3);
        assertThat(calls.get()).isEqualTo(3);
        assertThat(received.values().iterator().next()).hasSize(7);
    }

    @Test
    void skipsBlankLines() throws Exception {
        if (hasEnvOverride()) {
            return;
        }
        String sessionId = UUID.randomUUID().toString();
        String projectKey = "myproj";
        writeMainTranscript(projectKey, sessionId, List.of(
                json("user", "u1", "First"),
                "",
                json("user", "u2", "Second"),
                "",
                ""));

        InMemorySessionStore store = new InMemorySessionStore();
        SessionImport.importSessionToStore(sessionId, store, null, false, 0);

        assertThat(store.load(new SessionKey(projectKey, sessionId, null))).hasSize(2);
    }

    @SuppressWarnings("null")
    @Test
    void invalidUuidRaises() {
        InMemorySessionStore store = new InMemorySessionStore();
        assertThatThrownBy(
                () -> SessionImport.importSessionToStore("not-a-uuid", store, null, true, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid session_id");
    }

    @Test
    void sessionNotFoundRaises() throws Exception {
        if (hasEnvOverride()) {
            return;
        }
        InMemorySessionStore store = new InMemorySessionStore();
        String missing = UUID.randomUUID().toString();
        assertThatThrownBy(
                () -> SessionImport.importSessionToStore(missing, store, null, true, 100))
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void importsSubagentTranscriptsWithSubpath() throws Exception {
        if (hasEnvOverride()) {
            return;
        }
        String sessionId = UUID.randomUUID().toString();
        String projectKey = "myproj";
        writeMainTranscript(projectKey, sessionId, List.of(json("user", "u1", "main")));

        Path subagentsDir = tempDir.resolve(".claude").resolve("projects")
                .resolve(projectKey).resolve(sessionId).resolve("subagents");
        Files.createDirectories(subagentsDir);
        Path subFile = subagentsDir.resolve("agent-x.jsonl");
        Files.writeString(subFile, json("user", "a1", "sub-msg") + "\n");

        InMemorySessionStore store = new InMemorySessionStore();
        SessionImport.importSessionToStore(sessionId, store, null);

        SessionKey subKey = new SessionKey(projectKey, sessionId, "subagents/agent-x");
        assertThat(store.load(subKey)).hasSize(1);
    }

    @Test
    void includeSubagentsFalseSkipsSubagents() throws Exception {
        if (hasEnvOverride()) {
            return;
        }
        String sessionId = UUID.randomUUID().toString();
        String projectKey = "myproj";
        writeMainTranscript(projectKey, sessionId, List.of(json("user", "u1", "main")));

        Path subagentsDir = tempDir.resolve(".claude").resolve("projects")
                .resolve(projectKey).resolve(sessionId).resolve("subagents");
        Files.createDirectories(subagentsDir);
        Files.writeString(subagentsDir.resolve("agent-x.jsonl"),
                json("user", "a1", "sub-msg") + "\n");

        InMemorySessionStore store = new InMemorySessionStore();
        SessionImport.importSessionToStore(sessionId, store, null, false, 0);

        assertThat(store.load(new SessionKey(projectKey, sessionId, "subagents/agent-x"))).isNull();
        assertThat(store.load(new SessionKey(projectKey, sessionId, null))).hasSize(1);
    }

    @SuppressWarnings("null")
    @Test
    void importsMetaJsonSidecarAsAgentMetadata() throws Exception {
        if (hasEnvOverride()) {
            return;
        }
        String sessionId = UUID.randomUUID().toString();
        String projectKey = "myproj";
        writeMainTranscript(projectKey, sessionId, List.of(json("user", "u1", "main")));

        Path subagentsDir = tempDir.resolve(".claude").resolve("projects")
                .resolve(projectKey).resolve(sessionId).resolve("subagents");
        Files.createDirectories(subagentsDir);
        Files.writeString(subagentsDir.resolve("agent-x.jsonl"),
                json("user", "a1", "sub-msg") + "\n");
        Files.writeString(subagentsDir.resolve("agent-x.meta.json"),
                "{\"agentType\":\"general-purpose\",\"worktreePath\":\"/tmp/wt\"}");

        InMemorySessionStore store = new InMemorySessionStore();
        SessionImport.importSessionToStore(sessionId, store, null);

        SessionKey subKey = new SessionKey(projectKey, sessionId, "subagents/agent-x");
        List<SessionStoreEntry> loaded = store.load(subKey);
        assertThat(loaded).hasSize(2);
        SessionStoreEntry meta = loaded.get(1);
        assertThat(meta.type()).isEqualTo("agent_metadata");
        assertThat(meta.<String>get("agentType")).isEqualTo("general-purpose");
        assertThat(meta.<String>get("worktreePath")).isEqualTo("/tmp/wt");
    }

    @SuppressWarnings("null")
    @Test
    void metaJsonTypeKeyCannotShadowTheAgentMetadataMarker() throws Exception {
        if (hasEnvOverride()) {
            return;
        }
        String sessionId = UUID.randomUUID().toString();
        String projectKey = "myproj";
        writeMainTranscript(projectKey, sessionId, List.of(json("user", "u1", "main")));

        Path subagentsDir = tempDir.resolve(".claude").resolve("projects")
                .resolve(projectKey).resolve(sessionId).resolve("subagents");
        Files.createDirectories(subagentsDir);
        Files.writeString(subagentsDir.resolve("agent-x.jsonl"),
                json("user", "a1", "sub-msg") + "\n");
        // The sidecar is CLI-owned: a stray "type" key must not turn the
        // synthetic marker into something the reader treats as a transcript
        // line.
        Files.writeString(subagentsDir.resolve("agent-x.meta.json"),
                "{\"type\":\"user\",\"agentType\":\"general-purpose\"}");

        InMemorySessionStore store = new InMemorySessionStore();
        SessionImport.importSessionToStore(sessionId, store, null);

        SessionKey subKey = new SessionKey(projectKey, sessionId, "subagents/agent-x");
        List<SessionStoreEntry> loaded = store.load(subKey);
        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(1).type()).isEqualTo("agent_metadata");
        assertThat(loaded.get(1).<String>get("agentType")).isEqualTo("general-purpose");
    }

    @Test
    void unusableMetaJsonSidecarIsTreatedAsAbsent() throws Exception {
        if (hasEnvOverride()) {
            return;
        }
        String sessionId = UUID.randomUUID().toString();
        String projectKey = "myproj";
        writeMainTranscript(projectKey, sessionId, List.of(json("user", "u1", "main")));

        Path subagentsDir = tempDir.resolve(".claude").resolve("projects")
                .resolve(projectKey).resolve(sessionId).resolve("subagents");
        Files.createDirectories(subagentsDir);
        Files.writeString(subagentsDir.resolve("agent-bad.jsonl"),
                json("user", "a1", "sub-msg") + "\n");
        Files.writeString(subagentsDir.resolve("agent-bad.meta.json"), "{not json");
        Files.writeString(subagentsDir.resolve("agent-arr.jsonl"),
                json("user", "a2", "sub-msg") + "\n");
        Files.writeString(subagentsDir.resolve("agent-arr.meta.json"), "[1,2]");

        InMemorySessionStore store = new InMemorySessionStore();
        // A corrupt or non-object sidecar must not fail the import; the
        // transcript still lands.
        SessionImport.importSessionToStore(sessionId, store, null);

        assertThat(store.load(new SessionKey(projectKey, sessionId, "subagents/agent-bad")))
                .hasSize(1);
        assertThat(store.load(new SessionKey(projectKey, sessionId, "subagents/agent-arr")))
                .hasSize(1);
    }

    @Test
    void nonpositiveBatchSizeUsesDefault() throws Exception {
        if (hasEnvOverride()) {
            return;
        }
        String sessionId = UUID.randomUUID().toString();
        String projectKey = "myproj";
        writeMainTranscript(projectKey, sessionId, List.of(json("user", "u1", "First")));
        InMemorySessionStore store = new InMemorySessionStore();
        SessionImport.importSessionToStore(sessionId, store, null, false, 0);
        assertThat(store.load(new SessionKey(projectKey, sessionId, null))).hasSize(1);
    }

}
