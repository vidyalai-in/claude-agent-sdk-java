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

    // -------------------------------------------------------------------------
    // Tag extraction
    // -------------------------------------------------------------------------

    @Test
    void testExtractTagFromTail_present() {
        String tail = "{\"type\":\"user\",\"message\":{\"content\":\"hello\"}}\n"
                + "{\"type\":\"tag\",\"tag\":\"my-tag\",\"sessionId\":\"abc\"}\n";
        assertThat(Sessions.extractTagFromTail(tail)).isEqualTo("my-tag");
    }

    @Test
    void testExtractTagFromTail_lastWins() {
        String tail = "{\"type\":\"tag\",\"tag\":\"first\",\"sessionId\":\"abc\"}\n"
                + "{\"type\":\"tag\",\"tag\":\"second\",\"sessionId\":\"abc\"}\n";
        assertThat(Sessions.extractTagFromTail(tail)).isEqualTo("second");
    }

    @Test
    void testExtractTagFromTail_emptyStringIsNull() {
        String tail = "{\"type\":\"tag\",\"tag\":\"\",\"sessionId\":\"abc\"}\n";
        assertThat(Sessions.extractTagFromTail(tail)).isNull();
    }

    @Test
    void testExtractTagFromTail_absent() {
        String tail = "{\"type\":\"user\",\"message\":{\"content\":\"hello\"}}\n";
        assertThat(Sessions.extractTagFromTail(tail)).isNull();
    }

    @Test
    void testExtractTagFromTail_ignoresToolUseInputs() {
        // A tool_use entry with a "tag" key in its input — must NOT match
        String tail = "{\"type\":\"tag\",\"tag\":\"real-tag\",\"sessionId\":\"abc\"}\n"
                + "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\","
                + "\"name\":\"mcp__docker__build\",\"input\":{\"tag\":\"myapp:v2\"}}]}}\n";
        assertThat(Sessions.extractTagFromTail(tail)).isEqualTo("real-tag");
    }

    @Test
    void testExtractTagFromTail_noTagWhenOnlyToolUseTag() {
        String tail = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\","
                + "\"input\":{\"tag\":\"prod\"}}]}}\n";
        assertThat(Sessions.extractTagFromTail(tail)).isNull();
    }

    // -------------------------------------------------------------------------
    // createdAt extraction
    // -------------------------------------------------------------------------

    @Test
    void testExtractCreatedAtFromFirstLine_withZSuffix() {
        String line = "{\"type\":\"user\",\"message\":{\"content\":\"hello\"},"
                + "\"timestamp\":\"2026-01-15T10:30:00.000Z\"}";
        Long createdAt = Sessions.extractCreatedAtFromFirstLine(line);
        assertThat(createdAt).isNotNull();
        // 2026-01-15T10:30:00Z = 1768473000000 ms
        assertThat(createdAt).isEqualTo(1768473000000L);
    }

    @Test
    void testExtractCreatedAtFromFirstLine_withOffset() {
        String line = "{\"type\":\"user\",\"message\":{\"content\":\"hello\"},"
                + "\"timestamp\":\"2026-01-15T10:30:00+00:00\"}";
        Long createdAt = Sessions.extractCreatedAtFromFirstLine(line);
        assertThat(createdAt).isNotNull();
        assertThat(createdAt).isEqualTo(1768473000000L);
    }

    @Test
    void testExtractCreatedAtFromFirstLine_missingTimestamp() {
        String line = "{\"type\":\"user\",\"message\":{\"content\":\"hello\"}}";
        assertThat(Sessions.extractCreatedAtFromFirstLine(line)).isNull();
    }

    @Test
    void testExtractCreatedAtFromFirstLine_invalidFormat() {
        String line = "{\"type\":\"user\",\"message\":{\"content\":\"hello\"},"
                + "\"timestamp\":\"not-a-valid-iso-date\"}";
        assertThat(Sessions.extractCreatedAtFromFirstLine(line)).isNull();
    }

    // -------------------------------------------------------------------------
    // parseSessionInfoFromLite
    // -------------------------------------------------------------------------

    @Test
    void testExtractCreatedAtFromFirstLine_scansSubsequentLinesWhenFirstLacksTimestamp() {
        // Regression for Python SDK fix #907: when the first JSONL record is a
        // metadata-only entry (e.g. permission-mode) without a `timestamp`
        // field, scan the head buffer for the next record that has one.
        String head = "{\"type\":\"permission-mode\",\"permissionMode\":\"acceptEdits\"}\n"
                + "{\"type\":\"user\",\"message\":{\"content\":\"hello\"},"
                + "\"timestamp\":\"2026-01-15T10:30:00.000Z\"}\n";
        Long createdAt = Sessions.extractCreatedAtFromFirstLine(head);
        assertThat(createdAt).isEqualTo(1768473000000L);
    }

    @SuppressWarnings("null")
    @Test
    void testParseSessionInfoFromLite_createdAtFromLaterLineWhenFirstLacksTimestamp() {
        // Regression for Python SDK fix #907 — at the parseSessionInfoFromLite
        // level, not just the helper.
        String head = "{\"type\":\"permission-mode\",\"permissionMode\":\"acceptEdits\"}\n"
                + "{\"type\":\"user\",\"message\":{\"content\":\"hello\"},"
                + "\"cwd\":\"/workspace\","
                + "\"timestamp\":\"2026-01-15T10:30:00.000Z\"}\n";
        Sessions.LiteSessionFile lite = new Sessions.LiteSessionFile(
                System.currentTimeMillis(), 100L, head, head);

        SDKSessionInfo info = Sessions.parseSessionInfoFromLite("abc", lite, null);
        assertThat(info).isNotNull();
        assertThat(info.createdAt()).isEqualTo(1768473000000L);
    }

    @SuppressWarnings("null")
    @Test
    void testParseSessionInfoFromLite_withTagAndCreatedAt() {
        String head = "{\"type\":\"user\",\"message\":{\"content\":\"test prompt\"},"
                + "\"cwd\":\"/workspace\","
                + "\"timestamp\":\"2026-01-15T10:30:00.000Z\"}\n";
        String tail = head + "{\"type\":\"tag\",\"tag\":\"experiment\",\"sessionId\":\"abc\"}\n";

        Sessions.LiteSessionFile lite = new Sessions.LiteSessionFile(
                System.currentTimeMillis(), 100L, head, tail);

        SDKSessionInfo info = Sessions.parseSessionInfoFromLite("abc", lite, "/fallback");
        assertThat(info).isNotNull();
        assertThat(info.sessionId()).isEqualTo("abc");
        assertThat(info.summary()).isEqualTo("test prompt");
        assertThat(info.tag()).isEqualTo("experiment");
        assertThat(info.cwd()).isEqualTo("/workspace"); // head cwd wins over fallback
        assertThat(info.createdAt()).isEqualTo(1768473000000L);
    }

    @Test
    void testParseSessionInfoFromLite_sidechain() {
        String head = "{\"type\":\"user\",\"isSidechain\":true,\"message\":{\"content\":\"hi\"}}\n";
        Sessions.LiteSessionFile lite = new Sessions.LiteSessionFile(
                System.currentTimeMillis(), 50L, head, head);

        assertThat(Sessions.parseSessionInfoFromLite("abc", lite, null)).isNull();
    }

    @SuppressWarnings("null")
    @Test
    void testParseSessionInfoFromLite_aiTitleFallback() {
        // No customTitle, but has aiTitle → should use aiTitle
        String data = "{\"type\":\"user\",\"message\":{\"content\":\"hello\"}}\n"
                + "{\"aiTitle\":\"AI Generated Title\"}\n";
        Sessions.LiteSessionFile lite = new Sessions.LiteSessionFile(
                System.currentTimeMillis(), 80L, data, data);

        SDKSessionInfo info = Sessions.parseSessionInfoFromLite("abc", lite, null);
        assertThat(info).isNotNull();
        assertThat(info.customTitle()).isEqualTo("AI Generated Title");
        assertThat(info.summary()).isEqualTo("AI Generated Title");
    }

    @SuppressWarnings("null")
    @Test
    void testParseSessionInfoFromLite_lastPromptInSummary() {
        // No custom/ai title, but lastPrompt should win over summary
        String data = "{\"type\":\"user\",\"message\":{\"content\":\"hello\"}}\n"
                + "{\"lastPrompt\":\"recent query\",\"summary\":\"old summary\"}\n";
        Sessions.LiteSessionFile lite = new Sessions.LiteSessionFile(
                System.currentTimeMillis(), 80L, data, data);

        SDKSessionInfo info = Sessions.parseSessionInfoFromLite("abc", lite, null);
        assertThat(info).isNotNull();
        assertThat(info.summary()).isEqualTo("recent query");
    }

    // -------------------------------------------------------------------------
    // SDKSessionInfo new fields defaults
    // -------------------------------------------------------------------------

    @Test
    void testSDKSessionInfoNewFieldsDefaults() {
        SDKSessionInfo info = new SDKSessionInfo("abc", "test", 1000L, 42L, null, null, null, null);
        assertThat(info.tag()).isNull();
        assertThat(info.createdAt()).isNull();
    }

    @Test
    void testSDKSessionInfoWithAllFields() {
        SDKSessionInfo info = new SDKSessionInfo(
                "abc", "test", 1000L, 42L,
                "title", "prompt", "main", "/cwd",
                "my-tag", 1768473000000L);
        assertThat(info.tag()).isEqualTo("my-tag");
        assertThat(info.createdAt()).isEqualTo(1768473000000L);
    }

    // -------------------------------------------------------------------------
    // getSessionInfo
    // -------------------------------------------------------------------------

    @Test
    void testGetSessionInfo_invalidSessionId() {
        assertThat(Sessions.getSessionInfo("not-a-uuid", null)).isNull();
        assertThat(Sessions.getSessionInfo("", null)).isNull();
    }

    @Test
    void testGetSessionInfo_nonexistent(@TempDir Path tempDir) {
        withClaudeHome(tempDir, () -> {
            String uuid = "12345678-1234-1234-1234-123456789abc";
            assertThat(Sessions.getSessionInfo(uuid, null)).isNull();
        });
    }

    @SuppressWarnings("null")
    @Test
    void testGetSessionInfo_foundWithDirectory(@TempDir Path tempDir) throws IOException {
        Path claudeHome = tempDir;
        String projectPath = tempDir.resolve("myproject").toString();
        Files.createDirectories(Path.of(projectPath));
        Path projectDir = claudeHome.resolve(".claude").resolve("projects")
                .resolve(Sessions.sanitizePath(projectPath));
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        String jsonl = makeUserLine(sessionId, "msg-1", null, "Hello from test") + "\n";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"), jsonl);

        withClaudeHome(claudeHome, () -> {
            SDKSessionInfo info = Sessions.getSessionInfo(sessionId, projectPath);
            assertThat(info).isNotNull();
            assertThat(info.sessionId()).isEqualTo(sessionId);
            assertThat(info.summary()).contains("Hello from test");
        });
    }

    @SuppressWarnings("null")
    @Test
    void testGetSessionInfo_foundWithoutDirectory(@TempDir Path tempDir) throws IOException {
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("some-project");
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        String jsonl = makeUserLine(sessionId, "msg-1", null, "search all") + "\n";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"), jsonl);

        withClaudeHome(claudeHome, () -> {
            SDKSessionInfo info = Sessions.getSessionInfo(sessionId, null);
            assertThat(info).isNotNull();
            assertThat(info.summary()).contains("search all");
        });
    }

    @Test
    void testGetSessionInfo_returnsNullForSidechain(@TempDir Path tempDir) throws IOException {
        Path claudeHome = tempDir;
        String projectPath = tempDir.resolve("proj").toString();
        Files.createDirectories(Path.of(projectPath));
        Path projectDir = claudeHome.resolve(".claude").resolve("projects")
                .resolve(Sessions.sanitizePath(projectPath));
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        String jsonl = "{\"type\":\"user\",\"uuid\":\"m1\",\"sessionId\":\"" + sessionId
                + "\",\"isSidechain\":true,"
                + "\"message\":{\"role\":\"user\",\"content\":\"hi\"}}\n";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"), jsonl);

        withClaudeHome(claudeHome, () -> {
            assertThat(Sessions.getSessionInfo(sessionId, projectPath)).isNull();
        });
    }

    // -------------------------------------------------------------------------
    // listSubagents / getSubagentMessages
    // -------------------------------------------------------------------------

    @Test
    void testListSubagents_invalidSessionIdReturnsEmpty() {
        assertThat(Sessions.listSubagents("not-a-uuid", null)).isEmpty();
    }

    @Test
    void testListSubagents_missingSessionReturnsEmpty(@TempDir Path tempDir) {
        withClaudeHome(tempDir, () -> {
            String sid = "12345678-1234-1234-1234-123456789abc";
            assertThat(Sessions.listSubagents(sid, null)).isEmpty();
        });
    }

    @Test
    void testListSubagents_returnsAgentIds(@TempDir Path tempDir) throws IOException {
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"),
                makeUserLine(sessionId, "u1", null, "hello") + "\n");

        Path subagentsDir = projectDir.resolve(sessionId).resolve("subagents");
        Files.createDirectories(subagentsDir);
        Files.writeString(subagentsDir.resolve("agent-abc123.jsonl"), "{}\n");
        Files.writeString(subagentsDir.resolve("agent-def456.jsonl"), "{}\n");

        // Nested subagent file under workflows/<runId>/
        Path nested = subagentsDir.resolve("workflows").resolve("run1");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("agent-nested789.jsonl"), "{}\n");

        withClaudeHome(claudeHome, () -> {
            List<String> ids = Sessions.listSubagents(sessionId, null);
            assertThat(ids).containsExactlyInAnyOrder("abc123", "def456", "nested789");
        });
    }

    @Test
    void testGetSubagentMessages_invalidSessionIdReturnsEmpty() {
        assertThat(Sessions.getSubagentMessages("not-a-uuid", "abc", null, null, 0))
                .isEmpty();
    }

    @Test
    void testGetSubagentMessages_emptyAgentIdReturnsEmpty(@TempDir Path tempDir) {
        withClaudeHome(tempDir, () -> {
            String sid = "12345678-1234-1234-1234-123456789abc";
            assertThat(Sessions.getSubagentMessages(sid, "", null, null, 0)).isEmpty();
        });
    }

    @Test
    void testGetSubagentMessages_readsAgentTranscript(@TempDir Path tempDir) throws IOException {
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"),
                makeUserLine(sessionId, "u1", null, "hello") + "\n");

        Path subagentsDir = projectDir.resolve(sessionId).resolve("subagents");
        Files.createDirectories(subagentsDir);
        String agentJsonl = makeUserLine(sessionId, "a1", null, "Run the task") + "\n"
                + makeAssistantLine(sessionId, "a2", "a1", "Done.") + "\n";
        Files.writeString(subagentsDir.resolve("agent-xyz.jsonl"), agentJsonl);

        withClaudeHome(claudeHome, () -> {
            List<SessionMessage> messages = Sessions.getSubagentMessages(
                    sessionId, "xyz", null, null, 0);
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).type()).isEqualTo("user");
            assertThat(messages.get(1).type()).isEqualTo("assistant");
        });
    }

    @Test
    void testGetSubagentMessages_parentIdsRecoveredFromMetaSidecar(@TempDir Path tempDir)
            throws IOException {
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"),
                makeUserLine(sessionId, "u1", null, "hello") + "\n");

        Path subagentsDir = projectDir.resolve(sessionId).resolve("subagents");
        Files.createDirectories(subagentsDir);
        Files.writeString(subagentsDir.resolve("agent-xyz.jsonl"),
                makeUserLine(sessionId, "a1", null, "Run the task") + "\n"
                        + makeAssistantLine(sessionId, "a2", "a1", "Done.") + "\n");
        // The transcript lines carry no parent ids; the sidecar does.
        Files.writeString(subagentsDir.resolve("agent-xyz.meta.json"),
                "{\"toolUseId\":\"toolu_01\",\"parentAgentId\":\"a-parent\","
                        + "\"agentType\":\"general-purpose\"}");

        withClaudeHome(claudeHome, () -> {
            List<SessionMessage> messages = Sessions.getSubagentMessages(
                    sessionId, "xyz", null, null, 0);
            assertThat(messages).hasSize(2);
            // Every message in a subagent transcript shares the same parents.
            assertThat(messages).allSatisfy(m -> {
                assertThat(m.parentToolUseId()).isEqualTo("toolu_01");
                assertThat(m.parentAgentId()).isEqualTo("a-parent");
            });
        });
    }

    @Test
    void testGetSubagentMessages_parentIdsFromNestedMetaSidecar(@TempDir Path tempDir)
            throws IOException {
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"),
                makeUserLine(sessionId, "u1", null, "hello") + "\n");

        Path nested = projectDir.resolve(sessionId).resolve("subagents")
                .resolve("workflows").resolve("run1");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("agent-nested.jsonl"),
                makeUserLine(sessionId, "a1", null, "Run the task") + "\n");
        Files.writeString(nested.resolve("agent-nested.meta.json"),
                "{\"toolUseId\":\"toolu_nested\"}");

        withClaudeHome(claudeHome, () -> {
            List<SessionMessage> messages = Sessions.getSubagentMessages(
                    sessionId, "nested", null, null, 0);
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).parentToolUseId()).isEqualTo("toolu_nested");
            // Spawned by the main session, so no parent agent.
            assertThat(messages.get(0).parentAgentId()).isNull();
        });
    }

    @SuppressWarnings("null")
    @Test
    void testGetSubagentMessages_parentIdsNullWhenSidecarMissingOrUnusable(@TempDir Path tempDir)
            throws IOException {
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"),
                makeUserLine(sessionId, "u1", null, "hello") + "\n");

        Path subagentsDir = projectDir.resolve(sessionId).resolve("subagents");
        Files.createDirectories(subagentsDir);
        String line = makeUserLine(sessionId, "a1", null, "Run the task") + "\n";

        // No sidecar at all.
        Files.writeString(subagentsDir.resolve("agent-nometa.jsonl"), line);
        // Corrupt JSON.
        Files.writeString(subagentsDir.resolve("agent-corrupt.jsonl"), line);
        Files.writeString(subagentsDir.resolve("agent-corrupt.meta.json"), "{not json");
        // Valid JSON but not an object.
        Files.writeString(subagentsDir.resolve("agent-array.jsonl"), line);
        Files.writeString(subagentsDir.resolve("agent-array.meta.json"), "[1,2]");
        // Object with non-string ids.
        Files.writeString(subagentsDir.resolve("agent-typed.jsonl"), line);
        Files.writeString(subagentsDir.resolve("agent-typed.meta.json"),
                "{\"toolUseId\":42,\"parentAgentId\":null}");

        withClaudeHome(claudeHome, () -> {
            for (String agentId : List.of("nometa", "corrupt", "array", "typed")) {
                List<SessionMessage> messages = Sessions.getSubagentMessages(
                        sessionId, agentId, null, null, 0);
                assertThat(messages).as(agentId).hasSize(1);
                assertThat(messages.get(0).parentToolUseId()).as(agentId).isNull();
                assertThat(messages.get(0).parentAgentId()).as(agentId).isNull();
            }
        });
    }

    @Test
    void testGetSubagentMessages_unreadableSidecarDegradesToNull(@TempDir Path tempDir)
            throws IOException {
        // A sidecar that exists but cannot be read (here: a directory in its
        // place) must not make the best-effort read helper raise — the
        // transcript is still returned, just without parent ids.
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"),
                makeUserLine(sessionId, "u1", null, "hello") + "\n");

        Path subagentsDir = projectDir.resolve(sessionId).resolve("subagents");
        Files.createDirectories(subagentsDir);
        Files.writeString(subagentsDir.resolve("agent-x.jsonl"),
                makeUserLine(sessionId, "a1", null, "Run the task") + "\n"
                        + makeAssistantLine(sessionId, "a2", "a1", "Done.") + "\n");
        Files.createDirectories(subagentsDir.resolve("agent-x.meta.json"));

        withClaudeHome(claudeHome, () -> {
            List<SessionMessage> messages = Sessions.getSubagentMessages(
                    sessionId, "x", null, null, 0);
            assertThat(messages).hasSize(2);
            assertThat(messages).allSatisfy(m -> {
                assertThat(m.parentToolUseId()).isNull();
                assertThat(m.parentAgentId()).isNull();
            });
        });
    }

    @Test
    void testGetSessionMessages_parentIdsAreAlwaysNull(@TempDir Path tempDir) throws IOException {
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"),
                makeUserLine(sessionId, "u1", null, "hello") + "\n"
                        + makeAssistantLine(sessionId, "u2", "u1", "hi") + "\n");

        withClaudeHome(claudeHome, () -> {
            List<SessionMessage> messages = Sessions.getSessionMessages(sessionId, null, null, 0);
            assertThat(messages).hasSize(2);
            assertThat(messages).allSatisfy(m -> {
                assertThat(m.parentToolUseId()).isNull();
                assertThat(m.parentAgentId()).isNull();
            });
        });
    }

    @Test
    void testListSubagents_sessionExistsButNoSubagentsDir(@TempDir Path tempDir) throws IOException {
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"),
                makeUserLine(sessionId, "u1", null, "hi") + "\n");

        withClaudeHome(claudeHome, () -> {
            assertThat(Sessions.listSubagents(sessionId, null)).isEmpty();
        });
    }

    @Test
    void testListSubagents_emptySubagentsDir(@TempDir Path tempDir) throws IOException {
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"),
                makeUserLine(sessionId, "u1", null, "hi") + "\n");
        Files.createDirectories(projectDir.resolve(sessionId).resolve("subagents"));

        withClaudeHome(claudeHome, () -> {
            assertThat(Sessions.listSubagents(sessionId, null)).isEmpty();
        });
    }

    @Test
    void testListSubagents_ignoresNonAgentFiles(@TempDir Path tempDir) throws IOException {
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"),
                makeUserLine(sessionId, "u1", null, "hi") + "\n");

        Path subagentsDir = projectDir.resolve(sessionId).resolve("subagents");
        Files.createDirectories(subagentsDir);
        Files.writeString(subagentsDir.resolve("agent-keep.jsonl"), "{}\n");
        Files.writeString(subagentsDir.resolve("agent-keep.meta.json"), "{}");
        Files.writeString(subagentsDir.resolve("other.jsonl"), "{}\n");
        Files.writeString(subagentsDir.resolve("agent-noext"), "{}");

        withClaudeHome(claudeHome, () -> {
            assertThat(Sessions.listSubagents(sessionId, null)).containsExactly("keep");
        });
    }

    @Test
    void testListSubagents_searchesAllProjectsWithoutDirectory(@TempDir Path tempDir) throws IOException {
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("some-project");
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"),
                makeUserLine(sessionId, "u1", null, "hi") + "\n");

        Path subagentsDir = projectDir.resolve(sessionId).resolve("subagents");
        Files.createDirectories(subagentsDir);
        Files.writeString(subagentsDir.resolve("agent-x.jsonl"), "{}\n");

        withClaudeHome(claudeHome, () -> {
            assertThat(Sessions.listSubagents(sessionId, null)).containsExactly("x");
        });
    }

    @Test
    void testGetSubagentMessages_nonexistentAgentReturnsEmpty(@TempDir Path tempDir) throws IOException {
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"),
                makeUserLine(sessionId, "u1", null, "hi") + "\n");

        Path subagentsDir = projectDir.resolve(sessionId).resolve("subagents");
        Files.createDirectories(subagentsDir);
        Files.writeString(subagentsDir.resolve("agent-other.jsonl"), "{}\n");

        withClaudeHome(claudeHome, () -> {
            assertThat(Sessions.getSubagentMessages(sessionId, "missing", null, null, 0))
                    .isEmpty();
        });
    }

    @Test
    void testGetSubagentMessages_findsAgentInNestedSubdirectory(@TempDir Path tempDir) throws IOException {
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"),
                makeUserLine(sessionId, "u1", null, "hi") + "\n");

        Path nested = projectDir.resolve(sessionId).resolve("subagents")
                .resolve("workflows").resolve("run-1");
        Files.createDirectories(nested);
        String jsonl = makeUserLine(sessionId, "n1", null, "hi") + "\n"
                + makeAssistantLine(sessionId, "n2", "n1", "hello") + "\n";
        Files.writeString(nested.resolve("agent-deep.jsonl"), jsonl);

        withClaudeHome(claudeHome, () -> {
            List<SessionMessage> messages = Sessions.getSubagentMessages(
                    sessionId, "deep", null, null, 0);
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).uuid()).isEqualTo("n1");
            assertThat(messages.get(1).uuid()).isEqualTo("n2");
        });
    }

    @Test
    void testGetSubagentMessages_skipsCorruptLines(@TempDir Path tempDir) throws IOException {
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"),
                makeUserLine(sessionId, "u1", null, "hi") + "\n");

        Path subagentsDir = projectDir.resolve(sessionId).resolve("subagents");
        Files.createDirectories(subagentsDir);
        String jsonl = makeUserLine(sessionId, "a1", null, "hi") + "\n"
                + "not valid json {\n"
                + "\n"
                + makeAssistantLine(sessionId, "a2", "a1", "ok") + "\n";
        Files.writeString(subagentsDir.resolve("agent-x.jsonl"), jsonl);

        withClaudeHome(claudeHome, () -> {
            List<SessionMessage> messages = Sessions.getSubagentMessages(
                    sessionId, "x", null, null, 0);
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).uuid()).isEqualTo("a1");
            assertThat(messages.get(1).uuid()).isEqualTo("a2");
        });
    }

    @Test
    void testGetSubagentMessages_emptyAgentFileReturnsEmpty(@TempDir Path tempDir) throws IOException {
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"),
                makeUserLine(sessionId, "u1", null, "hi") + "\n");

        Path subagentsDir = projectDir.resolve(sessionId).resolve("subagents");
        Files.createDirectories(subagentsDir);
        Files.writeString(subagentsDir.resolve("agent-empty.jsonl"), "");

        withClaudeHome(claudeHome, () -> {
            assertThat(Sessions.getSubagentMessages(sessionId, "empty", null, null, 0))
                    .isEmpty();
        });
    }

    @Test
    void testGetSubagentMessages_limitZeroReturnsAll(@TempDir Path tempDir) throws IOException {
        // Python SDK semantics: limit=0 (or negative) is treated as no-limit.
        // Java overload uses Integer; @Nullable null = no limit. limit=0 also
        // returns all to match Python behavior.
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"),
                makeUserLine(sessionId, "u1", null, "hi") + "\n");

        Path subagentsDir = projectDir.resolve(sessionId).resolve("subagents");
        Files.createDirectories(subagentsDir);
        String jsonl = makeUserLine(sessionId, "a1", null, "first") + "\n"
                + makeAssistantLine(sessionId, "a2", "a1", "second") + "\n"
                + makeUserLine(sessionId, "a3", "a2", "third") + "\n";
        Files.writeString(subagentsDir.resolve("agent-p.jsonl"), jsonl);

        withClaudeHome(claudeHome, () -> {
            assertThat(Sessions.getSubagentMessages(sessionId, "p", null, 0, 0))
                    .hasSize(3);
        });
    }

    @Test
    void testGetSubagentMessages_appliesLimitAndOffset(@TempDir Path tempDir) throws IOException {
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"),
                makeUserLine(sessionId, "u1", null, "hello") + "\n");

        Path subagentsDir = projectDir.resolve(sessionId).resolve("subagents");
        Files.createDirectories(subagentsDir);
        String agentJsonl = makeUserLine(sessionId, "a1", null, "first") + "\n"
                + makeAssistantLine(sessionId, "a2", "a1", "second") + "\n"
                + makeUserLine(sessionId, "a3", "a2", "third") + "\n"
                + makeAssistantLine(sessionId, "a4", "a3", "fourth") + "\n";
        Files.writeString(subagentsDir.resolve("agent-xyz.jsonl"), agentJsonl);

        withClaudeHome(claudeHome, () -> {
            List<SessionMessage> sliced = Sessions.getSubagentMessages(
                    sessionId, "xyz", null, 2, 1);
            assertThat(sliced).hasSize(2);
            assertThat(sliced.get(0).uuid()).isEqualTo("a2");
            assertThat(sliced.get(1).uuid()).isEqualTo("a3");
        });
    }

    @Test
    void testListSessions_includesTagAndCreatedAt(@TempDir Path tempDir) throws IOException {
        Path claudeHome = tempDir;
        Path projectDir = claudeHome.resolve(".claude").resolve("projects").resolve("proj");
        Files.createDirectories(projectDir);

        String sessionId = "12345678-1234-1234-1234-123456789abc";
        String jsonl = "{\"type\":\"user\",\"message\":{\"content\":\"hello\"},"
                + "\"timestamp\":\"2026-01-15T10:30:00.000Z\"}\n"
                + "{\"type\":\"tag\",\"tag\":\"my-tag\",\"sessionId\":\"" + sessionId + "\"}\n";
        Files.writeString(projectDir.resolve(sessionId + ".jsonl"), jsonl);

        withClaudeHome(claudeHome, () -> {
            List<SDKSessionInfo> sessions = Sessions.listSessions(null, null, false);
            assertThat(sessions).hasSize(1);
            assertThat(sessions.get(0).tag()).isEqualTo("my-tag");
            assertThat(sessions.get(0).createdAt()).isEqualTo(1768473000000L);
        });
    }

}
