package in.vidyalai.claude.sdk.testing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionListSubkeysKey;
import in.vidyalai.claude.sdk.types.session.SessionStore;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;
import in.vidyalai.claude.sdk.types.session.SessionStoreListEntry;
import in.vidyalai.claude.sdk.types.session.SessionSummary;
import in.vidyalai.claude.sdk.types.session.SessionSummaryEntry;

/**
 * Shared conformance test suite for {@link SessionStore} adapters.
 *
 * <p>Call {@link #run(Supplier)} from a JUnit (or any test-framework) test to
 * assert the 14 behavioral contracts every adapter must satisfy. Tests for
 * optional methods ({@code listSessions}, {@code listSessionSummaries},
 * {@code delete}, {@code listSubkeys}) are skipped when listed in
 * {@code skipOptional} or when the store reports it doesn't implement that
 * method via the {@code implements*()} probes.
 *
 * <p>Mirrors Python SDK's
 * {@code session_store_conformance.run_session_store_conformance}.
 *
 * <p>Uses plain {@link AssertionError} (no test-framework dependency) so it
 * can be invoked from JUnit, TestNG, Spock, or even a plain {@code main}
 * smoke test.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * import in.vidyalai.claude.sdk.testing.SessionStoreConformance;
 *
 * @Test
 * void myStoreSatisfiesTheContract() {
 *     SessionStoreConformance.run(MyRedisStore::new);
 *     // Or skip optional methods you don't implement:
 *     SessionStoreConformance.run(WormStore::new,
 *             EnumSet.of(SessionStoreConformance.OptionalMethod.DELETE));
 * }
 * }</pre>
 */
public final class SessionStoreConformance {

    /** Optional methods that may be skipped via {@link #run(Supplier, Set)}. */
    public enum OptionalMethod {
        LIST_SESSIONS,
        LIST_SESSION_SUMMARIES,
        DELETE,
        LIST_SUBKEYS
    }

    private static final SessionKey KEY = new SessionKey("proj", "sess", null);

    private SessionStoreConformance() {
    }

    /**
     * Run the full 14-contract conformance suite. {@code makeStore} is
     * invoked once per contract to provide isolation between tests.
     */
    public static void run(Supplier<SessionStore> makeStore) {
        run(makeStore, Set.of());
    }

