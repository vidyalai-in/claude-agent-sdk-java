package in.vidyalai.claude.sdk.types.session;

import static org.assertj.core.api.Assertions.assertThat;

import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.SDKSessionInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Listing sessions from a store must not deadlock when the configured executor
 * is bounded.
 *
 * <p>{@code SessionStores.deriveInfosViaLoadConcurrent} fans out one
 * {@code loadAsync} per session. The default {@code loadAsync} submits to
 * {@link SessionStoreExecutor}, so if the fan-out itself runs as a task on that
 * same executor and then blocks waiting for the load, it is waiting on a slot it
 * occupies. With an unbounded thread-per-task executor that merely costs a
 * thread; with a bounded one — which {@link SessionStoreExecutor}'s own javadoc
 * suggests — every worker parks and the listing never returns.
 *
 * <p>Every test here carries a {@code SEPARATE_THREAD} {@link Timeout}: a
 * regression must fail the build rather than hang it.
 */
class SessionStoreBoundedExecutorTest {

    @AfterEach
    void resetExecutor() {
        SessionStoreExecutor.reset();
    }

    /** Sync-only adapter: the shape that relies on the default *Async wrappers. */
    private static final class SyncOnlyStore implements SessionStore {
        private final Map<String, List<SessionStoreEntry>> data = new ConcurrentHashMap<>();

        @Override
        public void append(SessionKey key, List<SessionStoreEntry> entries) {
            data.computeIfAbsent(key.sessionId(), k -> new ArrayList<>()).addAll(entries);
        }

        @Override
        public List<SessionStoreEntry> load(SessionKey key) {
            return data.get(key.sessionId());
        }

        @Override
        public boolean implementsListSessions() {
            return true;
        }

        @Override
        public List<SessionStoreListEntry> listSessions(String projectKey) {
            List<SessionStoreListEntry> out = new ArrayList<>();
            long mtime = 1_700_000_000_000L;
            for (String sessionId : data.keySet()) {
                out.add(new SessionStoreListEntry(sessionId, mtime--));
            }
            return out;
        }
    }

    private static List<String> seed(SessionStore store, int sessions) {
        String projectKey = ClaudeSDK.projectKeyForDirectory(null);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < sessions; i++) {
            String sessionId = UUID.randomUUID().toString();
            ids.add(sessionId);
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("type", "user");
            user.put("uuid", "u" + i);
            user.put("sessionId", sessionId);
            user.put("timestamp", "2026-01-01T00:00:00Z");
            user.put("message", Map.of("role", "user", "content", "prompt " + i));
            store.append(new SessionKey(projectKey, sessionId, null),
                    List.of(SessionStoreEntry.of(user)));
        }
        return ids;
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS,
            threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void listSessionsFromStore_withSingleThreadedExecutor_doesNotDeadlock() {
        SyncOnlyStore store = new SyncOnlyStore();
        // More sessions than the store-load concurrency bound (16), so the fan-out
        // genuinely has to queue behind itself.
        List<String> ids = seed(store, 20);
        ExecutorService bounded = Executors.newSingleThreadExecutor();
        try {
            SessionStoreExecutor.setDefault(bounded);
            List<SDKSessionInfo> listed = ClaudeSDK.listSessionsFromStore(store, null, null, 0);
            assertThat(listed).hasSize(ids.size());
            assertThat(listed).extracting(info -> info.sessionId())
                    .containsExactlyInAnyOrderElementsOf(ids);
        } finally {
            bounded.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS,
            threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void listSessionsFromStore_withFixedPool_doesNotDeadlock() {
        SyncOnlyStore store = new SyncOnlyStore();
        List<String> ids = seed(store, 20);
        // The exact configuration SessionStoreExecutor's javadoc offers as an example.
        ExecutorService bounded = Executors.newFixedThreadPool(8);
        try {
            SessionStoreExecutor.setDefault(bounded);
            List<SDKSessionInfo> listed = ClaudeSDK.listSessionsFromStore(store, null, null, 0);
            assertThat(listed).extracting(info -> info.sessionId())
                    .containsExactlyInAnyOrderElementsOf(ids);
        } finally {
            bounded.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS,
            threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void listSessionsFromStore_withBoundedExecutor_stillPaginates() {
        SyncOnlyStore store = new SyncOnlyStore();
        seed(store, 20);
        ExecutorService bounded = Executors.newFixedThreadPool(2);
        try {
            SessionStoreExecutor.setDefault(bounded);
            List<SDKSessionInfo> page = ClaudeSDK.listSessionsFromStore(store, null, 5, 0);
            assertThat(page).hasSize(5);
        } finally {
            bounded.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS,
            threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void listSessionsFromStore_withDefaultExecutor_isUnaffected() {
        SyncOnlyStore store = new SyncOnlyStore();
        List<String> ids = seed(store, 20);
        List<SDKSessionInfo> listed = ClaudeSDK.listSessionsFromStore(store, null, null, 0);
        assertThat(listed).extracting(info -> info.sessionId())
                .containsExactlyInAnyOrderElementsOf(ids);
    }
}
