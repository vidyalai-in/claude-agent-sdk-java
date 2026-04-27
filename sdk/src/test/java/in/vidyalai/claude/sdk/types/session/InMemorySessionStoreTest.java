package in.vidyalai.claude.sdk.types.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class InMemorySessionStoreTest {

    private static SessionStoreEntry entry(String type, String uuid, Map<String, Object> extras) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", type);
        if (uuid != null) {
            data.put("uuid", uuid);
        }
        data.putAll(extras);
        return SessionStoreEntry.of(data);
    }

    private static SessionStoreEntry userMessage(String uuid, String text) {
        Map<String, Object> message = Map.of(
                "content", List.of(Map.of("type", "text", "text", text)));
        return entry("user", uuid, Map.of(
                "message", message,
                "timestamp", "2026-04-27T00:00:00Z"));
    }

    @Test
    void appendAndLoad_mainTranscript_roundTrips() {
        InMemorySessionStore store = new InMemorySessionStore();
        SessionKey key = new SessionKey("project1", "abc12345-1234-1234-1234-123456789012", null);

        SessionStoreEntry e1 = userMessage("u1", "hello");
        SessionStoreEntry e2 = userMessage("u2", "world");
        store.append(key, List.of(e1, e2));

        List<SessionStoreEntry> loaded = store.load(key);
        assertThat(loaded).containsExactly(e1, e2);
    }

    @Test
    void load_unknownKey_returnsNull() {
        InMemorySessionStore store = new InMemorySessionStore();
        SessionKey key = new SessionKey("project1", "abc12345-1234-1234-1234-123456789012", null);
        assertThat(store.load(key)).isNull();
    }

    @Test
    void listSessions_returnsMainTranscriptsOnly() {
        InMemorySessionStore store = new InMemorySessionStore();
        SessionKey main = new SessionKey("p1", "abc12345-1234-1234-1234-123456789012", null);
        SessionKey sub = new SessionKey("p1", "abc12345-1234-1234-1234-123456789012", "subagents/agent-x");

        store.append(main, List.of(userMessage("u1", "hi")));
        store.append(sub, List.of(userMessage("s1", "agent-msg")));

        List<SessionStoreListEntry> list = store.listSessions("p1");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).sessionId()).isEqualTo("abc12345-1234-1234-1234-123456789012");
        assertThat(list.get(0).mtime()).isPositive();
    }

    @Test
    void listSubkeys_returnsSubpaths() {
        InMemorySessionStore store = new InMemorySessionStore();
        String sid = "abc12345-1234-1234-1234-123456789012";
        store.append(new SessionKey("p1", sid, null), List.of(userMessage("u1", "main")));
        store.append(new SessionKey("p1", sid, "subagents/agent-x"), List.of(userMessage("a1", "x")));
        store.append(new SessionKey("p1", sid, "subagents/agent-y"), List.of(userMessage("a2", "y")));

        List<String> subs = store.listSubkeys(new SessionListSubkeysKey("p1", sid));
        assertThat(subs).containsExactlyInAnyOrder("subagents/agent-x", "subagents/agent-y");
    }

    @Test
    void delete_mainKey_cascadesToSubkeys() {
        InMemorySessionStore store = new InMemorySessionStore();
        String sid = "abc12345-1234-1234-1234-123456789012";
        SessionKey main = new SessionKey("p1", sid, null);
        SessionKey sub = new SessionKey("p1", sid, "subagents/agent-x");

        store.append(main, List.of(userMessage("u1", "hi")));
        store.append(sub, List.of(userMessage("s1", "agent")));

        store.delete(main);

        assertThat(store.load(main)).isNull();
        assertThat(store.load(sub)).isNull();
        assertThat(store.listSubkeys(new SessionListSubkeysKey("p1", sid))).isEmpty();
    }

    @Test
    void delete_subpathKey_doesNotCascade() {
        InMemorySessionStore store = new InMemorySessionStore();
        String sid = "abc12345-1234-1234-1234-123456789012";
        SessionKey main = new SessionKey("p1", sid, null);
        SessionKey subA = new SessionKey("p1", sid, "subagents/agent-a");
        SessionKey subB = new SessionKey("p1", sid, "subagents/agent-b");

        store.append(main, List.of(userMessage("u1", "hi")));
        store.append(subA, List.of(userMessage("a1", "a")));
        store.append(subB, List.of(userMessage("b1", "b")));

        store.delete(subA);

        assertThat(store.load(main)).hasSize(1);
        assertThat(store.load(subA)).isNull();
        assertThat(store.load(subB)).hasSize(1);
    }

    @Test
    void listSessionSummaries_excludesSubpathEntries() {
        InMemorySessionStore store = new InMemorySessionStore();
        String sid = "abc12345-1234-1234-1234-123456789012";
        SessionKey main = new SessionKey("p1", sid, null);
        SessionKey sub = new SessionKey("p1", sid, "subagents/agent-x");

        store.append(main, List.of(userMessage("u1", "main prompt")));
        store.append(sub, List.of(userMessage("s1", "agent prompt")));

        List<SessionSummaryEntry> summaries = store.listSessionSummaries("p1");
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).sessionId()).isEqualTo(sid);
    }

    @Test
    void appendDoesNotMutateInputEntries() {
        InMemorySessionStore store = new InMemorySessionStore();
        SessionKey key = new SessionKey("p1", "abc12345-1234-1234-1234-123456789012", null);
        SessionStoreEntry entry = userMessage("u1", "hi");
        Map<String, Object> originalSnapshot = entry.asMap();

        store.append(key, List.of(entry));

        assertThat(entry.asMap()).isEqualTo(originalSnapshot);
    }

    @Test
    void implementsFlags_areTrue() {
        InMemorySessionStore store = new InMemorySessionStore();
        assertThat(store.implementsListSessions()).isTrue();
        assertThat(store.implementsListSessionSummaries()).isTrue();
        assertThat(store.implementsDelete()).isTrue();
        assertThat(store.implementsListSubkeys()).isTrue();
    }

    @Test
    void sessionStoreEntry_requiresType() {
        Map<String, Object> noType = new LinkedHashMap<>();
        noType.put("uuid", "abc");
        assertThatThrownBy(() -> SessionStoreEntry.of(noType))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
