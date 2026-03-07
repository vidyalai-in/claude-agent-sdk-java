package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import in.vidyalai.claude.sdk.types.message.SDKSessionInfo;
import in.vidyalai.claude.sdk.types.message.SessionMessage;

/**
 * Unit tests for the Sessions internal class.
 */
class SessionsTest {

    // -------------------------------------------------------------------------
    // sanitizePath
    // -------------------------------------------------------------------------

    @Test
    void testSanitizePath_simple() {
        assertThat(Sessions.sanitizePath("/home/user/project"))
                .isEqualTo("-home-user-project");
    }

    @Test
    void testSanitizePath_alphanumericUnchanged() {
        assertThat(Sessions.sanitizePath("myProject123"))
                .isEqualTo("myProject123");
    }

    @Test
    void testSanitizePath_longPath() {
        String longPath = "/home/user/" + "a".repeat(200);
        String result = Sessions.sanitizePath(longPath);

        // Truncated to 200 + "-" + hash
        assertThat(result.length()).isGreaterThan(200);
        assertThat(result.substring(0, 200)).hasSize(200);
        assertThat(result.charAt(200)).isEqualTo('-');
    }

    @Test
    void testSanitizePath_exactlyMaxLength() {
        String path = "a".repeat(200);
        assertThat(Sessions.sanitizePath(path)).isEqualTo(path);
        assertThat(Sessions.sanitizePath(path)).hasSize(200);
    }

    @Test
    void testSanitizePath_oneBeyondMax() {
        String path = "a".repeat(201);
        String result = Sessions.sanitizePath(path);
        assertThat(result.length()).isGreaterThan(200);
        assertThat(result.substring(0, 200)).isEqualTo("a".repeat(200));
        assertThat(result.charAt(200)).isEqualTo('-');
    }

    // -------------------------------------------------------------------------
    // simpleHash
    // -------------------------------------------------------------------------

    @Test
    void testSimpleHash_emptyString() {
        // h stays 0, Integer.toString(0, 36) = "0"
        assertThat(Sessions.simpleHash("")).isEqualTo("0");
    }

    @Test
    void testSimpleHash_singleChar() {
        // 'a' = 97; h = (0 << 5) - 0 + 97 = 97
        // Integer.toString(97, 36): 97 = 2*36 + 25 → "2p"
        assertThat(Sessions.simpleHash("a")).isEqualTo("2p");
    }

    @Test
    void testSimpleHash_deterministic() {
        String h1 = Sessions.simpleHash("hello world");
        String h2 = Sessions.simpleHash("hello world");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void testSimpleHash_differentInputsDifferentHashes() {
        assertThat(Sessions.simpleHash("abc")).isNotEqualTo(Sessions.simpleHash("xyz"));
    }

    @Test
    void testSimpleHash_neverNegative() {
        for (String s : List.of("", "a", "hello", "/home/user/project", "test-123")) {
            assertThat(Sessions.simpleHash(s)).doesNotStartWith("-");
        }
    }

    // -------------------------------------------------------------------------
    // extractJsonStringField
    // -------------------------------------------------------------------------

    @Test
    void testExtractJsonStringField_simple() {
        String json = "{\"summary\":\"My Session\",\"other\":\"value\"}";
        assertThat(Sessions.extractJsonStringField(json, "summary"))
                .isEqualTo("My Session");
    }

    @Test
    void testExtractJsonStringField_withSpaces() {
        String json = "{ \"title\" : \"Hello World\" }";
        assertThat(Sessions.extractJsonStringField(json, "title"))
                .isEqualTo("Hello World");
    }

    @Test
    void testExtractJsonStringField_missingKey() {
        String json = "{\"other\":\"value\"}";
        assertThat(Sessions.extractJsonStringField(json, "summary")).isNull();
    }

    @Test
    void testExtractJsonStringField_withEscapes() {
        String json = "{\"title\":\"Hello\\nWorld\"}";
        assertThat(Sessions.extractJsonStringField(json, "title"))
                .isEqualTo("Hello\nWorld");
    }

    @Test
    void testExtractLastJsonStringField_returnsLast() {
        String text = "{\"summary\":\"First\"}\n{\"summary\":\"Second\"}";
        assertThat(Sessions.extractLastJsonStringField(text, "summary"))
                .isEqualTo("Second");
    }

    @Test
    void testExtractJsonStringField_returnsFirst() {
        String text = "{\"summary\":\"First\"}\n{\"summary\":\"Second\"}";
        assertThat(Sessions.extractJsonStringField(text, "summary"))
                .isEqualTo("First");
    }

    // -------------------------------------------------------------------------
    // listSessions (using temp dir)
    // -------------------------------------------------------------------------

    @Test
    void testListSessions_emptyDir(@TempDir Path tempDir) throws IOException {
        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir.resolve("projects"));

        withClaudeHome(claudeDir.getParent(), () -> {
            List<SDKSessionInfo> sessions = Sessions.listSessions(null, null, false);
            assertThat(sessions).isEmpty();
        });
    }

