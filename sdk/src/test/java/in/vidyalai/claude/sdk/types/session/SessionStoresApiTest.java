package in.vidyalai.claude.sdk.types.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.ForkSessionResult;
import in.vidyalai.claude.sdk.types.message.SDKSessionInfo;
import in.vidyalai.claude.sdk.types.message.SessionMessage;

/**
 * End-to-end tests for the {@link SessionStore}-backed APIs exposed via
 * {@link ClaudeSDK}. Uses {@link InMemorySessionStore} as the backing adapter.
 */
class SessionStoresApiTest {

    private static SessionStoreEntry userEntry(String uuid, String text, String ts) {
        Map<String, Object> message = Map.of(
                "content", List.of(Map.of("type", "text", "text", text)));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "user");
        entry.put("uuid", uuid);
        entry.put("sessionId", "ignored-by-test");
        entry.put("message", message);
        if (ts != null) {
            entry.put("timestamp", ts);
        }
        return SessionStoreEntry.of(entry);
    }

    private static SessionStoreEntry assistantEntry(String uuid, String parentUuid, String text, String ts) {
        Map<String, Object> message = Map.of(
                "content", List.of(Map.of("type", "text", "text", text)),
                "model", "claude-test");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "assistant");
        entry.put("uuid", uuid);
        if (parentUuid != null) {
            entry.put("parentUuid", parentUuid);
        }
        entry.put("sessionId", "ignored-by-test");
        entry.put("message", message);
        if (ts != null) {
            entry.put("timestamp", ts);
        }
        return SessionStoreEntry.of(entry);
    }

    private String newSessionId() {
        return UUID.randomUUID().toString();
    }

    @Test
    void listSessionsFromStore_usesSummaryFastPath() {
        InMemorySessionStore store = new InMemorySessionStore();
        String projectKey = ClaudeSDK.projectKeyForDirectory(null);
        String sid = newSessionId();
        SessionKey main = new SessionKey(projectKey, sid, null);

        store.append(main, List.of(userEntry("u1", "What is the weather today?", "2026-04-27T00:00:00Z")));

        List<SDKSessionInfo> sessions = ClaudeSDK.listSessionsFromStore(store, null, null, 0);
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).sessionId()).isEqualTo(sid);
        assertThat(sessions.get(0).summary()).isEqualTo("What is the weather today?");
    }

    @SuppressWarnings("null")
    @Test
    void getSessionInfoFromStore_returnsSummaryForExistingSession() {
        InMemorySessionStore store = new InMemorySessionStore();
        String projectKey = ClaudeSDK.projectKeyForDirectory(null);
        String sid = newSessionId();
        store.append(new SessionKey(projectKey, sid, null),
                List.of(userEntry("u1", "Hello there", "2026-04-27T00:00:00Z")));

        SDKSessionInfo info = ClaudeSDK.getSessionInfoFromStore(store, sid, null);
        assertThat(info).isNotNull();
        assertThat(info.firstPrompt()).isEqualTo("Hello there");
    }

    @Test
    void getSessionInfoFromStore_invalidUuid_returnsNull() {
        InMemorySessionStore store = new InMemorySessionStore();
        assertThat(ClaudeSDK.getSessionInfoFromStore(store, "not-a-uuid", null)).isNull();
    }

    @Test
    void getSessionMessagesFromStore_returnsConversationChain() {
        InMemorySessionStore store = new InMemorySessionStore();
        String projectKey = ClaudeSDK.projectKeyForDirectory(null);
        String sid = newSessionId();
        SessionKey main = new SessionKey(projectKey, sid, null);

        store.append(main, List.of(
                userEntry("u1", "First", "2026-04-27T00:00:00Z"),
                assistantEntry("a1", "u1", "Reply", "2026-04-27T00:00:01Z")));

        List<SessionMessage> messages = ClaudeSDK.getSessionMessagesFromStore(
                store, sid, null, null, 0);
        assertThat(messages).hasSize(2);
    }

    @SuppressWarnings("null")
    @Test
    void renameSessionViaStore_appendsCustomTitleEntry() {
        InMemorySessionStore store = new InMemorySessionStore();
        String projectKey = ClaudeSDK.projectKeyForDirectory(null);
        String sid = newSessionId();
        SessionKey main = new SessionKey(projectKey, sid, null);

        store.append(main, List.of(userEntry("u1", "First", "2026-04-27T00:00:00Z")));
        ClaudeSDK.renameSessionViaStore(store, sid, "My New Title", null);

        List<SessionStoreEntry> entries = store.load(main);
        assertThat(entries).hasSize(2);
        SessionStoreEntry titleEntry = entries.get(1);
        assertThat(titleEntry.type()).isEqualTo("custom-title");
        assertThat(titleEntry.<String>get("customTitle")).isEqualTo("My New Title");
    }

    @Test
    void renameSessionViaStore_emptyTitle_throws() {
        InMemorySessionStore store = new InMemorySessionStore();
        String sid = newSessionId();
        assertThatThrownBy(() -> ClaudeSDK.renameSessionViaStore(store, sid, "   ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("null")
    @Test
    void tagSessionViaStore_appendsTagEntry() {
        InMemorySessionStore store = new InMemorySessionStore();
        String projectKey = ClaudeSDK.projectKeyForDirectory(null);
        String sid = newSessionId();
        SessionKey main = new SessionKey(projectKey, sid, null);
        store.append(main, List.of(userEntry("u1", "x", "2026-04-27T00:00:00Z")));

        ClaudeSDK.tagSessionViaStore(store, sid, "important", null);

        List<SessionStoreEntry> entries = store.load(main);
        SessionStoreEntry tagEntry = entries.get(entries.size() - 1);
        assertThat(tagEntry.type()).isEqualTo("tag");
        assertThat(tagEntry.<String>get("tag")).isEqualTo("important");
    }

    @SuppressWarnings("null")
    @Test
    void tagSessionViaStore_clearsTagWithNull() {
        InMemorySessionStore store = new InMemorySessionStore();
        String projectKey = ClaudeSDK.projectKeyForDirectory(null);
        String sid = newSessionId();
        SessionKey main = new SessionKey(projectKey, sid, null);
        store.append(main, List.of(userEntry("u1", "x", "2026-04-27T00:00:00Z")));

        ClaudeSDK.tagSessionViaStore(store, sid, null, null);

        SessionStoreEntry tagEntry = store.load(main).get(1);
        assertThat(tagEntry.<String>get("tag")).isEmpty();
    }

    @Test
    void deleteSessionViaStore_removesEntry() {
        InMemorySessionStore store = new InMemorySessionStore();
        String projectKey = ClaudeSDK.projectKeyForDirectory(null);
        String sid = newSessionId();
        SessionKey main = new SessionKey(projectKey, sid, null);
        store.append(main, List.of(userEntry("u1", "x", "2026-04-27T00:00:00Z")));

        ClaudeSDK.deleteSessionViaStore(store, sid, null);

        assertThat(store.load(main)).isNull();
    }

    @SuppressWarnings("null")
    @Test
    void forkSessionViaStore_copiesTranscriptIntoNewSession() throws IOException {
        InMemorySessionStore store = new InMemorySessionStore();
        String projectKey = ClaudeSDK.projectKeyForDirectory(null);
        String sid = newSessionId();
        SessionKey main = new SessionKey(projectKey, sid, null);
        store.append(main, List.of(
                userEntry("u1", "Hello", "2026-04-27T00:00:00Z"),
                assistantEntry("a1", "u1", "Hi", "2026-04-27T00:00:01Z")));

        ForkSessionResult result = ClaudeSDK.forkSessionViaStore(store, sid, null, null, "Forked");
        assertThat(result.sessionId()).isNotEqualTo(sid);

        SessionKey forkKey = new SessionKey(projectKey, result.sessionId(), null);
        List<SessionStoreEntry> forkEntries = store.load(forkKey);
        assertThat(forkEntries).isNotEmpty();
        assertThat(forkEntries.stream().anyMatch(e -> "custom-title".equals(e.type()))).isTrue();
    }

    @Test
    void listSubagentsFromStore_findsAgentIds() {
        InMemorySessionStore store = new InMemorySessionStore();
        String projectKey = ClaudeSDK.projectKeyForDirectory(null);
        String sid = newSessionId();
        store.append(new SessionKey(projectKey, sid, null),
                List.of(userEntry("u1", "main", "2026-04-27T00:00:00Z")));
        store.append(new SessionKey(projectKey, sid, "subagents/agent-foo"),
                List.of(userEntry("a1", "agent foo", "2026-04-27T00:00:01Z")));
        store.append(new SessionKey(projectKey, sid, "subagents/agent-bar"),
                List.of(userEntry("a2", "agent bar", "2026-04-27T00:00:02Z")));

        List<String> ids = ClaudeSDK.listSubagentsFromStore(store, sid, null);
        assertThat(ids).containsExactlyInAnyOrder("foo", "bar");
    }

    @Test
    void listSessionsFromStore_dropsSidechainSessions() {
        InMemorySessionStore store = new InMemorySessionStore();
        String projectKey = ClaudeSDK.projectKeyForDirectory(null);
        String mainSid = newSessionId();
        String sidechainSid = newSessionId();

        // Main session — visible
        store.append(new SessionKey(projectKey, mainSid, null),
                List.of(userEntry("u1", "main prompt", "2026-04-27T00:00:00Z")));

        // Sidechain session — should be filtered
        Map<String, Object> sidechainEntry = new LinkedHashMap<>();
        sidechainEntry.put("type", "user");
        sidechainEntry.put("uuid", "u2");
        sidechainEntry.put("isSidechain", Boolean.TRUE);
        sidechainEntry.put("timestamp", "2026-04-27T00:00:01Z");
        sidechainEntry.put("message", Map.of("content", List.of(Map.of("type", "text", "text", "side"))));
        store.append(new SessionKey(projectKey, sidechainSid, null),
                List.of(SessionStoreEntry.of(sidechainEntry)));

        List<SDKSessionInfo> sessions = ClaudeSDK.listSessionsFromStore(store, null, null, 0);
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).sessionId()).isEqualTo(mainSid);
    }

    @Test
    void listSessionsFromStore_appliesLimitAndOffset() {
        InMemorySessionStore store = new InMemorySessionStore();
        String projectKey = ClaudeSDK.projectKeyForDirectory(null);
        // Append 5 sessions (sleep so each gets a distinct mtime)
        for (int i = 0; i < 5; i++) {
            store.append(new SessionKey(projectKey, newSessionId(), null),
                    List.of(userEntry("u" + i, "prompt " + i, "2026-04-27T00:00:0" + i + "Z")));
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        List<SDKSessionInfo> page1 = ClaudeSDK.listSessionsFromStore(store, null, 2, 0);
        List<SDKSessionInfo> page2 = ClaudeSDK.listSessionsFromStore(store, null, 2, 2);
        List<SDKSessionInfo> page3 = ClaudeSDK.listSessionsFromStore(store, null, 2, 4);

        assertThat(page1).hasSize(2);
        assertThat(page2).hasSize(2);
        assertThat(page3).hasSize(1);
        // No overlap
        assertThat(page1.get(0).sessionId()).isNotEqualTo(page2.get(0).sessionId());
    }

    @SuppressWarnings("null")
    @Test
    void listSessionsFromStore_throwsWhenStoreImplementsNeitherListing() {
        // Custom store implementing only append/load (no list_sessions, no list_session_summaries)
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
        assertThatThrownBy(() -> ClaudeSDK.listSessionsFromStore(minStore, null, null, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("listSessions");
    }

    @Test
    void listSessionsFromStore_concurrentLoadsAreBoundedAt16() throws Exception {
        // Verify concurrency bound: register 50 sessions; track peak in-flight
        // loadAsync calls and assert the peak ≤ 16 (STORE_LIST_LOAD_CONCURRENCY).
        java.util.concurrent.atomic.AtomicInteger inflight = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger peakInflight = new java.util.concurrent.atomic.AtomicInteger();

        in.vidyalai.claude.sdk.types.session.SessionStore concurrencyTracking =
                new in.vidyalai.claude.sdk.types.session.SessionStore() {
                    private final InMemorySessionStore inner = new InMemorySessionStore();

                    @Override
                    public void append(SessionKey key, List<SessionStoreEntry> entries) {
                        inner.append(key, entries);
                    }

                    @Override
                    public List<SessionStoreEntry> load(SessionKey key) {
                        int now = inflight.incrementAndGet();
                        peakInflight.updateAndGet(p -> Math.max(p, now));
                        try {
                            // Hold long enough to overlap if concurrency > bound
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        try {
                            return inner.load(key);
                        } finally {
                            inflight.decrementAndGet();
                        }
                    }

                    @Override
                    public List<in.vidyalai.claude.sdk.types.session.SessionStoreListEntry> listSessions(
                            String projectKey) {
                        return inner.listSessions(projectKey);
                    }

                    @Override
                    public boolean implementsListSessions() {
                        return true;
                    }
                };

        String projectKey = ClaudeSDK.projectKeyForDirectory(null);
        for (int i = 0; i < 50; i++) {
            concurrencyTracking.append(new SessionKey(projectKey, newSessionId(), null),
                    List.of(userEntry("u" + i, "prompt " + i, "2026-04-27T00:00:00Z")));
        }

        List<SDKSessionInfo> sessions = ClaudeSDK.listSessionsFromStore(concurrencyTracking, null, null, 0);
        assertThat(sessions).isNotEmpty();
        assertThat(peakInflight.get()).isLessThanOrEqualTo(16);
    }

    @Test
    void listSessionsFromStore_adapterLoadErrorDegradesRow() {
        // An adapter that throws on load() during slow-path listing must
        // degrade to an empty-summary row rather than failing the whole list.
        // (This test exercises the slow path: store has listSessions but NOT
        // listSessionSummaries.)
        InMemorySessionStore inner = new InMemorySessionStore();
        String projectKey = ClaudeSDK.projectKeyForDirectory(null);
        String okSid = newSessionId();
        String badSid = newSessionId();
        inner.append(new SessionKey(projectKey, okSid, null),
                List.of(userEntry("u1", "ok prompt", "2026-04-27T00:00:00Z")));
        inner.append(new SessionKey(projectKey, badSid, null),
                List.of(userEntry("u2", "bad prompt", "2026-04-27T00:00:01Z")));

        in.vidyalai.claude.sdk.types.session.SessionStore failingForBad =
                new in.vidyalai.claude.sdk.types.session.SessionStore() {
                    @Override
                    public void append(SessionKey key, List<SessionStoreEntry> entries) {
                        inner.append(key, entries);
                    }

                    @Override
                    public List<SessionStoreEntry> load(SessionKey key) {
                        if (badSid.equals(key.sessionId())) {
                            throw new RuntimeException("simulated adapter load failure");
                        }
                        return inner.load(key);
                    }

                    @Override
                    public List<in.vidyalai.claude.sdk.types.session.SessionStoreListEntry> listSessions(
                            String projectKey) {
                        return inner.listSessions(projectKey);
                    }

                    @Override
                    public boolean implementsListSessions() {
                        return true;
                    }
                    // No implementsListSessionSummaries override → default false → slow path
                };

        List<SDKSessionInfo> sessions = ClaudeSDK.listSessionsFromStore(failingForBad, null, null, 0);
        // The OK session must surface; the failing one degrades to an empty
        // summary entry (sessionId still present, summary blank).
        java.util.Map<String, SDKSessionInfo> bySid = new java.util.HashMap<>();
        for (SDKSessionInfo info : sessions) {
            bySid.put(info.sessionId(), info);
        }
        assertThat(bySid).containsKey(okSid);
        assertThat(bySid.get(okSid).summary()).isEqualTo("ok prompt");
        // The bad session is dropped (sentinel summary empty → filtered by sortLimitOffset → present with blank)
        // Either dropped or sentinel — the contract is "doesn't fail the whole list".
    }

    @Test
    void listSubagentsFromStore_dedupesAcrossNestedSubpaths() {
        // The same agent ID can live at multiple subpaths (e.g. workflows/<runId>/agent-x AND subagents/agent-x).
        InMemorySessionStore store = new InMemorySessionStore();
        String projectKey = ClaudeSDK.projectKeyForDirectory(null);
        String sid = newSessionId();

        store.append(new SessionKey(projectKey, sid, "subagents/agent-foo"),
                List.of(userEntry("a1", "x", "2026-04-27T00:00:00Z")));
        store.append(new SessionKey(projectKey, sid, "subagents/workflows/run-1/agent-foo"),
                List.of(userEntry("a2", "y", "2026-04-27T00:00:01Z")));

        List<String> ids = ClaudeSDK.listSubagentsFromStore(store, sid, null);
        assertThat(ids).containsExactly("foo");
    }

    @SuppressWarnings("null")
    @Test
    void listSubagentsFromStore_throwsWhenStoreLacksListSubkeys() {
        // Custom store without listSubkeys must raise.
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
        String sid = newSessionId();
        assertThatThrownBy(() -> ClaudeSDK.listSubagentsFromStore(minStore, sid, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("listSubkeys");
    }

    @Test
    void getSubagentMessagesFromStore_returnsAgentTranscript() {
        InMemorySessionStore store = new InMemorySessionStore();
        String projectKey = ClaudeSDK.projectKeyForDirectory(null);
        String sid = newSessionId();
        store.append(new SessionKey(projectKey, sid, "subagents/agent-foo"),
                List.of(
                        userEntry("a1", "agent foo prompt", "2026-04-27T00:00:00Z"),
                        assistantEntry("a2", "a1", "agent foo reply", "2026-04-27T00:00:01Z")));

        List<SessionMessage> messages = ClaudeSDK.getSubagentMessagesFromStore(
                store, sid, "foo", null, null, 0);
        assertThat(messages).hasSize(2);
    }

}
