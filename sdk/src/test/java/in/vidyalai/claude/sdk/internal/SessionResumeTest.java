package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.internal.SessionResume.MaterializedResume;
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;
import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;

class SessionResumeTest {

    private static SessionStoreEntry userEntry(String uuid, String text, String ts) {
        Map<String, Object> message = Map.of(
                "content", List.of(Map.of("type", "text", "text", text)));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "user");
        entry.put("uuid", uuid);
        entry.put("sessionId", "ignored");
        entry.put("timestamp", ts);
        entry.put("message", message);
        return SessionStoreEntry.of(entry);
    }

    @Test
    void materializeResumeSession_returnsNullWhenNoStore() throws IOException {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .resume(UUID.randomUUID().toString())
                .build();
        assertThat(SessionResume.materializeResumeSession(options)).isNull();
    }

    @Test
    void materializeResumeSession_returnsNullWhenNoResumeOrContinue() throws IOException {
        InMemorySessionStore store = new InMemorySessionStore();
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sessionStore(store)
                .build();
        assertThat(SessionResume.materializeResumeSession(options)).isNull();
    }

    @Test
    void materializeResumeSession_returnsNullForInvalidUuid() throws IOException {
        InMemorySessionStore store = new InMemorySessionStore();
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sessionStore(store)
                .resume("not-a-uuid")
                .build();
        assertThat(SessionResume.materializeResumeSession(options)).isNull();
    }

    @Test
    void materializeResumeSession_writesJsonlFromStoreEntries() throws IOException {
        InMemorySessionStore store = new InMemorySessionStore();
        String sessionId = UUID.randomUUID().toString();
        String projectKey = SessionStores.projectKeyForDirectory(null);
        SessionKey key = new SessionKey(projectKey, sessionId, null);
        store.append(key, List.of(
                userEntry("u1", "Hello", "2026-04-27T00:00:00Z"),
                userEntry("u2", "World", "2026-04-27T00:00:01Z")));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sessionStore(store)
                .resume(sessionId)
                .build();

        MaterializedResume result = SessionResume.materializeResumeSession(options);
        assertThat(result).isNotNull();
        try {
            Path jsonlFile = result.configDir()
                    .resolve("projects").resolve(projectKey).resolve(sessionId + ".jsonl");
            assertThat(jsonlFile).exists();
            List<String> lines = Files.readAllLines(jsonlFile);
            assertThat(lines).hasSize(2);
            assertThat(lines.get(0)).contains("\"u1\"");
            assertThat(lines.get(1)).contains("\"u2\"");

            // applyMaterializedOptions wires CLAUDE_CONFIG_DIR into env
            ClaudeAgentOptions applied = SessionResume.applyMaterializedOptions(options, result);
            assertThat(applied.env())
                    .containsEntry("CLAUDE_CONFIG_DIR", result.configDir().toString());
            assertThat(applied.resume()).isEqualTo(sessionId);
            assertThat(applied.continueConversation()).isFalse();
        } finally {
            result.cleanup();
        }
    }

    @Test
    void materializeResumeSession_returnsNullWhenStoreHasNoEntries() throws IOException {
        InMemorySessionStore store = new InMemorySessionStore();
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sessionStore(store)
                .resume(UUID.randomUUID().toString())
                .build();
        assertThat(SessionResume.materializeResumeSession(options)).isNull();
    }

    @Test
    void continueConversation_resolvesNonSidechainSession() throws IOException {
        InMemorySessionStore store = new InMemorySessionStore();
        String projectKey = SessionStores.projectKeyForDirectory(null);
        String sessionA = UUID.randomUUID().toString();
        String sessionB = UUID.randomUUID().toString();
        store.append(new SessionKey(projectKey, sessionA, null),
                List.of(userEntry("u1", "main A", "2026-04-27T00:00:00Z")));
        // Sleep briefly to ensure mtime ordering
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        store.append(new SessionKey(projectKey, sessionB, null),
                List.of(userEntry("u2", "main B", "2026-04-27T00:00:01Z")));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sessionStore(store)
                .continueConversation(true)
                .build();

        MaterializedResume result = SessionResume.materializeResumeSession(options);
        assertThat(result).isNotNull();
        try {
            // sessionB was appended last, so listSessions().mtime is highest
            assertThat(result.resumeSessionId()).isEqualTo(sessionB);
        } finally {
            result.cleanup();
        }
    }

    @Test
    void cleanup_removesTempDir() throws IOException {
        InMemorySessionStore store = new InMemorySessionStore();
        String sessionId = UUID.randomUUID().toString();
        String projectKey = SessionStores.projectKeyForDirectory(null);
        store.append(new SessionKey(projectKey, sessionId, null),
                List.of(userEntry("u1", "x", "2026-04-27T00:00:00Z")));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sessionStore(store)
                .resume(sessionId)
                .build();
        MaterializedResume result = SessionResume.materializeResumeSession(options);
        assertThat(result).isNotNull();
        Path configDir = result.configDir();
        assertThat(configDir).exists();

        result.cleanup();
        assertThat(configDir).doesNotExist();
    }

    @Test
    void isSafeSubpath_rejectsTraversal() {
        Path sessionDir = Path.of("/tmp/some-session");
        assertThat(SessionResume.isSafeSubpath("subagents/agent-x", sessionDir)).isTrue();
        assertThat(SessionResume.isSafeSubpath("", sessionDir)).isFalse();
        assertThat(SessionResume.isSafeSubpath("../escape", sessionDir)).isFalse();
        assertThat(SessionResume.isSafeSubpath("/abs/path", sessionDir)).isFalse();
        assertThat(SessionResume.isSafeSubpath("subagents/../../../etc/passwd", sessionDir)).isFalse();
    }

    @Test
    void validateSessionStoreOptions_continueWithoutListSessions_throws() {
        // A minimal store with only the required append/load methods.
        in.vidyalai.claude.sdk.types.session.SessionStore minStore =
                new in.vidyalai.claude.sdk.types.session.SessionStore() {
                    @Override
                    public void append(SessionKey key, List<SessionStoreEntry> entries) {
                    }

                    @Override
                    public List<SessionStoreEntry> load(SessionKey key) {
                        return null;
                    }
                };
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sessionStore(minStore)
                .continueConversation(true)
                .build();
        assertThatThrownBy(() -> SessionStoreValidation.validate(options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("listSessions");
    }

    @Test
    void validateSessionStoreOptions_fileCheckpointing_throws() {
        InMemorySessionStore store = new InMemorySessionStore();
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sessionStore(store)
                .enableFileCheckpointing(true)
                .build();
        assertThatThrownBy(() -> SessionStoreValidation.validate(options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enableFileCheckpointing");
    }

    @Test
    void validateSessionStoreOptions_noStore_isNoop() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .continueConversation(true)
                .enableFileCheckpointing(true)
                .build();
        SessionStoreValidation.validate(options); // No exception
    }

}
