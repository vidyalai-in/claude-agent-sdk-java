package in.vidyalai.claude.sdk.types.session;

import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.testing.SessionStoreConformance;

/**
 * Verifies the bundled {@link InMemorySessionStore} satisfies the full
 * 14-contract {@link SessionStoreConformance} suite.
 *
 * <p>Adapter authors should run the same suite against their own
 * implementations.
 */
class SessionStoreConformanceTest {

    @Test
    void inMemorySessionStorePassesConformance() {
        SessionStoreConformance.run(InMemorySessionStore::new);
    }

}