    /**
     * Run the conformance suite, skipping the listed optional methods.
     */
    public static void run(Supplier<SessionStore> makeStore, Set<OptionalMethod> skipOptional) {
        SessionStore probe = makeStore.get();
        boolean hasListSessions = supports(probe, OptionalMethod.LIST_SESSIONS, skipOptional)
                && probe.implementsListSessions();
        boolean hasListSummaries = supports(probe, OptionalMethod.LIST_SESSION_SUMMARIES, skipOptional)
                && probe.implementsListSessionSummaries();
        boolean hasDelete = supports(probe, OptionalMethod.DELETE, skipOptional)
                && probe.implementsDelete();
        boolean hasListSubkeys = supports(probe, OptionalMethod.LIST_SUBKEYS, skipOptional)
                && probe.implementsListSubkeys();

        // --- Required: append + load ---------------------------------------

        // 1. append then load returns same entries in same order
        SessionStore store = makeStore.get();
        store.append(KEY, List.of(e(Map.of("uuid", "b", "n", 1)), e(Map.of("uuid", "a", "n", 2))));
        List<SessionStoreEntry> loaded = store.load(KEY);
        assertEqualsExactly("contract 1: append+load preserves order", loaded, List.of(
                e(Map.of("uuid", "b", "n", 1)),
                e(Map.of("uuid", "a", "n", 2))));

        // 2. load unknown key returns null
        store = makeStore.get();
        assertNull("contract 2: load unknown main key", store.load(new SessionKey("proj", "nope", null)));
        store.append(KEY, List.of(e(Map.of("uuid", "x", "n", 1))));
        assertNull("contract 2: load unknown subpath", store.load(
                new SessionKey(KEY.projectKey(), KEY.sessionId(), "nope")));

        // 3. multiple append calls preserve call order
        store = makeStore.get();
        store.append(KEY, List.of(e(Map.of("uuid", "z", "n", 1))));
        store.append(KEY, List.of(
                e(Map.of("uuid", "a", "n", 2)),
                e(Map.of("uuid", "m", "n", 3))));
        store.append(KEY, List.of(e(Map.of("uuid", "b", "n", 4))));
        assertEqualsExactly("contract 3: multiple appends preserve order", store.load(KEY), List.of(
                e(Map.of("uuid", "z", "n", 1)),
                e(Map.of("uuid", "a", "n", 2)),
                e(Map.of("uuid", "m", "n", 3)),
                e(Map.of("uuid", "b", "n", 4))));

        // 4. append([]) is a no-op
        store = makeStore.get();
        store.append(KEY, List.of(e(Map.of("uuid", "a", "n", 1))));
        store.append(KEY, List.of());
        assertEqualsExactly("contract 4: append([]) is a no-op", store.load(KEY),
                List.of(e(Map.of("uuid", "a", "n", 1))));

        // 5. subpath keys are stored independently of main
        store = makeStore.get();
        SessionKey sub = new SessionKey(KEY.projectKey(), KEY.sessionId(), "subagents/agent-1");
        store.append(KEY, List.of(e(Map.of("uuid", "m", "n", 1))));
        store.append(sub, List.of(e(Map.of("uuid", "s", "n", 1))));
        assertEqualsExactly("contract 5: main isolated from subpath",
                store.load(KEY), List.of(e(Map.of("uuid", "m", "n", 1))));
        assertEqualsExactly("contract 5: subpath isolated from main",
                store.load(sub), List.of(e(Map.of("uuid", "s", "n", 1))));

        // 6. project_key isolation
        store = makeStore.get();
        store.append(new SessionKey("A", "s1", null), List.of(e(Map.of("from", "A"))));
        store.append(new SessionKey("B", "s1", null), List.of(e(Map.of("from", "B"))));
        assertEqualsExactly("contract 6: project A isolated",
                store.load(new SessionKey("A", "s1", null)),
                List.of(e(Map.of("from", "A"))));
        assertEqualsExactly("contract 6: project B isolated",
                store.load(new SessionKey("B", "s1", null)),
                List.of(e(Map.of("from", "B"))));
        if (hasListSessions) {
            assertSize("contract 6: project A has 1 session", store.listSessions("A"), 1);
            assertSize("contract 6: project B has 1 session", store.listSessions("B"), 1);
        }

        // --- Optional: listSessions ----------------------------------------

        if (hasListSessions) {
            // 7. listSessions returns session_ids for project
            store = makeStore.get();
            store.append(new SessionKey("proj", "a", null), List.of(e(Map.of("n", 1))));
            store.append(new SessionKey("proj", "b", null), List.of(e(Map.of("n", 1))));
            store.append(new SessionKey("other", "c", null), List.of(e(Map.of("n", 1))));
            List<SessionStoreListEntry> sessions = store.listSessions("proj");
            assertEquals("contract 7: listSessions returns project sessions",
                    sortedSessionIds(sessions), List.of("a", "b"));
            for (SessionStoreListEntry s : sessions) {
                assertTrue("contract 7: mtime must be epoch-ms (>1e12), got " + s.mtime(),
                        s.mtime() > 1_000_000_000_000L);
            }
            assertSize("contract 7: empty project listing",
                    store.listSessions("never-appended-project"), 0);

            // 8. listSessions excludes subagent subpaths
            store = makeStore.get();
            store.append(new SessionKey("proj", "main", null), List.of(e(Map.of("n", 1))));
            store.append(new SessionKey("proj", "main", "subagents/agent-1"),
                    List.of(e(Map.of("n", 1))));
            assertEquals("contract 8: listSessions excludes subagent subpaths",
                    sortedSessionIds(store.listSessions("proj")), List.of("main"));
        }

        // --- Optional: listSessionSummaries --------------------------------

        if (hasListSummaries) {
            // 14. listSessionSummaries returns persisted fold output that
            // round-trips through foldSessionSummary again.
            store = makeStore.get();
            SessionKey sumKey = new SessionKey("proj", "summ-sess", null);
            store.append(sumKey, List.of(
                    e(Map.of("timestamp", "2024-01-01T00:00:00.000Z", "customTitle", "first")),
                    e(Map.of("timestamp", "2024-01-01T00:00:01.000Z"))));
            store.append(sumKey, List.of(
                    e(Map.of("timestamp", "2024-01-01T00:00:02.000Z", "customTitle", "second"))));
            store.append(new SessionKey("other", "elsewhere", null),
                    List.of(e(Map.of("timestamp", "2024-01-01T00:00:00.000Z"))));

            List<SessionSummaryEntry> summaries = store.listSessionSummaries("proj");
            Map<String, SessionSummaryEntry> byId = new LinkedHashMap<>();
            for (SessionSummaryEntry s : summaries) {
                byId.put(s.sessionId(), s);
            }
            assertEquals("contract 14: summary keys", new TreeSet<>(byId.keySet()),
                    new TreeSet<>(Set.of("summ-sess")));
            SessionSummaryEntry summ = byId.get("summ-sess");
            assertNotNull("contract 14: summ-sess present", summ);
            assertTrue("contract 14: summary mtime is epoch-ms", summ.mtime() > 1_000_000_000_000L);

            if (hasListSessions) {
                Map<String, Long> lsByMtime = new LinkedHashMap<>();
                for (SessionStoreListEntry le : store.listSessions("proj")) {
                    lsByMtime.put(le.sessionId(), le.mtime());
                }
                Long mt = lsByMtime.get("summ-sess");
                if (mt != null) {
                    assertTrue("contract 14: summary mtime >= listSessions mtime",
                            summ.mtime() >= mt);
                }
            }

            assertTrue("contract 14: summary data is a Map", summ.data() instanceof Map);
            SessionSummaryEntry refolded = SessionSummary.foldSessionSummary(
                    summ, sumKey, List.of(e(Map.of("timestamp", "2024-01-01T00:00:03.000Z"))));
            assertEquals("contract 14: refold preserves session_id",
                    refolded.sessionId(), "summ-sess");
            // The fold preserves prev.mtime() verbatim.
            assertEquals("contract 14: refold preserves prev.mtime",
                    refolded.mtime(), summ.mtime());

            // Subagent appends must NOT affect the main session's summary.
            store.append(new SessionKey(sumKey.projectKey(), sumKey.sessionId(), "subagents/agent-1"),
                    List.of(e(Map.of("timestamp", "2024-01-01T00:00:09.000Z", "customTitle", "subagent"))));
            Map<String, SessionSummaryEntry> afterSub = new LinkedHashMap<>();
            for (SessionSummaryEntry s : store.listSessionSummaries("proj")) {
                afterSub.put(s.sessionId(), s);
            }
            assertEquals("contract 14: subagent append doesn't touch main summary",
                    afterSub.get("summ-sess").data(), summ.data());

            assertSize("contract 14: empty summaries listing",
                    store.listSessionSummaries("never-appended-project"), 0);
            if (hasDelete) {
                store.delete(sumKey);
                assertSize("contract 14: delete clears summaries",
                        store.listSessionSummaries("proj"), 0);
            }
        }

        // --- Optional: delete ---------------------------------------------

        if (hasDelete) {
            // 9. delete main then load returns null
            store = makeStore.get();
            store.delete(new SessionKey("proj", "never-written", null));
            store.append(KEY, List.of(e(Map.of("n", 1))));
            store.delete(KEY);
            assertNull("contract 9: load after delete", store.load(KEY));

            // 10. delete main cascades to subkeys
            store = makeStore.get();
            SessionKey sub1 = new SessionKey(KEY.projectKey(), KEY.sessionId(), "subagents/agent-1");
            SessionKey sub2 = new SessionKey(KEY.projectKey(), KEY.sessionId(), "subagents/agent-2");
            SessionKey other = new SessionKey("proj", "sess2", null);
            SessionKey otherProj = new SessionKey("other-proj", KEY.sessionId(), null);
            store.append(KEY, List.of(e(Map.of("n", 1))));
            store.append(sub1, List.of(e(Map.of("n", 1))));
            store.append(sub2, List.of(e(Map.of("n", 1))));
            store.append(other, List.of(e(Map.of("n", 1))));
            store.append(otherProj, List.of(e(Map.of("n", 1))));

            store.delete(KEY);

            assertNull("contract 10: main deleted", store.load(KEY));
            assertNull("contract 10: cascaded sub1 deleted", store.load(sub1));
            assertNull("contract 10: cascaded sub2 deleted", store.load(sub2));
            assertSize("contract 10: other session preserved", store.load(other), 1);
            assertSize("contract 10: other project preserved", store.load(otherProj), 1);
            if (hasListSubkeys) {
                assertSize("contract 10: subkeys empty after cascade",
                        store.listSubkeys(new SessionListSubkeysKey(KEY.projectKey(), KEY.sessionId())), 0);
            }
            if (hasListSessions) {
                assertTrue("contract 10: deleted session not listed",
                        sortedSessionIds(store.listSessions(KEY.projectKey()))
                                .stream().noneMatch(KEY.sessionId()::equals));
            }

            // 11. delete with subpath removes only that subkey
            store = makeStore.get();
            store.append(KEY, List.of(e(Map.of("n", 1))));
            store.append(sub1, List.of(e(Map.of("n", 1))));
            store.append(sub2, List.of(e(Map.of("n", 1))));

            store.delete(sub1);

            assertNull("contract 11: sub1 deleted", store.load(sub1));
            assertSize("contract 11: sub2 preserved", store.load(sub2), 1);
            assertSize("contract 11: main preserved", store.load(KEY), 1);
            if (hasListSubkeys) {
                assertEquals("contract 11: only sub1 removed",
                        sorted(store.listSubkeys(new SessionListSubkeysKey(
                                KEY.projectKey(), KEY.sessionId()))),
                        List.of("subagents/agent-2"));
            }
        }

        // --- Optional: listSubkeys ----------------------------------------

        if (hasListSubkeys) {
            // 12. listSubkeys returns subpaths
            store = makeStore.get();
            store.append(KEY, List.of(e(Map.of("n", 1))));
            store.append(new SessionKey(KEY.projectKey(), KEY.sessionId(), "subagents/agent-1"),
                    List.of(e(Map.of("n", 1))));
            store.append(new SessionKey(KEY.projectKey(), KEY.sessionId(), "subagents/agent-2"),
                    List.of(e(Map.of("n", 1))));
            store.append(new SessionKey(KEY.projectKey(), "other-sess", "subagents/agent-x"),
                    List.of(e(Map.of("n", 1))));

            List<String> subkeys = store.listSubkeys(new SessionListSubkeysKey(
                    KEY.projectKey(), KEY.sessionId()));
            assertEquals("contract 12: listSubkeys returns subpaths",
                    sorted(subkeys), List.of("subagents/agent-1", "subagents/agent-2"));
            assertTrue("contract 12: other-sess subpaths excluded",
                    subkeys.stream().noneMatch("subagents/agent-x"::equals));

            // 13. listSubkeys excludes main transcript
            store = makeStore.get();
            store.append(KEY, List.of(e(Map.of("n", 1))));
            assertSize("contract 13: main-only session has no subkeys",
                    store.listSubkeys(new SessionListSubkeysKey(KEY.projectKey(), KEY.sessionId())), 0);
            assertSize("contract 13: never-appended session has no subkeys",
                    store.listSubkeys(new SessionListSubkeysKey("proj", "never-appended")), 0);
        }
    }