    @Test
    void testListSessions_singleSession(@TempDir Path tempDir) throws IOException {
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("my-project");
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        String jsonl = makeUserLine(sessionId, "msg-1", null, "Hello from test") + "\n"
                + makeSystemLine("{\"summary\":\"Test Session\",\"cwd\":\"/my-project\"}") + "\n";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"), jsonl);

        withClaudeHome(claudeHome, () -> {
            List<SDKSessionInfo> sessions = Sessions.listSessions(null, null, false);
            assertThat(sessions).hasSize(1);

            SDKSessionInfo info = sessions.get(0);
            assertThat(info.sessionId()).isEqualTo(sessionId);
            assertThat(info.summary()).isNotBlank();
            assertThat(info.fileSize()).isGreaterThan(0);
            assertThat(info.lastModified()).isGreaterThan(0);
        });
    }

    @Test
    void testListSessions_skipsSidechain(@TempDir Path tempDir) throws IOException {
        Path projectDir = tempDir.resolve(".claude").resolve("projects").resolve("my-project");
        Files.createDirectories(projectDir);

        String sessionId = "22345678-1234-1234-1234-123456789abc";
        String jsonl = "{\"type\":\"user\",\"uuid\":\"m1\",\"sessionId\":\"" + sessionId
                + "\",\"isSidechain\":true,"
                + "\"message\":{\"role\":\"user\",\"content\":\"hi\"}}\n";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"), jsonl);

        withClaudeHome(tempDir, () -> {
            List<SDKSessionInfo> sessions = Sessions.listSessions(null, null, false);
            assertThat(sessions).isEmpty();
        });
    }

    @Test
    void testListSessions_sortsByLastModified(@TempDir Path tempDir) throws IOException {
        Path projectDir = tempDir.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String olderSessionId = "32345678-1234-1234-1234-123456789abc";
        String newerSessionId = "42345678-1234-1234-1234-123456789abc";

        String olderJsonl = makeUserLine(olderSessionId, "m1", null, "Older") + "\n"
                + makeSystemLine("{\"summary\":\"Older\"}") + "\n";
        String newerJsonl = makeUserLine(newerSessionId, "m2", null, "Newer") + "\n"
                + makeSystemLine("{\"summary\":\"Newer\"}") + "\n";

        Path olderFile = projectDir.resolve(olderSessionId + ".jsonl");
        Path newerFile = projectDir.resolve(newerSessionId + ".jsonl");

        Files.writeString(olderFile, olderJsonl);
        Files.writeString(newerFile, newerJsonl);
        Files.setLastModifiedTime(olderFile, FileTime.fromMillis(System.currentTimeMillis() - 10000));
        Files.setLastModifiedTime(newerFile, FileTime.fromMillis(System.currentTimeMillis()));

        withClaudeHome(tempDir, () -> {
            List<SDKSessionInfo> sessions = Sessions.listSessions(null, null, false);
            assertThat(sessions).hasSizeGreaterThanOrEqualTo(2);
            assertThat(sessions.get(0).lastModified())
                    .isGreaterThanOrEqualTo(sessions.get(1).lastModified());
        });
    }

    @Test
    void testListSessions_appliesLimit(@TempDir Path tempDir) throws IOException {
        Path projectDir = tempDir.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        for (int i = 0; i < 5; i++) {
            String sessionId = String.format("5234567%d-1234-1234-1234-123456789abc", i);
            String jsonl = makeUserLine(sessionId, "m" + i, null, "Session " + i) + "\n"
                    + makeSystemLine("{\"summary\":\"Session " + i + "\"}") + "\n";
            Files.writeString(projectDir.resolve(sessionId + ".jsonl"), jsonl);
        }

        withClaudeHome(tempDir, () -> {
            List<SDKSessionInfo> sessions = Sessions.listSessions(null, 3, false);
            assertThat(sessions).hasSize(3);
        });
    }

