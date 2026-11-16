package in.vidyalai.claude.sdk.types.session;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.testing.SessionStoreConformance;

/**
 * Verifies the bundled {@link InMemorySessionStore} satisfies the full
 * 14-contract {@link SessionStoreConformance} suite, and that the suite
 * rejects adapters the Python suite rejects.
 *
 * <p>Adapter authors should run the same suite against their own
 * implementations.
 */
class SessionStoreConformanceTest {

    @Test
    void inMemorySessionStorePassesConformance() {
        SessionStoreConformance.run(InMemorySessionStore::new);
    }

    /** Delegates everything to an {@link InMemorySessionStore}; subclasses override one method. */
    private static class DelegatingStore implements SessionStore {
        final InMemorySessionStore inner = new InMemorySessionStore();

        @Override
        public void append(SessionKey key, List<SessionStoreEntry> entries) {
            inner.append(key, entries);
        }

        @Override
        public @Nullable List<SessionStoreEntry> load(SessionKey key) {
            return inner.load(key);
        }

        @Override
        public List<SessionStoreListEntry> listSessions(String projectKey) {
            return inner.listSessions(projectKey);
        }

        @Override
        public List<SessionSummaryEntry> listSessionSummaries(String projectKey) {
            return inner.listSessionSummaries(projectKey);
        }

        @Override
        public void delete(SessionKey key) {
            inner.delete(key);
        }

        @Override
        public List<String> listSubkeys(SessionListSubkeysKey key) {
            return inner.listSubkeys(key);
        }

        @Override
        public boolean implementsListSessions() {
            return true;
        }

        @Override
        public boolean implementsListSessionSummaries() {
            return true;
        }

        @Override
        public boolean implementsDelete() {
            return true;
        }

        @Override
        public boolean implementsListSubkeys() {
            return true;
        }
    }

    /** Returns null where the contract wants an empty subkey list. */
    private static final class NullSubkeysStore extends DelegatingStore {
        @Override
        public List<String> listSubkeys(SessionListSubkeysKey key) {
            List<String> subkeys = inner.listSubkeys(key);
            return subkeys.isEmpty() ? null : subkeys;
        }
    }

    /** Returns null where the contract wants an empty summary list. */
    private static final class NullSummariesStore extends DelegatingStore {
        @Override
        public List<SessionSummaryEntry> listSessionSummaries(String projectKey) {
            List<SessionSummaryEntry> summaries = inner.listSessionSummaries(projectKey);
            return summaries.isEmpty() ? null : summaries;
        }
    }

    /** Summarizes a session that its listSessions leaves out. */
    private static final class UnlistedSummaryStore extends DelegatingStore {
        @Override
        public List<SessionStoreListEntry> listSessions(String projectKey) {
            return inner.listSessions(projectKey).stream()
                    .filter(e -> !"summ-sess".equals(e.sessionId()))
                    .collect(Collectors.toList());
        }
    }

    @SuppressWarnings("null")
    @Test
    void nullInsteadOfEmptySubkeyListFails() {
        // Python requires `list_subkeys(...) == []`; null is not an empty list.
        assertThatThrownBy(() -> SessionStoreConformance.run(NullSubkeysStore::new))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("subkeys")
                .hasMessageContaining("was null");
    }

    @SuppressWarnings("null")
    @Test
    void nullInsteadOfEmptySummaryListFails() {
        assertThatThrownBy(() -> SessionStoreConformance.run(NullSummariesStore::new))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("contract 14")
                .hasMessageContaining("was null");
    }

    @SuppressWarnings("null")
    @Test
    void listSessionsOmittingASummarizedSessionFailsContract14() {
        // Python's `ls_by_id["summ-sess"]` raises KeyError when listSessions
        // leaves the session out.
        assertThatThrownBy(() -> SessionStoreConformance.run(UnlistedSummaryStore::new))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("contract 14")
                .hasMessageContaining("summ-sess");
    }
}
