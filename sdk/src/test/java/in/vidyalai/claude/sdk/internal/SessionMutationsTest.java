package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.types.message.ForkSessionResult;
import in.vidyalai.claude.sdk.types.message.SDKSessionInfo;
import in.vidyalai.claude.sdk.types.message.SessionMessage;

/**
 * Tests for the SessionMutations internal class.
 * Ported from Python SDK tests/test_session_mutations.py.
 */
class SessionMutationsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Sets user.home to point to a temp Claude home, runs test, then restores. */
    private void withClaudeHome(Path home, RunnableWithIO test) throws IOException {
        String original = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            test.run();
        } finally {
            System.setProperty("user.home", original);
        }
    }

    @FunctionalInterface
    interface RunnableWithIO {
        void run() throws IOException;
    }

    /** Creates a claude home dir structure (home/.claude/projects/). */
    private Path createClaudeHome(Path tempDir) throws IOException {
        Path claudeHome = tempDir.resolve("home");
        Files.createDirectories(claudeHome.resolve(".claude").resolve("projects"));
        return claudeHome;
    }

    /**
     * Creates a project directory under claudeHome/.claude/projects/<sanitized>.
     */
    private Path makeProjectDir(Path claudeHome, String projectPath) throws IOException {
        String sanitized = Sessions.sanitizePath(projectPath);
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve(sanitized);
        Files.createDirectories(projectDir);
        return projectDir;
    }

    /**
     * Creates a session JSONL file in the given project dir. Returns (sessionId,
     * filePath).
     */
    private String[] makeSessionFile(Path projectDir, String sessionId, String firstPrompt)
            throws IOException {
        if (sessionId == null) {
            sessionId = UUID.randomUUID().toString();
        }
        Path filePath = projectDir.resolve(sessionId + ".jsonl");
        String content = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\""
                + firstPrompt + "\"}}\n"
                + "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":\"Hi!\"}}\n";
        Files.writeString(filePath, content);
        return new String[] { sessionId, filePath.toString() };
    }

    private String[] makeSessionFile(Path projectDir) throws IOException {
        return makeSessionFile(projectDir, null, "Hello Claude");
    }

    // -------------------------------------------------------------------------
    // _tryAppend() tests
    // -------------------------------------------------------------------------

    @Test
    void testTryAppend_appendsToExistingFile(@TempDir Path tempDir) throws IOException {
        Path f = tempDir.resolve("test.jsonl");
        Files.writeString(f, "line1\n");
        boolean result = SessionMutations.tryAppend(f, "line2\n");
        assertThat(result).isTrue();
        assertThat(Files.readString(f)).isEqualTo("line1\nline2\n");
    }

    @Test
    void testTryAppend_missingFileReturnsFalse(@TempDir Path tempDir) {
        Path f = tempDir.resolve("nonexistent.jsonl");
        boolean result = SessionMutations.tryAppend(f, "data\n");
        assertThat(result).isFalse();
        assertThat(Files.exists(f)).isFalse(); // did NOT create the file
    }

    @Test
    void testTryAppend_missingParentDirReturnsFalse(@TempDir Path tempDir) {
        Path f = tempDir.resolve("nonexistent").resolve("file.jsonl");
        boolean result = SessionMutations.tryAppend(f, "data\n");
        assertThat(result).isFalse();
    }

    @Test
    void testTryAppend_zeroByteFileReturnsFalse(@TempDir Path tempDir) throws IOException {
        Path f = tempDir.resolve("stub.jsonl");
        Files.writeString(f, "");
        boolean result = SessionMutations.tryAppend(f, "data\n");
        assertThat(result).isFalse();
        assertThat(Files.readString(f)).isEqualTo(""); // not modified
    }

    @Test
    void testTryAppend_multipleAppendsInOrder(@TempDir Path tempDir) throws IOException {
        Path f = tempDir.resolve("test.jsonl");
        Files.writeString(f, "line1\n");
        SessionMutations.tryAppend(f, "line2\n");
        SessionMutations.tryAppend(f, "line3\n");
        assertThat(Files.readString(f)).isEqualTo("line1\nline2\nline3\n");
    }

    // -------------------------------------------------------------------------
    // renameSession() tests
    // -------------------------------------------------------------------------

    @SuppressWarnings("null")
    @Test
    void testRenameSession_invalidSessionIdRaises(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        withClaudeHome(claudeHome, () -> {
            assertThatThrownBy(() -> SessionMutations.renameSession("not-a-uuid", "title", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid session_id");
            assertThatThrownBy(() -> SessionMutations.renameSession("", "title", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid session_id");
        });
    }

    @SuppressWarnings("null")
    @Test
    void testRenameSession_emptyTitleRaises(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeSessionFile(projectDir);
            String sid = session[0];

            assertThatThrownBy(() -> SessionMutations.renameSession(sid, "", projectPath))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("title must be non-empty");
            assertThatThrownBy(() -> SessionMutations.renameSession(sid, "   ", projectPath))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("title must be non-empty");
            assertThatThrownBy(() -> SessionMutations.renameSession(sid, "\n\t", projectPath))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("title must be non-empty");
        });
    }

    @Test
    void testRenameSession_sessionNotFoundRaises(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            makeProjectDir(claudeHome, projectPath);
            String sid = UUID.randomUUID().toString();

            assertThatThrownBy(() -> SessionMutations.renameSession(sid, "title", projectPath))
                    .isInstanceOf(FileNotFoundException.class);
        });
    }

    @Test
    void testRenameSession_noProjectsDirRaises(@TempDir Path tempDir) throws IOException {
        // Use a nonexistent claude home so projects dir doesn't exist
        Path nonExistent = tempDir.resolve("nonexistent");
        String sid = UUID.randomUUID().toString();

        withClaudeHome(nonExistent, () -> {
            assertThatThrownBy(() -> SessionMutations.renameSession(sid, "title", null))
                    .isInstanceOf(FileNotFoundException.class);
        });
    }

    @SuppressWarnings("null")
    @Test
    void testRenameSession_appendsCustomTitleEntry(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeSessionFile(projectDir);
            String sid = session[0];
            Path filePath = Path.of(session[1]);

            SessionMutations.renameSession(sid, "My New Title", projectPath);

            List<String> lines = Files.readAllLines(filePath);
            // Last line should be the custom-title entry
            String lastLine = lines.get(lines.size() - 1);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> entry = MAPPER.readValue(lastLine, java.util.Map.class);
            assertThat(entry.get("type")).isEqualTo("custom-title");
            assertThat(entry.get("customTitle")).isEqualTo("My New Title");
            assertThat(entry.get("sessionId")).isEqualTo(sid);
        });
    }

    @SuppressWarnings("null")
    @Test
    void testRenameSession_titleTrimmedBeforeStoring(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeSessionFile(projectDir);
            String sid = session[0];
            Path filePath = Path.of(session[1]);

            SessionMutations.renameSession(sid, "  Trimmed Title  ", projectPath);

            List<String> lines = Files.readAllLines(filePath);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> entry = MAPPER.readValue(
                    lines.get(lines.size() - 1), java.util.Map.class);
            assertThat(entry.get("customTitle")).isEqualTo("Trimmed Title");
        });
    }

    @Test
    void testRenameSession_lastWinsViaListSessions(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeSessionFile(projectDir, null, "original");
            String sid = session[0];

            SessionMutations.renameSession(sid, "First Title", projectPath);
            SessionMutations.renameSession(sid, "Second Title", projectPath);
            SessionMutations.renameSession(sid, "Final Title", projectPath);

            List<SDKSessionInfo> sessions = Sessions.listSessions(projectPath, null, false);
            assertThat(sessions).hasSize(1);
            assertThat(sessions.get(0).customTitle()).isEqualTo("Final Title");
            assertThat(sessions.get(0).summary()).isEqualTo("Final Title");
        });
    }

    @SuppressWarnings("null")
    @Test
    void testRenameSession_searchAllProjects(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, "/some/project");
            String[] session = makeSessionFile(projectDir);
            String sid = session[0];
            Path filePath = Path.of(session[1]);

            SessionMutations.renameSession(sid, "Found Without Dir", null);

            List<String> lines = Files.readAllLines(filePath);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> entry = MAPPER.readValue(
                    lines.get(lines.size() - 1), java.util.Map.class);
            assertThat(entry.get("customTitle")).isEqualTo("Found Without Dir");
        });
    }

    @Test
    void testRenameSession_skipsZeroByteStub(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);

        withClaudeHome(claudeHome, () -> {
            Path projA = makeProjectDir(claudeHome, "/aaa/project");
            Path projZ = makeProjectDir(claudeHome, "/zzz/project");

            String sid = UUID.randomUUID().toString();
            // 0-byte stub in first dir
            Files.writeString(projA.resolve(sid + ".jsonl"), "");
            // Real file in second dir
            makeSessionFile(projZ, sid, "real");

            SessionMutations.renameSession(sid, "New Title", null);

            // Stub untouched
            assertThat(Files.readString(projA.resolve(sid + ".jsonl"))).isEqualTo("");
            // Real file has the entry
            String realContent = Files.readString(projZ.resolve(sid + ".jsonl"));
            assertThat(realContent).contains("\"customTitle\":\"New Title\"");
        });
    }

    @Test
    void testRenameSession_compactJsonFormat(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeSessionFile(projectDir);
            String sid = session[0];
            Path filePath = Path.of(session[1]);

            SessionMutations.renameSession(sid, "Title", projectPath);

            List<String> lines = Files.readAllLines(filePath);
            String lastLine = lines.get(lines.size() - 1);
            // Compact JSON: no spaces after : or ,
            assertThat(lastLine).isEqualTo(
                    "{\"type\":\"custom-title\",\"customTitle\":\"Title\",\"sessionId\":\"" + sid + "\"}");
        });
    }

    // -------------------------------------------------------------------------
    // tagSession() tests
    // -------------------------------------------------------------------------

    @SuppressWarnings("null")
    @Test
    void testTagSession_invalidSessionIdRaises(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        withClaudeHome(claudeHome, () -> {
            assertThatThrownBy(() -> SessionMutations.tagSession("not-a-uuid", "tag", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid session_id");
            assertThatThrownBy(() -> SessionMutations.tagSession("", "tag", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid session_id");
        });
    }

    @SuppressWarnings("null")
    @Test
    void testTagSession_emptyTagRaises(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeSessionFile(projectDir);
            String sid = session[0];

            assertThatThrownBy(() -> SessionMutations.tagSession(sid, "", projectPath))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tag must be non-empty");
            assertThatThrownBy(() -> SessionMutations.tagSession(sid, "   ", projectPath))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tag must be non-empty");
        });
    }

    @Test
    void testTagSession_sessionNotFoundRaises(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            makeProjectDir(claudeHome, projectPath);
            String sid = UUID.randomUUID().toString();

            assertThatThrownBy(() -> SessionMutations.tagSession(sid, "tag", projectPath))
                    .isInstanceOf(FileNotFoundException.class);
        });
    }

    @SuppressWarnings("null")
    @Test
    void testTagSession_appendsTagEntry(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeSessionFile(projectDir);
            String sid = session[0];
            Path filePath = Path.of(session[1]);

            SessionMutations.tagSession(sid, "experiment", projectPath);

            List<String> lines = Files.readAllLines(filePath);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> entry = MAPPER.readValue(
                    lines.get(lines.size() - 1), java.util.Map.class);
            assertThat(entry.get("type")).isEqualTo("tag");
            assertThat(entry.get("tag")).isEqualTo("experiment");
            assertThat(entry.get("sessionId")).isEqualTo(sid);
        });
    }

    @SuppressWarnings("null")
    @Test
    void testTagSession_tagTrimmed(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeSessionFile(projectDir);
            String sid = session[0];
            Path filePath = Path.of(session[1]);

            SessionMutations.tagSession(sid, "  my-tag  ", projectPath);

            List<String> lines = Files.readAllLines(filePath);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> entry = MAPPER.readValue(
                    lines.get(lines.size() - 1), java.util.Map.class);
            assertThat(entry.get("tag")).isEqualTo("my-tag");
        });
    }

    @SuppressWarnings("null")
    @Test
    void testTagSession_nullClearsTag(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeSessionFile(projectDir);
            String sid = session[0];
            Path filePath = Path.of(session[1]);

            SessionMutations.tagSession(sid, "original-tag", projectPath);
            SessionMutations.tagSession(sid, null, projectPath);

            List<String> lines = Files.readAllLines(filePath);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> entry = MAPPER.readValue(
                    lines.get(lines.size() - 1), java.util.Map.class);
            assertThat(entry.get("type")).isEqualTo("tag");
            assertThat(entry.get("tag")).isEqualTo("");
            assertThat(entry.get("sessionId")).isEqualTo(sid);
        });
    }

    @SuppressWarnings("null")
    @Test
    void testTagSession_lastWins(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeSessionFile(projectDir);
            String sid = session[0];
            Path filePath = Path.of(session[1]);

            SessionMutations.tagSession(sid, "first", projectPath);
            SessionMutations.tagSession(sid, "second", projectPath);
            SessionMutations.tagSession(sid, "third", projectPath);

            List<String> lines = Files.readAllLines(filePath);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> lastEntry = MAPPER.readValue(
                    lines.get(lines.size() - 1), java.util.Map.class);
            assertThat(lastEntry.get("tag")).isEqualTo("third");

            // All three tag entries present in file
            long tagCount = lines.stream().filter(line -> {
                try {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> e = MAPPER.readValue(line, java.util.Map.class);
                    return "tag".equals(e.get("type"));
                } catch (Exception ex) {
                    return false;
                }
            }).count();
            assertThat(tagCount).isEqualTo(3);
        });
    }

    @Test
    void testTagSession_compactJsonFormat(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeSessionFile(projectDir);
            String sid = session[0];
            Path filePath = Path.of(session[1]);

            SessionMutations.tagSession(sid, "mytag", projectPath);

            List<String> lines = Files.readAllLines(filePath);
            String lastLine = lines.get(lines.size() - 1);
            assertThat(lastLine).isEqualTo(
                    "{\"type\":\"tag\",\"tag\":\"mytag\",\"sessionId\":\"" + sid + "\"}");
        });
    }

    @SuppressWarnings("null")
    @Test
    void testTagSession_unicodeSanitization(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeSessionFile(projectDir);
            String sid = session[0];
            Path filePath = Path.of(session[1]);

            // Tag with zero-width space (\u200b) and BOM (\ufeff) embedded
            String dirtyTag = "clean\u200btag\ufeff";
            SessionMutations.tagSession(sid, dirtyTag, projectPath);

            List<String> lines = Files.readAllLines(filePath);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> entry = MAPPER.readValue(
                    lines.get(lines.size() - 1), java.util.Map.class);
            assertThat(entry.get("tag")).isEqualTo("cleantag");
        });
    }

    @SuppressWarnings("null")
    @Test
    void testTagSession_sanitizationRejectsPureInvisible(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeSessionFile(projectDir);
            String sid = session[0];

            // Tag that is only zero-width chars — should be rejected
            assertThatThrownBy(
                    () -> SessionMutations.tagSession(sid, "\u200b\u200c\ufeff", projectPath))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tag must be non-empty");
        });
    }

    // -------------------------------------------------------------------------
    // sanitizeUnicode() tests
    // -------------------------------------------------------------------------

    @Test
    void testSanitizeUnicode_passthroughCleanString() {
        assertThat(SessionMutations.sanitizeUnicode("hello")).isEqualTo("hello");
        assertThat(SessionMutations.sanitizeUnicode("tag-with-dashes_123"))
                .isEqualTo("tag-with-dashes_123");
    }

    @Test
    void testSanitizeUnicode_stripsZeroWidth() {
        assertThat(SessionMutations.sanitizeUnicode("a\u200bb")).isEqualTo("ab");
        assertThat(SessionMutations.sanitizeUnicode("a\u200cb")).isEqualTo("ab"); // zero-width non-joiner
        assertThat(SessionMutations.sanitizeUnicode("a\u200db")).isEqualTo("ab"); // zero-width joiner
    }

    @Test
    void testSanitizeUnicode_stripsBom() {
        assertThat(SessionMutations.sanitizeUnicode("\ufeffhello")).isEqualTo("hello");
    }

    @Test
    void testSanitizeUnicode_stripsDirectionalMarks() {
        assertThat(SessionMutations.sanitizeUnicode("a\u202ab\u202cc")).isEqualTo("abc");
        assertThat(SessionMutations.sanitizeUnicode("a\u2066b\u2069c")).isEqualTo("abc");
    }

    @Test
    void testSanitizeUnicode_stripsPrivateUse() {
        assertThat(SessionMutations.sanitizeUnicode("a\ue000b")).isEqualTo("ab");
        assertThat(SessionMutations.sanitizeUnicode("a\uf8ffb")).isEqualTo("ab");
    }

    @Test
    void testSanitizeUnicode_nfkcNormalization() {
        // Fullwidth 'A' (\uff21) → ASCII 'A' via NFKC
        assertThat(SessionMutations.sanitizeUnicode("\uff21")).isEqualTo("A");
    }

    @Test
    void testSanitizeUnicode_iterativeConverges() {
        // A string that needs multiple passes still converges
        String result = SessionMutations.sanitizeUnicode("a" + "\u200b".repeat(20) + "b");
        assertThat(result).isEqualTo("ab");
    }

    // -------------------------------------------------------------------------
    // deleteSession() tests
    // -------------------------------------------------------------------------

    @SuppressWarnings("null")
    @Test
    void testDeleteSession_invalidSessionIdRaises(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        withClaudeHome(claudeHome, () -> {
            assertThatThrownBy(() -> SessionMutations.deleteSession("not-a-uuid", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid session_id");
        });
    }

    @SuppressWarnings("null")
    @Test
    void testDeleteSession_sessionNotFoundRaises(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        withClaudeHome(claudeHome, () -> {
            String sid = UUID.randomUUID().toString();
            assertThatThrownBy(() -> SessionMutations.deleteSession(sid, null))
                    .isInstanceOf(FileNotFoundException.class)
                    .hasMessageContaining("not found");
        });
    }

    @Test
    void testDeleteSession_deletesSessionFile(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeSessionFile(projectDir);
            String sid = session[0];
            Path filePath = Path.of(session[1]);

            assertThat(Files.exists(filePath)).isTrue();
            SessionMutations.deleteSession(sid, projectPath);
            assertThat(Files.exists(filePath)).isFalse();
        });
    }

    @Test
    void testDeleteSession_deletesWithoutDirectory(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, "/any/project");
            String[] session = makeSessionFile(projectDir);
            String sid = session[0];
            Path filePath = Path.of(session[1]);

            assertThat(Files.exists(filePath)).isTrue();
            SessionMutations.deleteSession(sid, null);
            assertThat(Files.exists(filePath)).isFalse();
        });
    }

    @Test
    void testDeleteSession_cascadesSubagentTranscripts(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeSessionFile(projectDir);
            String sid = session[0];
            Path filePath = Path.of(session[1]);

            // Create the sibling subagents directory tree
            Path subagentsDir = projectDir.resolve(sid).resolve("subagents");
            Files.createDirectories(subagentsDir);
            Path agentFile = subagentsDir.resolve("agent-abc123.jsonl");
            Files.writeString(agentFile, "{\"type\":\"user\",\"uuid\":\"u1\"}\n");

            assertThat(Files.exists(filePath)).isTrue();
            assertThat(Files.exists(agentFile)).isTrue();

            SessionMutations.deleteSession(sid, projectPath);

            assertThat(Files.exists(filePath)).isFalse();
            assertThat(Files.exists(agentFile)).isFalse();
            assertThat(Files.exists(projectDir.resolve(sid))).isFalse();
        });
    }

    @Test
    void testDeleteSession_succeedsWhenSubagentDirAbsent(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeSessionFile(projectDir);
            String sid = session[0];
            Path filePath = Path.of(session[1]);

            // No subagents dir created — delete should still succeed.
            assertThat(Files.exists(filePath)).isTrue();
            SessionMutations.deleteSession(sid, projectPath);
            assertThat(Files.exists(filePath)).isFalse();
        });
    }

    @Test
    void testDeleteSession_noLongerInListSessions(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeSessionFile(projectDir);
            String sid = session[0];

            List<SDKSessionInfo> sessions = Sessions.listSessions(projectPath, null, false);
            assertThat(sessions).anyMatch(s -> s.sessionId().equals(sid));

            SessionMutations.deleteSession(sid, projectPath);

            sessions = Sessions.listSessions(projectPath, null, false);
            assertThat(sessions).noneMatch(s -> s.sessionId().equals(sid));
        });
    }

    // -------------------------------------------------------------------------
    // Helpers for fork tests — transcript entries with uuid + parentUuid
    // -------------------------------------------------------------------------

    /**
     * Creates a session file with proper uuid/parentUuid chain.
     * Returns {sessionId, filePath, uuid1, uuid2, ...}.
     */
    private String[] makeTranscriptSession(Path projectDir, String sessionId, int numTurns)
            throws IOException {
        if (sessionId == null) {
            sessionId = UUID.randomUUID().toString();
        }
        Path filePath = projectDir.resolve(sessionId + ".jsonl");
        List<String> uuids = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        String parentUuid = null;

        for (int i = 0; i < numTurns; i++) {
            // User message
            String userUuid = UUID.randomUUID().toString();
            uuids.add(userUuid);
            String userParent = parentUuid != null ? "\"" + parentUuid + "\"" : "null";
            lines.add("{\"type\":\"user\",\"uuid\":\"" + userUuid
                    + "\",\"parentUuid\":" + userParent
                    + ",\"sessionId\":\"" + sessionId
                    + "\",\"timestamp\":\"2026-03-01T00:00:00Z\""
                    + ",\"message\":{\"role\":\"user\",\"content\":\"Turn " + (i + 1) + " question\"}}");
            parentUuid = userUuid;

            // Assistant message
            String asstUuid = UUID.randomUUID().toString();
            uuids.add(asstUuid);
            lines.add("{\"type\":\"assistant\",\"uuid\":\"" + asstUuid
                    + "\",\"parentUuid\":\"" + parentUuid
                    + "\",\"sessionId\":\"" + sessionId
                    + "\",\"timestamp\":\"2026-03-01T00:00:00Z\""
                    + ",\"message\":{\"role\":\"assistant\",\"content\":["
                    + "{\"type\":\"text\",\"text\":\"Turn " + (i + 1) + " answer\"}]}}");
            parentUuid = asstUuid;
        }

        Files.writeString(filePath, String.join("\n", lines) + "\n");

        // Return sessionId, filePath, then all uuids
        List<String> result = new ArrayList<>();
        result.add(sessionId);
        result.add(filePath.toString());
        result.addAll(uuids);
        return result.toArray(String[]::new);
    }

    // -------------------------------------------------------------------------
    // forkSession() tests
    // -------------------------------------------------------------------------

    @SuppressWarnings("null")
    @Test
    void testForkSession_invalidSessionIdRaises(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        withClaudeHome(claudeHome, () -> {
            assertThatThrownBy(() -> SessionMutations.forkSession("not-a-uuid", null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid session_id");
        });
    }

    @SuppressWarnings("null")
    @Test
    void testForkSession_sessionNotFoundRaises(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        withClaudeHome(claudeHome, () -> {
            String sid = UUID.randomUUID().toString();
            assertThatThrownBy(() -> SessionMutations.forkSession(sid, null, null, null))
                    .isInstanceOf(FileNotFoundException.class)
                    .hasMessageContaining("not found");
        });
    }

    @SuppressWarnings("null")
    @Test
    void testForkSession_invalidUpToMessageIdRaises(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        withClaudeHome(claudeHome, () -> {
            assertThatThrownBy(
                    () -> SessionMutations.forkSession(UUID.randomUUID().toString(), null, "not-valid", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid up_to_message_id");
        });
    }

    @Test
    void testForkSession_createsNewSession(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeTranscriptSession(projectDir, null, 2);
            String sid = session[0];

            ForkSessionResult result = SessionMutations.forkSession(sid, projectPath, null, null);
            assertThat(result.sessionId()).isNotEqualTo(sid);

            // New file exists
            Path forkPath = projectDir.resolve(result.sessionId() + ".jsonl");
            assertThat(Files.exists(forkPath)).isTrue();
        });
    }

    @SuppressWarnings({ "unchecked", "null" })
    @Test
    void testForkSession_remapsUuids(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeTranscriptSession(projectDir, null, 2);
            String sid = session[0];
            // Original UUIDs start at index 2
            List<String> originalUuids = new ArrayList<>();
            for (int i = 2; i < session.length; i++) {
                originalUuids.add(session[i]);
            }

            ForkSessionResult result = SessionMutations.forkSession(sid, projectPath, null, null);
            Path forkPath = projectDir.resolve(result.sessionId() + ".jsonl");

            for (String line : Files.readAllLines(forkPath)) {
                Map<String, Object> entry = MAPPER.readValue(line, Map.class);
                String type = (String) entry.get("type");
                if ("user".equals(type) || "assistant".equals(type)) {
                    assertThat(originalUuids).doesNotContain((String) entry.get("uuid"));
                    if (entry.get("parentUuid") != null) {
                        assertThat(originalUuids).doesNotContain((String) entry.get("parentUuid"));
                    }
                }
            }
        });
    }

    @Test
    void testForkSession_preservesMessageCount(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeTranscriptSession(projectDir, null, 3);
            String sid = session[0];

            ForkSessionResult result = SessionMutations.forkSession(sid, projectPath, null, null);

            List<SessionMessage> originalMsgs = Sessions.getSessionMessages(sid, projectPath, null, 0);
            List<SessionMessage> forkMsgs = Sessions.getSessionMessages(
                    result.sessionId(), projectPath, null, 0);
            // Fork has same visible messages
            assertThat(forkMsgs).hasSameSizeAs(originalMsgs);
        });
    }

    @Test
    void testForkSession_upToMessageId(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeTranscriptSession(projectDir, null, 3);
            String sid = session[0];
            // uuid index 3 = first assistant response (index 2 in session array)
            String cutoffUuid = session[3]; // second uuid (first assistant)

            ForkSessionResult result = SessionMutations.forkSession(
                    sid, projectPath, cutoffUuid, null);

            List<SessionMessage> forkMsgs = Sessions.getSessionMessages(
                    result.sessionId(), projectPath, null, 0);
            // Should have 2 messages (1 user + 1 assistant from first turn)
            assertThat(forkMsgs).hasSize(2);
        });
    }

    @SuppressWarnings("null")
    @Test
    void testForkSession_upToMessageIdNotFoundRaises(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeTranscriptSession(projectDir, null, 2);
            String sid = session[0];

            String fakeUuid = UUID.randomUUID().toString();
            assertThatThrownBy(
                    () -> SessionMutations.forkSession(sid, projectPath, fakeUuid, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not found in session");
        });
    }

    @Test
    void testForkSession_customTitle(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeTranscriptSession(projectDir, null, 2);
            String sid = session[0];

            ForkSessionResult result = SessionMutations.forkSession(
                    sid, projectPath, null, "My Fork");

            List<SDKSessionInfo> sessions = Sessions.listSessions(projectPath, null, false);
            SDKSessionInfo forkInfo = sessions.stream()
                    .filter(s -> s.sessionId().equals(result.sessionId()))
                    .findFirst().orElse(null);
            assertThat(forkInfo).isNotNull();
            assertThat(forkInfo.customTitle()).isEqualTo("My Fork");
        });
    }

    @Test
    void testForkSession_defaultTitleHasSuffix(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeTranscriptSession(projectDir, null, 2);
            String sid = session[0];

            ForkSessionResult result = SessionMutations.forkSession(
                    sid, projectPath, null, null);

            List<SDKSessionInfo> sessions = Sessions.listSessions(projectPath, null, false);
            SDKSessionInfo forkInfo = sessions.stream()
                    .filter(s -> s.sessionId().equals(result.sessionId()))
                    .findFirst().orElse(null);
            assertThat(forkInfo).isNotNull();
            assertThat(forkInfo.customTitle()).isNotNull();
            assertThat(forkInfo.customTitle()).endsWith("(fork)");
        });
    }

    @SuppressWarnings({ "unchecked", "null" })
    @Test
    void testForkSession_sessionIdInEntries(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeTranscriptSession(projectDir, null, 2);
            String sid = session[0];

            ForkSessionResult result = SessionMutations.forkSession(
                    sid, projectPath, null, null);
            Path forkPath = projectDir.resolve(result.sessionId() + ".jsonl");

            for (String line : Files.readAllLines(forkPath)) {
                Map<String, Object> entry = MAPPER.readValue(line, Map.class);
                assertThat(entry.get("sessionId")).isEqualTo(result.sessionId());
            }
        });
    }

    @SuppressWarnings({ "unchecked", "null" })
    @Test
    void testForkSession_forkedFromField(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String[] session = makeTranscriptSession(projectDir, null, 2);
            String sid = session[0];

            ForkSessionResult result = SessionMutations.forkSession(
                    sid, projectPath, null, null);
            Path forkPath = projectDir.resolve(result.sessionId() + ".jsonl");

            for (String line : Files.readAllLines(forkPath)) {
                Map<String, Object> entry = MAPPER.readValue(line, Map.class);
                String type = (String) entry.get("type");
                if ("user".equals(type) || "assistant".equals(type)) {
                    Map<String, Object> forkedFrom = (Map<String, Object>) entry.get("forkedFrom");
                    assertThat(forkedFrom).isNotNull();
                    assertThat(forkedFrom.get("sessionId")).isEqualTo(sid);
                }
            }
        });
    }

    @Test
    void testForkSession_withoutDirectory(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, "/any/project");
            String[] session = makeTranscriptSession(projectDir, null, 2);
            String sid = session[0];

            ForkSessionResult result = SessionMutations.forkSession(sid, null, null, null);
            Path forkPath = projectDir.resolve(result.sessionId() + ".jsonl");
            assertThat(Files.exists(forkPath)).isTrue();
        });
    }

    @SuppressWarnings({ "unchecked", "null" })
    @Test
    void testForkSession_clearsStaleFields(@TempDir Path tempDir) throws IOException {
        Path claudeHome = createClaudeHome(tempDir);
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));

        withClaudeHome(claudeHome, () -> {
            Path projectDir = makeProjectDir(claudeHome, projectPath);
            String sid = UUID.randomUUID().toString();
            Path filePath = projectDir.resolve(sid + ".jsonl");
            String userUuid = UUID.randomUUID().toString();
            String entry = "{\"type\":\"user\",\"uuid\":\"" + userUuid
                    + "\",\"parentUuid\":null,\"sessionId\":\"" + sid
                    + "\",\"timestamp\":\"2026-03-01T00:00:00Z\""
                    + ",\"teamName\":\"test-team\",\"agentName\":\"test-agent\""
                    + ",\"slug\":\"test-slug\""
                    + ",\"message\":{\"role\":\"user\",\"content\":\"Hello\"}}";
            Files.writeString(filePath, entry + "\n");

            ForkSessionResult result = SessionMutations.forkSession(
                    sid, projectPath, null, null);
            Path forkPath = projectDir.resolve(result.sessionId() + ".jsonl");

            for (String line : Files.readAllLines(forkPath)) {
                Map<String, Object> e = MAPPER.readValue(line, Map.class);
                if ("user".equals(e.get("type"))) {
                    assertThat(e).doesNotContainKey("teamName");
                    assertThat(e).doesNotContainKey("agentName");
                    assertThat(e).doesNotContainKey("slug");
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // listSessions with offset tests
    // -------------------------------------------------------------------------

    @Test
    void testListSessions_appliesOffset(@TempDir Path tempDir) throws IOException {
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        // Create 5 sessions
        for (int i = 0; i < 5; i++) {
            String sessionId = String.format("5234567%d-1234-1234-1234-123456789abc", i);
            String jsonl = "{\"type\":\"user\",\"uuid\":\"m" + i + "\",\"sessionId\":\""
                    + sessionId + "\",\"parentUuid\":null,"
                    + "\"message\":{\"role\":\"user\",\"content\":\"Session " + i + "\"}}\n"
                    + "{\"type\":\"system\",\"summary\":\"Session " + i + "\"}\n";
            Files.writeString(projectDir.resolve(sessionId + ".jsonl"), jsonl);
        }

        withClaudeHome(claudeHome, () -> {
            // All sessions
            List<SDKSessionInfo> all = Sessions.listSessions(null, null, 0, false);
            assertThat(all).hasSize(5);

            // Offset of 2, should return 3
            List<SDKSessionInfo> offset2 = Sessions.listSessions(null, null, 2, false);
            assertThat(offset2).hasSize(3);

            // Offset + limit for pagination
            List<SDKSessionInfo> page = Sessions.listSessions(null, 2, 2, false);
            assertThat(page).hasSize(2);

            // Offset past end
            List<SDKSessionInfo> empty = Sessions.listSessions(null, null, 10, false);
            assertThat(empty).isEmpty();
        });
    }

}
