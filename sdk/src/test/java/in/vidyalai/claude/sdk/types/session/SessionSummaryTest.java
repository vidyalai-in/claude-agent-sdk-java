package in.vidyalai.claude.sdk.types.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.types.message.SDKSessionInfo;

class SessionSummaryTest {

    private static SessionStoreEntry user(String uuid, String text, String ts) {
        Map<String, Object> message = Map.of(
                "content", List.of(Map.of("type", "text", "text", text)));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "user");
        entry.put("uuid", uuid);
        entry.put("message", message);
        if (ts != null) {
            entry.put("timestamp", ts);
        }
        return SessionStoreEntry.of(entry);
    }

    private static SessionStoreEntry tag(String uuid, String tagValue) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "tag");
        entry.put("uuid", uuid);
        entry.put("tag", tagValue);
        return SessionStoreEntry.of(entry);
    }

    private static SessionKey mainKey() {
        return new SessionKey("project", "abc12345-1234-1234-1234-123456789012", null);
    }

    @Test
    void foldSessionSummary_capturesFirstPrompt() {
        SessionSummaryEntry result = SessionSummary.foldSessionSummary(
                null, mainKey(),
                List.of(user("u1", "Hello world", "2026-04-27T00:00:00Z")));

        assertThat(result.data().get("first_prompt")).isEqualTo("Hello world");
        assertThat(result.data().get("first_prompt_locked")).isEqualTo(Boolean.TRUE);
        assertThat(result.data().get("created_at")).isInstanceOf(Long.class);
    }

    @Test
    void foldSessionSummary_lockedFirstPromptDoesNotChange() {
        SessionSummaryEntry first = SessionSummary.foldSessionSummary(
                null, mainKey(),
                List.of(user("u1", "First message", "2026-04-27T00:00:00Z")));
        SessionSummaryEntry second = SessionSummary.foldSessionSummary(
                first, mainKey(),
                List.of(user("u2", "Second message", "2026-04-27T00:01:00Z")));

        assertThat(second.data().get("first_prompt")).isEqualTo("First message");
    }

    @Test
    void foldSessionSummary_tagAppendOverwrites() {
        SessionSummaryEntry result = SessionSummary.foldSessionSummary(
                null, mainKey(), List.of(tag("t1", "important")));

        assertThat(result.data().get("tag")).isEqualTo("important");
    }

    @Test
    void foldSessionSummary_emptyTagClears() {
        SessionSummaryEntry seeded = SessionSummary.foldSessionSummary(
                null, mainKey(), List.of(tag("t1", "important")));
        SessionSummaryEntry cleared = SessionSummary.foldSessionSummary(
                seeded, mainKey(), List.of(tag("t2", "")));

        assertThat(cleared.data()).doesNotContainKey("tag");
    }

    @Test
    void summaryEntryToSdkInfo_returnsNullForSidechain() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("is_sidechain", Boolean.TRUE);
        data.put("first_prompt", "x");
        SessionSummaryEntry entry = new SessionSummaryEntry("sid", 100L, data);

        assertThat(SessionSummary.summaryEntryToSdkInfo(entry, null)).isNull();
    }

    @SuppressWarnings("null")
    @Test
    void summaryEntryToSdkInfo_buildsExpectedShape() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("first_prompt", "Hello");
        data.put("first_prompt_locked", Boolean.TRUE);
        data.put("custom_title", "My Title");
        data.put("git_branch", "main");
        data.put("cwd", "/home/x");
        data.put("created_at", 12345L);
        SessionSummaryEntry entry = new SessionSummaryEntry("sid-123", 99L, data);

        SDKSessionInfo info = SessionSummary.summaryEntryToSdkInfo(entry, "/home/x");
        assertThat(info).isNotNull();
        assertThat(info.sessionId()).isEqualTo("sid-123");
        assertThat(info.summary()).isEqualTo("My Title");
        assertThat(info.customTitle()).isEqualTo("My Title");
        assertThat(info.firstPrompt()).isEqualTo("Hello");
        assertThat(info.gitBranch()).isEqualTo("main");
        assertThat(info.cwd()).isEqualTo("/home/x");
        assertThat(info.createdAt()).isEqualTo(12345L);
        assertThat(info.lastModified()).isEqualTo(99L);
        assertThat(info.fileSize()).isNull();
    }

    @Test
    void summaryEntryToSdkInfo_returnsNullWhenNoExtractableSummary() {
        Map<String, Object> data = new LinkedHashMap<>();
        SessionSummaryEntry entry = new SessionSummaryEntry("sid", 0L, data);
        assertThat(SessionSummary.summaryEntryToSdkInfo(entry, null)).isNull();
    }

    @Test
    void foldSessionSummary_setOnceFieldsFreeze() {
        // cwd and created_at are set on first observation and never overwritten.
        Map<String, Object> entry1 = new LinkedHashMap<>();
        entry1.put("type", "user");
        entry1.put("uuid", "u1");
        entry1.put("cwd", "/initial/cwd");
        entry1.put("timestamp", "2026-04-27T00:00:00Z");
        entry1.put("message", Map.of("content", List.of(Map.of("type", "text", "text", "first"))));

        Map<String, Object> entry2 = new LinkedHashMap<>();
        entry2.put("type", "user");
        entry2.put("uuid", "u2");
        entry2.put("cwd", "/different/cwd");
        entry2.put("timestamp", "2026-04-27T00:01:00Z");
        entry2.put("message", Map.of("content", List.of(Map.of("type", "text", "text", "second"))));

        SessionSummaryEntry first = SessionSummary.foldSessionSummary(
                null, mainKey(), List.of(SessionStoreEntry.of(entry1)));
        SessionSummaryEntry second = SessionSummary.foldSessionSummary(
                first, mainKey(), List.of(SessionStoreEntry.of(entry2)));

        assertThat(second.data().get("cwd")).isEqualTo("/initial/cwd");
        // created_at is also frozen
        assertThat(second.data().get("created_at")).isEqualTo(first.data().get("created_at"));
    }

    @Test
    void foldSessionSummary_lastWinsForCustomTitleAiTitleEtc() {
        Map<String, Object> entry1 = new LinkedHashMap<>();
        entry1.put("type", "user");
        entry1.put("uuid", "u1");
        entry1.put("customTitle", "First Title");
        entry1.put("aiTitle", "AI v1");
        entry1.put("gitBranch", "main");
        entry1.put("timestamp", "2026-04-27T00:00:00Z");
        entry1.put("message", Map.of("content", List.of(Map.of("type", "text", "text", "first"))));

        Map<String, Object> entry2 = new LinkedHashMap<>();
        entry2.put("type", "user");
        entry2.put("uuid", "u2");
        entry2.put("customTitle", "Second Title");
        entry2.put("aiTitle", "AI v2");
        entry2.put("gitBranch", "feature");
        entry2.put("timestamp", "2026-04-27T00:01:00Z");
        entry2.put("message", Map.of("content", List.of(Map.of("type", "text", "text", "second"))));

        SessionSummaryEntry first = SessionSummary.foldSessionSummary(
                null, mainKey(), List.of(SessionStoreEntry.of(entry1)));
        SessionSummaryEntry second = SessionSummary.foldSessionSummary(
                first, mainKey(), List.of(SessionStoreEntry.of(entry2)));

        assertThat(second.data().get("custom_title")).isEqualTo("Second Title");
        assertThat(second.data().get("ai_title")).isEqualTo("AI v2");
        assertThat(second.data().get("git_branch")).isEqualTo("feature");
    }

    @Test
    void foldSessionSummary_firstPromptSkipsToolResultAndMeta() {
        // tool_result-carrying user message must NOT lock first_prompt.
        Map<String, Object> toolResult = new LinkedHashMap<>();
        toolResult.put("type", "user");
        toolResult.put("uuid", "u1");
        toolResult.put("timestamp", "2026-04-27T00:00:00Z");
        toolResult.put("message", Map.of("content",
                List.of(Map.of("type", "tool_result", "tool_use_id", "x", "content", "ok"))));

        // isMeta entries skipped
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", "user");
        meta.put("uuid", "u2");
        meta.put("isMeta", Boolean.TRUE);
        meta.put("timestamp", "2026-04-27T00:00:01Z");
        meta.put("message", Map.of("content", List.of(Map.of("type", "text", "text", "meta msg"))));

        // Real first prompt
        Map<String, Object> real = new LinkedHashMap<>();
        real.put("type", "user");
        real.put("uuid", "u3");
        real.put("timestamp", "2026-04-27T00:00:02Z");
        real.put("message", Map.of("content", List.of(Map.of("type", "text", "text", "the real prompt"))));

        SessionSummaryEntry result = SessionSummary.foldSessionSummary(null, mainKey(), List.of(
                SessionStoreEntry.of(toolResult),
                SessionStoreEntry.of(meta),
                SessionStoreEntry.of(real)));

        assertThat(result.data().get("first_prompt")).isEqualTo("the real prompt");
        assertThat(result.data().get("first_prompt_locked")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void foldSessionSummary_firstPromptCommandFallbackForSlashCommand() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "user");
        entry.put("uuid", "u1");
        entry.put("timestamp", "2026-04-27T00:00:00Z");
        entry.put("message", Map.of("content",
                List.of(Map.of("type", "text", "text", "<command-name>git</command-name> <args>"))));

        SessionSummaryEntry result = SessionSummary.foldSessionSummary(
                null, mainKey(), List.of(SessionStoreEntry.of(entry)));
        // Slash commands populate command_fallback, NOT first_prompt
        assertThat(result.data()).doesNotContainKey("first_prompt_locked");
        assertThat(result.data().get("command_fallback")).isEqualTo("git");
    }

    @Test
    void foldSessionSummary_firstPromptTruncatedAt200Chars() {
        StringBuilder long_ = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            long_.append('a');
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "user");
        entry.put("uuid", "u1");
        entry.put("timestamp", "2026-04-27T00:00:00Z");
        entry.put("message", Map.of("content",
                List.of(Map.of("type", "text", "text", long_.toString()))));

        SessionSummaryEntry result = SessionSummary.foldSessionSummary(
                null, mainKey(), List.of(SessionStoreEntry.of(entry)));
        Object firstPrompt = result.data().get("first_prompt");
        assertThat(firstPrompt).isInstanceOf(String.class);
        assertThat((String) firstPrompt).hasSize(201); // 200 chars + ellipsis
        assertThat((String) firstPrompt).endsWith("…");
    }

    @Test
    void foldSessionSummary_doesNotMutatePrev() {
        SessionSummaryEntry first = SessionSummary.foldSessionSummary(
                null, mainKey(),
                List.of(user("u1", "First message", "2026-04-27T00:00:00Z")));
        Map<String, Object> originalData = new LinkedHashMap<>(first.data());

        SessionSummary.foldSessionSummary(first, mainKey(),
                List.of(user("u2", "Second message", "2026-04-27T00:01:00Z")));

        // Prev's data should be untouched
        assertThat(first.data()).isEqualTo(originalData);
    }

    @SuppressWarnings("null")
    @Test
    void summaryEntryToSdkInfo_summaryPrecedenceChain() {
        // custom_title > ai_title > last_prompt > summary_hint > first_prompt
        Map<String, Object> withCustom = new LinkedHashMap<>();
        withCustom.put("custom_title", "Custom");
        withCustom.put("ai_title", "AI");
        withCustom.put("last_prompt", "Last");
        withCustom.put("first_prompt", "First");
        withCustom.put("first_prompt_locked", Boolean.TRUE);
        SDKSessionInfo i1 = SessionSummary.summaryEntryToSdkInfo(
                new SessionSummaryEntry("sid", 1L, withCustom), null);
        assertThat(i1).isNotNull();
        assertThat(i1.summary()).isEqualTo("Custom");

        Map<String, Object> withoutCustom = new LinkedHashMap<>();
        withoutCustom.put("ai_title", "AI");
        withoutCustom.put("last_prompt", "Last");
        SDKSessionInfo i2 = SessionSummary.summaryEntryToSdkInfo(
                new SessionSummaryEntry("sid", 1L, withoutCustom), null);
        assertThat(i2).isNotNull();
        assertThat(i2.summary()).isEqualTo("AI");

        Map<String, Object> withoutTitles = new LinkedHashMap<>();
        withoutTitles.put("last_prompt", "Last");
        withoutTitles.put("summary_hint", "Hint");
        SDKSessionInfo i3 = SessionSummary.summaryEntryToSdkInfo(
                new SessionSummaryEntry("sid", 1L, withoutTitles), null);
        assertThat(i3).isNotNull();
        assertThat(i3.summary()).isEqualTo("Last");

        Map<String, Object> hintOnly = new LinkedHashMap<>();
        hintOnly.put("summary_hint", "Hint");
        hintOnly.put("first_prompt", "First");
        hintOnly.put("first_prompt_locked", Boolean.TRUE);
        SDKSessionInfo i4 = SessionSummary.summaryEntryToSdkInfo(
                new SessionSummaryEntry("sid", 1L, hintOnly), null);
        assertThat(i4).isNotNull();
        assertThat(i4.summary()).isEqualTo("Hint");

        Map<String, Object> firstOnly = new LinkedHashMap<>();
        firstOnly.put("first_prompt", "First");
        firstOnly.put("first_prompt_locked", Boolean.TRUE);
        SDKSessionInfo i5 = SessionSummary.summaryEntryToSdkInfo(
                new SessionSummaryEntry("sid", 1L, firstOnly), null);
        assertThat(i5).isNotNull();
        assertThat(i5.summary()).isEqualTo("First");
    }

}
