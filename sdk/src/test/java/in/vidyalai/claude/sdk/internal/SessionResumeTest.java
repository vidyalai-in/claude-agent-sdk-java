package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

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

    @SuppressWarnings("null")
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

    @SuppressWarnings("null")
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

    @SuppressWarnings("null")
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

    @SuppressWarnings("null")
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

    @SuppressWarnings("null")
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

    // ------------------------------------------------------------------
    // User settings seeded into the temp config dir (Python SDK #1197)
    // ------------------------------------------------------------------

    /**
     * Materialize a one-entry session with {@code CLAUDE_CONFIG_DIR} pointed at
     * {@code configDir}, so the seeding reads from the test's directory rather
     * than the developer's real {@code ~/.claude}.
     */
    private static MaterializedResume materializeWithConfigDir(Path configDir) throws IOException {
        InMemorySessionStore store = new InMemorySessionStore();
        String sessionId = UUID.randomUUID().toString();
        String projectKey = SessionStores.projectKeyForDirectory(null);
        store.append(new SessionKey(projectKey, sessionId, null),
                List.of(userEntry("u1", "Hello", "2026-08-13T00:00:00Z")));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sessionStore(store)
                .resume(sessionId)
                .env(Map.of("CLAUDE_CONFIG_DIR", configDir.toString()))
                .build();
        MaterializedResume result = SessionResume.materializeResumeSession(options);
        assertThat(result).isNotNull();
        return result;
    }

    @SuppressWarnings("null")
    @Test
    void userSettingsAreSeededIntoTempConfigDir(@TempDir Path configDir) throws IOException {
        // settings.json (apiKeyHelper etc.) and cowork_settings.json are seeded
        // into the temp config dir so an apiKeyHelper-only host can still auth.
        byte[] settings = "{\"apiKeyHelper\":\"/bin/print-key\",\"env\":{\"FOO\":\"bar\"}}"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(configDir.resolve("settings.json"), settings);
        Files.write(configDir.resolve("cowork_settings.json"), settings);
        Files.writeString(configDir.resolve(".claude.json"), "{\"theme\":\"dark\"}");

        MaterializedResume result = materializeWithConfigDir(configDir);
        try {
            // Nothing to strip → bytes copied through untouched.
            assertThat(Files.readAllBytes(result.configDir().resolve("settings.json")))
                    .isEqualTo(settings);
            assertThat(Files.readAllBytes(result.configDir().resolve("cowork_settings.json")))
                    .isEqualTo(settings);
            assertThat(result.configDir().resolve(".claude.json")).exists();
        } finally {
            result.cleanup();
        }
    }

    @SuppressWarnings("null")
    @Test
    void absentUserSettingsWriteNothing(@TempDir Path configDir) throws IOException {
        MaterializedResume result = materializeWithConfigDir(configDir);
        try {
            assertThat(result.configDir().resolve("settings.json")).doesNotExist();
            assertThat(result.configDir().resolve("cowork_settings.json")).doesNotExist();
            assertThat(result.configDir().resolve(".claude.json")).doesNotExist();
        } finally {
            result.cleanup();
        }
    }

    @SuppressWarnings("null")
    @Test
    void userSettingsStripPluginsAndConfigDirEnv(@TempDir Path configDir) throws IOException {
        // Plugin declarations (which would reconcile against the empty temp
        // plugin cache) and env.CLAUDE_CONFIG_DIR (which would redirect config
        // reads away from the temp dir) are dropped; everything else survives.
        // A UTF-8 BOM (PowerShell-written settings) is tolerated.
        String original = "{\"apiKeyHelper\":\"/bin/print-key\","
                + "\"enabledPlugins\":{\"p@m\":true},"
                + "\"extraKnownMarketplaces\":{\"m\":{\"source\":\"github\",\"repo\":\"o/r\"}},"
                + "\"env\":{\"CLAUDE_CONFIG_DIR\":\"/elsewhere\",\"KEEP\":\"1\"},"
                + "\"permissions\":{\"allow\":[\"Bash(ls)\"]}}";
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        for (String name : List.of("settings.json", "cowork_settings.json")) {
            try (var out = Files.newOutputStream(configDir.resolve(name))) {
                out.write(bom);
                out.write(original.getBytes(StandardCharsets.UTF_8));
            }
        }

        MaterializedResume result = materializeWithConfigDir(configDir);
        try {
            ObjectMapper mapper = new ObjectMapper();
            for (String name : List.of("settings.json", "cowork_settings.json")) {
                Map<?, ?> copied = mapper.readValue(
                        Files.readAllBytes(result.configDir().resolve(name)), Map.class);
                assertThat(copied).as(name).isEqualTo(Map.of(
                        "apiKeyHelper", "/bin/print-key",
                        "env", Map.of("KEEP", "1"),
                        "permissions", Map.of("allow", List.of("Bash(ls)"))));
            }
        } finally {
            result.cleanup();
        }
    }

    @SuppressWarnings("null")
    @Test
    void malformedUserSettingsAreCopiedThrough(@TempDir Path configDir) throws IOException {
        // Valid JSON but not an object, and an object whose `env` is not an
        // object: both are left byte-for-byte as the CLI would have read them.
        Files.writeString(configDir.resolve("settings.json"), "{not json");
        Files.writeString(configDir.resolve("cowork_settings.json"), "{\"env\": \"nope\", \"a\": 1}");
        Files.writeString(configDir.resolve(".claude.json"), "[1, 2]");

        MaterializedResume result = materializeWithConfigDir(configDir);
        try {
            assertThat(result.configDir().resolve("settings.json"))
                    .hasContent("{not json");
            assertThat(result.configDir().resolve("cowork_settings.json"))
                    .hasContent("{\"env\": \"nope\", \"a\": 1}");
            assertThat(result.configDir().resolve(".claude.json")).hasContent("[1, 2]");
        } finally {
            result.cleanup();
        }
    }

    @SuppressWarnings("null")
    @Test
    void overflowFloatInSettingsFallsBackToOriginalBytes(@TempDir Path configDir) throws IOException {
        // `1e999` is valid JSON that parses to infinity; re-serializing after a
        // strip would emit a token the CLI rejects. The transform must give up
        // and pass the original bytes through.
        String raw = "{\"enabledPlugins\": {\"p@m\": true}, \"threshold\": 1e999}";
        Files.writeString(configDir.resolve("settings.json"), raw);

        MaterializedResume result = materializeWithConfigDir(configDir);
        try {
            assertThat(result.configDir().resolve("settings.json")).hasContent(raw);
        } finally {
            result.cleanup();
        }
    }

    @SuppressWarnings("null")
    @Test
    void unreadableSeedFilesDoNotAbortResume(@TempDir Path configDir) throws IOException {
        // A directory where a file is expected must be skipped, not fatal.
        Files.createDirectory(configDir.resolve("settings.json"));
        Files.createDirectory(configDir.resolve(".credentials.json"));
        Files.createDirectory(configDir.resolve(".claude.json"));

        MaterializedResume result = materializeWithConfigDir(configDir);
        try {
            assertThat(Files.isRegularFile(result.configDir().resolve("settings.json"))).isFalse();
            assertThat(Files.isRegularFile(result.configDir().resolve(".credentials.json"))).isFalse();
            assertThat(Files.isRegularFile(result.configDir().resolve(".claude.json"))).isFalse();
            // The transcript itself was still materialized.
            String projectKey = SessionStores.projectKeyForDirectory(null);
            assertThat(result.configDir().resolve("projects").resolve(projectKey))
                    .isDirectoryContaining(p -> p.toString().endsWith(".jsonl"));
        } finally {
            result.cleanup();
        }
    }

    @SuppressWarnings("null")
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void fifoSeedFileIsSkippedNotRead(@TempDir Path configDir) throws Exception {
        // A FIFO where settings.json is expected would block a plain read
        // forever; it must be skipped like any other non-regular file.
        Process mkfifo = new ProcessBuilder("mkfifo", configDir.resolve("settings.json").toString())
                .start();
        assumeTrue(mkfifo.waitFor() == 0, "mkfifo unavailable");

        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            MaterializedResume result = materializeWithConfigDir(configDir);
            try {
                assertThat(Files.isRegularFile(result.configDir().resolve("settings.json")))
                        .isFalse();
            } finally {
                result.cleanup();
            }
        });
    }

    @SuppressWarnings("null")
    @Test
    void userSettingsComeFromTheConfiguredConfigDir(@TempDir Path configDir,
            @TempDir Path decoyDir) throws IOException {
        // Source resolution is options.env["CLAUDE_CONFIG_DIR"] → process env →
        // ~/.claude. Only the configured directory is read; an unrelated one
        // (and, on a developer machine, the real ~/.claude) must not win.
        Files.writeString(configDir.resolve("settings.json"), "{\"apiKeyHelper\":\"/from/env\"}");
        Files.writeString(decoyDir.resolve("settings.json"), "{\"apiKeyHelper\":\"/decoy\"}");

        MaterializedResume result = materializeWithConfigDir(configDir);
        try {
            assertThat(result.configDir().resolve("settings.json"))
                    .hasContent("{\"apiKeyHelper\":\"/from/env\"}");
        } finally {
            result.cleanup();
        }
    }

    @SuppressWarnings("null")
    @Test
    void nonUtf8SettingsAreCopiedThroughByteForByte(@TempDir Path configDir) throws IOException {
        // Invalid UTF-8 must be passed through untouched rather than rewritten
        // with U+FFFD replacement characters — even though a strippable key is
        // present, which would otherwise trigger a re-serialization.
        byte[] raw = concat(
                "{\"enabledPlugins\":{\"p@m\":true},\"note\":\"".getBytes(StandardCharsets.UTF_8),
                new byte[] {(byte) 0xFF},
                "\"}".getBytes(StandardCharsets.UTF_8));
        // Sanity: the transform itself refuses to touch it.
        assertThat(SessionResume.stripSettingsForResume(raw)).isEqualTo(raw);

        Files.write(configDir.resolve("settings.json"), raw);
        MaterializedResume result = materializeWithConfigDir(configDir);
        try {
            assertThat(Files.readAllBytes(result.configDir().resolve("settings.json")))
                    .isEqualTo(raw);
        } finally {
            result.cleanup();
        }
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] joined = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, joined, offset, part.length);
            offset += part.length;
        }
        return joined;
    }

    @Test
    void seededFilesAreOwnerReadableOnlyInAnOwnerOnlyDir(@TempDir Path configDir)
            throws IOException {
        assumeTrue(!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT)
                .contains("win"), "POSIX permissions only");
        Files.writeString(configDir.resolve("settings.json"), "{\"a\":1}");
        Files.writeString(configDir.resolve("cowork_settings.json"), "{\"a\":1}");
        Files.writeString(configDir.resolve(".claude.json"), "{\"b\":2}");
        Files.writeString(configDir.resolve(".credentials.json"),
                "{\"claudeAiOauth\":{\"accessToken\":\"t\",\"refreshToken\":\"secret\"}}");

        MaterializedResume result = materializeWithConfigDir(configDir);
        try {
            assertThat(Files.getPosixFilePermissions(result.configDir()))
                    .as("temp config dir")
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
            for (String name : List.of("settings.json", "cowork_settings.json",
                    ".claude.json", ".credentials.json")) {
                assertThat(Files.getPosixFilePermissions(result.configDir().resolve(name)))
                        .as(name)
                        .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE);
            }
            // The refresh token is still redacted out of the seeded copy.
            assertThat(result.configDir().resolve(".credentials.json"))
                    .content(StandardCharsets.UTF_8)
                    .doesNotContain("refreshToken");
        } finally {
            result.cleanup();
        }
    }

}
