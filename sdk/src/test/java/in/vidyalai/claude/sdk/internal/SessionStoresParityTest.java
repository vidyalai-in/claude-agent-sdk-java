package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.types.message.ForkSessionResult;
import in.vidyalai.claude.sdk.types.message.SDKSessionInfo;
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;
import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionStore;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;
import in.vidyalai.claude.sdk.types.session.SessionStoreListEntry;
import in.vidyalai.claude.sdk.types.session.SessionSummary;
import in.vidyalai.claude.sdk.types.session.SessionSummaryEntry;

/**
 * Python-parity behavior of the {@link SessionStore}-backed session APIs:
 * tag sanitizing, fork titles, session info via the lite parser, adapter
 * error propagation, untyped entries, and the summary fold.
 */
class SessionStoresParityTest {

    private static final String PROJECT_KEY = SessionStores.projectKeyForDirectory(null);

    private static SessionStoreEntry entry(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return SessionStoreEntry.of(m);
    }

    private static SessionStoreEntry user(String uuid, String text, @Nullable String ts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "user");
        m.put("uuid", uuid);
        if (ts != null) {
            m.put("timestamp", ts);
        }
        m.put("message", Map.of("role", "user", "content", text));
        return SessionStoreEntry.of(m);
    }

    private static String newSession(SessionStore store, SessionStoreEntry... entries) {
        String sid = UUID.randomUUID().toString();
        store.append(new SessionKey(PROJECT_KEY, sid, null), List.of(entries));
        return sid;
    }

    // ---------------------------------------------------------------- #25

    @SuppressWarnings("null")
    @Test
    void tagViaStoreUsesTheDiskSanitizer() {
        // U+FB01 (fi ligature) only folds under NFKC; U+00AD (soft hyphen, Cf)
        // and U+E000 (private use, Co) are outside the old explicit ranges.
        String raw = "\uFB01le\u00AD\uE000-tag";
        InMemorySessionStore store = new InMemorySessionStore();
        String sid = newSession(store, user("u1", "hi", "2026-01-01T00:00:00Z"));

        SessionStores.tagSessionViaStore(store, sid, raw, null);

        List<SessionStoreEntry> loaded = store.load(new SessionKey(PROJECT_KEY, sid, null));
        Object tag = loaded.get(loaded.size() - 1).asMap().get("tag");
        assertThat(tag).isEqualTo("file-tag").isEqualTo(SessionMutations.sanitizeUnicode(raw).strip());
    }

    // ---------------------------------------------------------------- #26

    @Test
    void forkTitleFallsBackToTheFirstPrompt() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        String sid = newSession(store, user(UUID.randomUUID().toString(), "Plan the migration",
                "2026-01-01T00:00:00Z"));

        ForkSessionResult fork = SessionStores.forkSessionViaStore(store, sid, null, null, null);

        List<SessionStoreEntry> forked = store.load(new SessionKey(PROJECT_KEY, fork.sessionId(), null));
        assertThat(forked).anySatisfy(e -> assertThat(e.asMap())
                .containsEntry("type", "custom-title")
                .containsEntry("customTitle", "Plan the migration (fork)"));
    }

    @Test
    void forkViaStoreTreatsAnEmptyCutoffAsNone() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        String first = UUID.randomUUID().toString();
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("type", "user");
        second.put("uuid", UUID.randomUUID().toString());
        second.put("parentUuid", first);
        second.put("timestamp", "2026-01-01T00:00:01Z");
        second.put("message", Map.of("role", "user", "content", "two"));
        String sid = newSession(store, user(first, "one", "2026-01-01T00:00:00Z"), SessionStoreEntry.of(second));

        ForkSessionResult full = SessionStores.forkSessionViaStore(store, sid, null, "", null);
        ForkSessionResult cut = SessionStores.forkSessionViaStore(store, sid, null, first, null);

        // "" forks everything, like no cutoff; a real cutoff stops at it.
        assertThat(userTexts(store, full.sessionId())).containsExactly("one", "two");
        assertThat(userTexts(store, cut.sessionId())).containsExactly("one");
    }

    private static List<Object> userTexts(InMemorySessionStore store, String sessionId) {
        List<Object> texts = new java.util.ArrayList<>();
        for (SessionStoreEntry e : java.util.Objects.requireNonNull(
                store.load(new SessionKey(PROJECT_KEY, sessionId, null)))) {
            if ("user".equals(e.asMap().get("type")) && (e.asMap().get("message") instanceof Map<?, ?> m)) {
                texts.add(m.get("content"));
            }
        }
        return texts;
    }

    @Test
    void projectKeyResolvesSymlinksInTheExistingPrefixOfAMissingPath() throws Exception {
        java.nio.file.Path base = java.nio.file.Files.createTempDirectory("pk").toRealPath();
        java.nio.file.Path real = java.nio.file.Files.createDirectory(base.resolve("real"));
        java.nio.file.Path link = java.nio.file.Files.createSymbolicLink(base.resolve("link"), real);
        try {
            // "link/missing" does not exist; realpath still resolves "link".
            String key = SessionStores.projectKeyForDirectory(link.resolve("missing").toString());
            String expected = Sessions.sanitizePath(real.resolve("missing").toString());
            assertThat(key).isEqualTo(expected);
            assertThat(Sessions.sanitizePath(Sessions.canonicalizePath(link + "/missing"))).isEqualTo(expected);
        } finally {
            java.nio.file.Files.delete(link);
            java.nio.file.Files.delete(real);
            java.nio.file.Files.delete(base);
        }
    }

    @Test
    void deriveTitlePrefersCustomThenAiThenFirstPrompt() {
        Map<String, Object> prompt = new LinkedHashMap<>(user("u1", "first ask", null).asMap());
        Map<String, Object> ai = Map.of("type", "ai-title", "aiTitle", "AI title");
        Map<String, Object> custom = Map.of("type", "custom-title", "customTitle", "Mine");
        assertThat(SessionStores.deriveTitleFromEntries(List.of(prompt))).isEqualTo("first ask");
        assertThat(SessionStores.deriveTitleFromEntries(List.of(prompt, ai))).isEqualTo("AI title");
        assertThat(SessionStores.deriveTitleFromEntries(List.of(prompt, custom, ai))).isEqualTo("Mine");
        assertThat(SessionStores.deriveTitleFromEntries(List.of(Map.of("type", "x")))).isNull();
    }

    // ---------------------------------------------------------------- #27

    @SuppressWarnings("null")
    @Test
    void getSessionInfoPropagatesAdapterLoadFailure() {
        SessionStore failing = new SessionStore() {
            @Override
            public void append(SessionKey key, List<SessionStoreEntry> entries) {
            }

            @Override
            public @Nullable List<SessionStoreEntry> load(SessionKey key) {
                throw new IllegalStateException("backend down");
            }
        };
        assertThatThrownBy(() -> SessionStores.getSessionInfoFromStore(
                failing, UUID.randomUUID().toString(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("backend down");
    }

    // ---------------------------------------------------- session info via lite

    @SuppressWarnings("null")
    @Test
    void sessionInfoHasTheJsonlByteSizeAsFileSize() {
        InMemorySessionStore store = new InMemorySessionStore();
        SessionStoreEntry e1 = user("u1", "héllo wörld", "2026-01-01T00:00:00Z");
        String sid = newSession(store, e1);

        SDKSessionInfo info = SessionStores.getSessionInfoFromStore(store, sid, null);

        assertThat(info).isNotNull();
        String jsonl = SessionStores.entriesToJsonl(List.of(e1));
        assertThat(jsonl).startsWith("{\"type\":\"user\"").containsIgnoringCase("h\\u00e9llo").endsWith("}\n");
        assertThat(info.fileSize()).isEqualTo((long) jsonl.getBytes(StandardCharsets.UTF_8).length);
        assertThat(info.firstPrompt()).isEqualTo("héllo wörld");
    }

    @SuppressWarnings("null")
    @Test
    void sessionInfoReadsFirstPromptAndCreatedAtFromTheFirst64Kb() {
        InMemorySessionStore store = new InMemorySessionStore();
        // A first line bigger than the 64 KB head pushes the prompt out of it,
        // exactly as on disk; a tail title keeps the session listable.
        SessionStoreEntry big = entry("type", "attachment", "uuid", "a0", "blob", "x".repeat(70_000));
        String sid = newSession(store, big,
                user("u1", "late prompt", "2026-01-01T00:00:00Z"),
                entry("type", "custom-title", "customTitle", "Titled"));

        SDKSessionInfo info = SessionStores.getSessionInfoFromStore(store, sid, null);

        assertThat(info).isNotNull();
        assertThat(info.summary()).isEqualTo("Titled");
        assertThat(info.firstPrompt()).isNull();
        assertThat(info.createdAt()).isNull();
    }

    @SuppressWarnings("null")
    @Test
    void sessionInfoLastModifiedComesFromTheLastLineOnly() {
        InMemorySessionStore store = new InMemorySessionStore();
        String sid = newSession(store,
                user("u1", "hello", "2026-01-01T00:00:00Z"),
                user("u2", "again", "2026-01-02T00:00:00Z"));
        SDKSessionInfo info = SessionStores.getSessionInfoFromStore(store, sid, null);
        assertThat(info.lastModified()).isEqualTo(java.time.Instant.parse("2026-01-02T00:00:00Z").toEpochMilli());

        // Last line without a timestamp: now, not an earlier line's timestamp.
        String sid2 = newSession(store,
                user("u1", "hello", "2026-01-01T00:00:00Z"),
                entry("type", "custom-title", "customTitle", "T"));
        long before = System.currentTimeMillis();
        SDKSessionInfo info2 = SessionStores.getSessionInfoFromStore(store, sid2, null);
        assertThat(info2.lastModified()).isGreaterThanOrEqualTo(before);
    }

    @Test
    void entriesToJsonlHoistsTypeToTheFront() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", "v1");
        m.put("sessionId", "s");
        m.put("type", "tag");
        assertThat(SessionStores.entriesToJsonl(List.of(SessionStoreEntry.of(m))))
                .isEqualTo("{\"type\":\"tag\",\"tag\":\"v1\",\"sessionId\":\"s\"}\n");
    }

    @SuppressWarnings("null")
    @Test
    void tagWrittenByAReorderingAdapterIsStillFound() {
        // A JSONB-style adapter that returns keys in a different order.
        InMemorySessionStore inner = new InMemorySessionStore();
        SessionStore reordering = new SessionStore() {
            @Override
            public void append(SessionKey key, List<SessionStoreEntry> entries) {
                inner.append(key, entries);
            }

            @Override
            public @Nullable List<SessionStoreEntry> load(SessionKey key) {
                List<SessionStoreEntry> loaded = inner.load(key);
                if (loaded == null) {
                    return null;
                }
                return loaded.stream().map(e -> {
                    Map<String, Object> rev = new LinkedHashMap<>();
                    List<String> keys = new java.util.ArrayList<>(e.asMap().keySet());
                    java.util.Collections.reverse(keys);
                    for (String k : keys) {
                        rev.put(k, e.asMap().get(k));
                    }
                    return SessionStoreEntry.of(rev);
                }).toList();
            }
        };
        String sid = newSession(reordering, user("u1", "hello", "2026-01-01T00:00:00Z"),
                entry("type", "tag", "tag", "urgent", "sessionId", "x"));
        assertThat(SessionStores.getSessionInfoFromStore(reordering, sid, null).tag()).isEqualTo("urgent");
    }

    @Test
    void listingSentinelForAFailedLoadHasNoCwd() {
        InMemorySessionStore inner = new InMemorySessionStore();
        String ok = newSession(inner, user("u1", "ok prompt", "2026-01-01T00:00:00Z"));
        String bad = newSession(inner, user("u2", "bad prompt", "2026-01-01T00:00:01Z"));
        SessionStore failingForBad = new SessionStore() {
            @Override
            public void append(SessionKey key, List<SessionStoreEntry> entries) {
                inner.append(key, entries);
            }

            @Override
            public @Nullable List<SessionStoreEntry> load(SessionKey key) {
                if (bad.equals(key.sessionId())) {
                    throw new IllegalStateException("simulated");
                }
                return inner.load(key);
            }

            @Override
            public List<SessionStoreListEntry> listSessions(String projectKey) {
                return inner.listSessions(projectKey);
            }

            @Override
            public boolean implementsListSessions() {
                return true;
            }
        };

        Map<String, SDKSessionInfo> byId = new HashMap<>();
        for (SDKSessionInfo i : SessionStores.listSessionsFromStore(failingForBad, null, null, 0)) {
            byId.put(i.sessionId(), i);
        }
        assertThat(byId).containsKeys(ok, bad);
        SDKSessionInfo sentinel = byId.get(bad);
        assertThat(sentinel.summary()).isEmpty();
        assertThat(sentinel.cwd()).isNull();
        assertThat(sentinel.fileSize()).isNull();
        // The good row was lite-parsed: it carries a byte size and the project cwd.
        assertThat(byId.get(ok).fileSize()).isPositive();
        assertThat(byId.get(ok).cwd()).isNotNull();
    }

    // ------------------------------------------------------------ untyped entries

    @SuppressWarnings("null")
    @Test
    void untypedEntriesDoNotBreakListingInfoOrMessages() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        String sid = newSession(store,
                entry("uuid", "no-type", "note", "x"),
                user(UUID.randomUUID().toString(), "real prompt", "2026-01-01T00:00:00Z"),
                entry("type", 7, "uuid", "numeric"));

        assertThat(SessionStores.getSessionInfoFromStore(store, sid, null).summary()).isEqualTo("real prompt");
        assertThat(SessionStores.listSessionsFromStore(store, null, null, 0))
                .extracting(SDKSessionInfo::sessionId).contains(sid);
        assertThat(SessionStores.getSessionMessagesFromStore(store, sid, null, null, 0)).hasSize(1);
        ForkSessionResult fork = SessionStores.forkSessionViaStore(store, sid, null, null, null);
        assertThat(fork.sessionId()).isNotBlank();
    }

    // ------------------------------------------------------------------- fold

    @Test
    void foldReplacesAFalsyCommandFallback() {
        SessionKey key = new SessionKey("p", "s", null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("command_fallback", "");
        SessionSummaryEntry prev = new SessionSummaryEntry("s", 5L, data);

        SessionSummaryEntry folded = SessionSummary.foldSessionSummary(prev, key, List.of(
                user("u1", "<command-name>/review</command-name>", null)));

        assertThat(folded.data()).containsEntry("command_fallback", "/review");
    }

    @Test
    void foldKeepsATruthyCommandFallback() {
        SessionKey key = new SessionKey("p", "s", null);
        SessionSummaryEntry first = SessionSummary.foldSessionSummary(null, key, List.of(
                user("u1", "<command-name>/one</command-name>", null)));
        SessionSummaryEntry second = SessionSummary.foldSessionSummary(first, key, List.of(
                user("u2", "<command-name>/two</command-name>", null)));
        assertThat(second.data()).containsEntry("command_fallback", "/one");
    }

    @Test
    void naiveTimestampsAreReadAsUtc() {
        long expected = java.time.Instant.parse("2024-01-01T10:00:00Z").toEpochMilli();
        assertThat(SessionStores.isoToEpochMs("2024-01-01T10:00:00")).isEqualTo(expected);
        assertThat(SessionStores.isoToEpochMs("2024-01-01T10:00:00.000")).isEqualTo(expected);
        assertThat(SessionStores.isoToEpochMs("2024-01-01T10:00:00Z")).isEqualTo(expected);
        assertThat(SessionStores.isoToEpochMs("2024-01-01T12:00:00+02:00")).isEqualTo(expected);
        assertThat(SessionStores.isoToEpochMs("2024-01-01"))
                .isEqualTo(java.time.Instant.parse("2024-01-01T00:00:00Z").toEpochMilli());
        assertThat(SessionStores.isoToEpochMs("yesterday")).isNull();
        assertThat(SessionStores.isoToEpochMs(42)).isNull();

        SessionSummaryEntry folded = SessionSummary.foldSessionSummary(null, new SessionKey("p", "s", null),
                List.of(entry("type", "user", "timestamp", "2024-01-01T10:00:00")));
        assertThat(folded.data()).containsEntry("created_at", expected);
    }
}