    // -------------------------------------------------------------------------
    // getSessionMessages
    // -------------------------------------------------------------------------

    @Test
    void testGetSessionMessages_basic(@TempDir Path tempDir) throws IOException {
        Path projectDir = tempDir.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String sessionId = "62345678-1234-1234-1234-123456789abc";
        String m1 = "aaaaaaaa-0001-0001-0001-000000000001";
        String m2 = "aaaaaaaa-0002-0002-0002-000000000002";

        String jsonl = makeUserLine(sessionId, m1, null, "What is 2+2?") + "\n"
                + makeAssistantLine(sessionId, m2, m1, "4") + "\n";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"), jsonl);

        withClaudeHome(tempDir, () -> {
            List<SessionMessage> messages = Sessions.getSessionMessages(sessionId, null, null, 0);
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).type()).isEqualTo("user");
            assertThat(messages.get(0).uuid()).isEqualTo(m1);
            assertThat(messages.get(1).type()).isEqualTo("assistant");
            assertThat(messages.get(1).uuid()).isEqualTo(m2);
        });
    }

    @Test
    void testGetSessionMessages_offset(@TempDir Path tempDir) throws IOException {
        Path projectDir = tempDir.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String sessionId = "72345678-1234-1234-1234-123456789abc";
        String m1 = "bbbbbbbb-0001-0001-0001-000000000001";
        String m2 = "bbbbbbbb-0002-0002-0002-000000000002";
        String m3 = "bbbbbbbb-0003-0003-0003-000000000003";
        String m4 = "bbbbbbbb-0004-0004-0004-000000000004";

        String jsonl = makeUserLine(sessionId, m1, null, "Q1") + "\n"
                + makeAssistantLine(sessionId, m2, m1, "A1") + "\n"
                + makeUserLine(sessionId, m3, m2, "Q2") + "\n"
                + makeAssistantLine(sessionId, m4, m3, "A2") + "\n";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"), jsonl);

        withClaudeHome(tempDir, () -> {
            List<SessionMessage> messages = Sessions.getSessionMessages(sessionId, null, null, 2);
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).uuid()).isEqualTo(m3);
        });
    }

    @Test
    void testGetSessionMessages_limit(@TempDir Path tempDir) throws IOException {
        Path projectDir = tempDir.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String sessionId = "82345678-1234-1234-1234-123456789abc";
        String m1 = "cccccccc-0001-0001-0001-000000000001";
        String m2 = "cccccccc-0002-0002-0002-000000000002";
        String m3 = "cccccccc-0003-0003-0003-000000000003";

        String jsonl = makeUserLine(sessionId, m1, null, "Q") + "\n"
                + makeAssistantLine(sessionId, m2, m1, "A") + "\n"
                + makeUserLine(sessionId, m3, m2, "Q2") + "\n";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"), jsonl);

        withClaudeHome(tempDir, () -> {
            List<SessionMessage> messages = Sessions.getSessionMessages(sessionId, null, 2, 0);
            assertThat(messages).hasSize(2);
        });
    }

    @Test
    void testGetSessionMessages_invalidUuid() {
        List<SessionMessage> messages = Sessions.getSessionMessages("not-a-uuid", null, null, 0);
        assertThat(messages).isEmpty();
    }

    @Test
    void testGetSessionMessages_missingFile(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve(".claude").resolve("projects"));

        withClaudeHome(tempDir, () -> {
            String sessionId = "92345678-1234-1234-1234-123456789abc";
            List<SessionMessage> messages = Sessions.getSessionMessages(sessionId, null, null, 0);
            assertThat(messages).isEmpty();
        });
    }

    // -------------------------------------------------------------------------
    // buildConversationChain
    // -------------------------------------------------------------------------

    @Test
    void testBuildConversationChain_linearChain() {
        Map<String, Object> m1 = makeEntry("user", "u1", null, "sess", false, false);
        Map<String, Object> m2 = makeEntry("assistant", "a1", "u1", "sess", false, false);
        Map<String, Object> m3 = makeEntry("user", "u2", "a1", "sess", false, false);

        List<Map<String, Object>> chain = Sessions.buildConversationChain(List.of(m1, m2, m3));

        assertThat(chain).hasSize(3);
        assertThat(chain.get(0).get("uuid")).isEqualTo("u1");
        assertThat(chain.get(1).get("uuid")).isEqualTo("a1");
        assertThat(chain.get(2).get("uuid")).isEqualTo("u2");
    }

    @Test
    void testBuildConversationChain_skipsSidechain() {
        Map<String, Object> main1 = makeEntry("user", "u1", null, "sess", false, false);
        Map<String, Object> main2 = makeEntry("assistant", "a1", "u1", "sess", false, false);
        // Sidechain branching off u1 — should be excluded from the main chain
        Map<String, Object> side = makeEntry("user", "s1", "u1", "sess", true, false);

        List<Map<String, Object>> chain = Sessions.buildConversationChain(
                List.of(main1, main2, side));

        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).get("uuid")).isEqualTo("u1");
        assertThat(chain.get(1).get("uuid")).isEqualTo("a1");
    }

    @Test
    void testBuildConversationChain_emptyEntries() {
        List<Map<String, Object>> chain = Sessions.buildConversationChain(List.of());
        assertThat(chain).isEmpty();
    }

    // -------------------------------------------------------------------------
    // deduplicateBySessionId
    // -------------------------------------------------------------------------

    @Test
    void testDeduplicateBySessionId_keepsNewest() {
        SDKSessionInfo older = new SDKSessionInfo("sess-1", "Old", 1000L, 100L, null, null, null, null);
        SDKSessionInfo newer = new SDKSessionInfo("sess-1", "New", 2000L, 200L, null, null, null, null);

        List<SDKSessionInfo> deduped = Sessions.deduplicateBySessionId(List.of(older, newer));
        assertThat(deduped).hasSize(1);
        assertThat(deduped.get(0).lastModified()).isEqualTo(2000L);
        assertThat(deduped.get(0).summary()).isEqualTo("New");
    }

    @Test
    void testDeduplicateBySessionId_differentSessionsKeptAll() {
        SDKSessionInfo s1 = new SDKSessionInfo("sess-1", "A", 1000L, 100L, null, null, null, null);
        SDKSessionInfo s2 = new SDKSessionInfo("sess-2", "B", 2000L, 200L, null, null, null, null);

        List<SDKSessionInfo> deduped = Sessions.deduplicateBySessionId(List.of(s1, s2));
        assertThat(deduped).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String makeUserLine(String sessionId, String uuid, String parentUuid, String text) {
        String parent = parentUuid != null ? "\"" + parentUuid + "\"" : "null";
        return "{\"type\":\"user\",\"uuid\":\"" + uuid + "\",\"sessionId\":\"" + sessionId
                + "\",\"parentUuid\":" + parent
                + ",\"message\":{\"role\":\"user\",\"content\":\"" + escape(text) + "\"}}";
    }

    private String makeAssistantLine(String sessionId, String uuid, String parentUuid, String text) {
        String parent = parentUuid != null ? "\"" + parentUuid + "\"" : "null";
        return "{\"type\":\"assistant\",\"uuid\":\"" + uuid + "\",\"sessionId\":\"" + sessionId
                + "\",\"parentUuid\":" + parent
                + ",\"message\":{\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"" + escape(text) + "\"}]}}";
    }

    private String makeSystemLine(String extraJson) {
        String inner = extraJson.substring(1, extraJson.length() - 1);
        return "{\"type\":\"system\"," + inner + "}";
    }

    private Map<String, Object> makeEntry(
            String type, String uuid, String parentUuid,
            String sessionId, boolean isSidechain, boolean isMeta) {
        Map<String, Object> entry = new java.util.HashMap<>();
        entry.put("type", type);
        entry.put("uuid", uuid);
        entry.put("sessionId", sessionId);
        if (parentUuid != null)
            entry.put("parentUuid", parentUuid);
        if (isSidechain)
            entry.put("isSidechain", true);
        if (isMeta)
            entry.put("isMeta", true);
        entry.put("message", Map.of("role", type, "content", "test"));
        return entry;
    }

    private String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Temporarily overrides user.home so Sessions reads from a test Claude dir.
     */
    private void withClaudeHome(Path home, Runnable test) {
        String original = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            test.run();
        } finally {
            System.setProperty("user.home", original);
        }
    }
}