    private static boolean supports(SessionStore store, OptionalMethod method, Set<OptionalMethod> skip) {
        if (skip.contains(method)) {
            return false;
        }
        return switch (method) {
            case LIST_SESSIONS -> store.implementsListSessions();
            case LIST_SESSION_SUMMARIES -> store.implementsListSessionSummaries();
            case DELETE -> store.implementsDelete();
            case LIST_SUBKEYS -> store.implementsListSubkeys();
        };
    }

    private static List<String> sortedSessionIds(List<SessionStoreListEntry> sessions) {
        List<String> ids = new ArrayList<>();
        for (SessionStoreListEntry s : sessions) {
            ids.add(s.sessionId());
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    private static List<String> sorted(List<String> in) {
        List<String> out = new ArrayList<>(in);
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** Build a test entry satisfying {@link SessionStoreEntry} contract (`type` required). */
    private static SessionStoreEntry e(Map<String, Object> extras) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "x");
        data.putAll(extras);
        return SessionStoreEntry.of(data);
    }

    // ---- Plain assertion helpers (no test-framework dependency) -----------

    private static void assertTrue(String what, boolean cond) {
        if (!cond) {
            throw new AssertionError("Conformance failure — " + what);
        }
    }

    private static void assertEquals(String what, Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError("Conformance failure — " + what
                    + "\n  expected: " + expected
                    + "\n  actual:   " + actual);
        }
    }

    private static void assertEqualsExactly(String what, List<?> actual, List<?> expected) {
        assertEquals(what, actual, expected);
    }

    private static void assertNull(String what, Object actual) {
        if (actual != null) {
            throw new AssertionError("Conformance failure — " + what
                    + "\n  expected null but was: " + actual);
        }
    }

    private static void assertNotNull(String what, Object actual) {
        if (actual == null) {
            throw new AssertionError("Conformance failure — " + what
                    + "\n  expected non-null but was null");
        }
    }

    private static void assertSize(String what, Collection<?> actual, int expected) {
        if (actual == null) {
            if (expected == 0) {
                return; // null treated as empty
            }
            throw new AssertionError("Conformance failure — " + what
                    + "\n  expected size " + expected + " but was null");
        }
        if (actual.size() != expected) {
            throw new AssertionError("Conformance failure — " + what
                    + "\n  expected size " + expected + " but was " + actual.size()
                    + "\n  actual:   " + actual);
        }
    }

}
