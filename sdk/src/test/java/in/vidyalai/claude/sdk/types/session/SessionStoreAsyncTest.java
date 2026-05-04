package in.vidyalai.claude.sdk.types.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SessionStoreAsyncTest {

    @AfterEach
    void resetExecutor() {
        SessionStoreExecutor.reset();
    }

    private static SessionStoreEntry entry(String uuid) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "user");
        data.put("uuid", uuid);
        return SessionStoreEntry.of(data);
    }

    private static SessionKey newMain() {
        return new SessionKey("p", UUID.randomUUID().toString(), null);
    }

    @SuppressWarnings("null")
    @Test
    void appendAsync_andLoadAsync_roundTripVirtualThreadDefault() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        SessionKey key = newMain();
        store.appendAsync(key, List.of(entry("u1"))).get();
        List<SessionStoreEntry> loaded = store.loadAsync(key).get();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).uuid()).isEqualTo("u1");
    }

    @Test
    void appendAsync_runsOnConfiguredExecutor() throws Exception {
        AtomicReference<String> threadName = new AtomicReference<>();
        ExecutorService customExecutor = Executors.newSingleThreadExecutor(
                r -> {
                    Thread t = new Thread(r, "custom-store-exec");
                    t.setDaemon(true);
                    return t;
                });
        try {
            SessionStoreExecutor.setDefault(customExecutor);

            // A SessionStore that captures the thread used during append
            SessionStore store = new SessionStore() {
                @Override
                public void append(SessionKey key, List<SessionStoreEntry> entries) {
                    threadName.set(Thread.currentThread().getName());
                }

                @Override
                public List<SessionStoreEntry> load(SessionKey key) {
                    return null;
                }
            };

            store.appendAsync(newMain(), List.of(entry("u1"))).get();
            assertThat(threadName.get()).isEqualTo("custom-store-exec");
        } finally {
            customExecutor.shutdown();
        }
    }

    @Test
    void appendAsync_supportsExplicitExecutorOverride() throws Exception {
        AtomicReference<String> threadName = new AtomicReference<>();
        ExecutorService customExecutor = Executors.newSingleThreadExecutor(
                r -> {
                    Thread t = new Thread(r, "explicit-exec");
                    t.setDaemon(true);
                    return t;
                });
        try {
            SessionStore store = new SessionStore() {
                @Override
                public void append(SessionKey key, List<SessionStoreEntry> entries) {
                    threadName.set(Thread.currentThread().getName());
                }

                @Override
                public List<SessionStoreEntry> load(SessionKey key) {
                    return null;
                }
            };

            store.appendAsync(newMain(), List.of(entry("u1")), customExecutor).get();
            assertThat(threadName.get()).isEqualTo("explicit-exec");
        } finally {
            customExecutor.shutdown();
        }
    }

    @Test
    void allAsyncMethods_haveDefaultImplementations() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        SessionKey key = newMain();
        store.appendAsync(key, List.of(entry("u1"))).get();
        assertThat(store.loadAsync(key).get()).hasSize(1);
        assertThat(store.listSessionsAsync(key.projectKey()).get()).hasSize(1);
        assertThat(store.listSessionSummariesAsync(key.projectKey()).get()).hasSize(1);
        assertThat(store.listSubkeysAsync(
                new SessionListSubkeysKey(key.projectKey(), key.sessionId())).get()).isEmpty();
        store.deleteAsync(key).get();
        assertThat(store.loadAsync(key).get()).isNull();
    }

    @Test
    void executor_canBeReset() {
        ExecutorService customExecutor = Executors.newSingleThreadExecutor();
        try {
            SessionStoreExecutor.setDefault(customExecutor);
            assertThat(SessionStoreExecutor.getDefault()).isSameAs(customExecutor);
            SessionStoreExecutor.reset();
            assertThat(SessionStoreExecutor.getDefault()).isNotSameAs(customExecutor);
        } finally {
            customExecutor.shutdown();
        }
    }

    @Test
    void overriddenAsyncMethod_isUsedDirectly() throws Exception {
        // An adapter with a native async client overrides appendAsync without
        // implementing append (the sync default delegates to appendAsync.join).
        AtomicReference<String> path = new AtomicReference<>();
        SessionStore asyncOnly = new SessionStore() {
            @Override
            public void append(SessionKey key, List<SessionStoreEntry> entries) {
                // Sync path delegates back to the async one — same as adapters
                // would do.
                appendAsync(key, entries).join();
            }

            @Override
            public CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries) {
                path.set("async-override");
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public List<SessionStoreEntry> load(SessionKey key) {
                return null;
            }
        };

        asyncOnly.appendAsync(newMain(), List.of(entry("u1"))).get();
        assertThat(path.get()).isEqualTo("async-override");
    }

    @SuppressWarnings("null")
    @Test
    void asyncException_propagatesViaCompletionException() {
        SessionStore failing = new SessionStore() {
            @Override
            public void append(SessionKey key, List<SessionStoreEntry> entries) {
                throw new IllegalStateException("kaboom");
            }

            @Override
            public List<SessionStoreEntry> load(SessionKey key) {
                return null;
            }
        };

        CompletableFuture<Void> future = failing.appendAsync(newMain(), List.of(entry("u1")));
        Throwable thrown = null;
        try {
            future.get();
        } catch (ExecutionException | InterruptedException e) {
            thrown = e;
        }
        assertThat(thrown).isInstanceOf(ExecutionException.class);
        assertThat(thrown.getCause()).isInstanceOf(IllegalStateException.class)
                .hasMessage("kaboom");
    }

}
