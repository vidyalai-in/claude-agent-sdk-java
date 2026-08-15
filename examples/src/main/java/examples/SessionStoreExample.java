package examples;

import java.util.List;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.MirrorErrorMessage;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.SDKSessionInfo;
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;
import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionStore;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;

/**
 * Demonstrates mirroring session transcripts to an external {@link SessionStore}.
 *
 * <p>The Java SDK ships with an {@link InMemorySessionStore} reference
 * adapter for testing and development. For production, implement your own
 * adapter (S3, Postgres, Redis, etc.) by implementing the {@link SessionStore}
 * interface — only {@code append} and {@code load} are required, the rest are
 * optional.
 *
 * <p>This example:
 * <ol>
 * <li>Configures a query with an {@link InMemorySessionStore}.</li>
 * <li>Runs a turn (the SDK will pass {@code --session-mirror} to the CLI and
 * append every transcript line to the store via {@link SessionStore#append}).</li>
 * <li>Reads the resulting session metadata back via the
 * {@code listSessionsFromStore} API.</li>
 * <li>Shows how to handle {@link MirrorErrorMessage} for non-fatal mirror
 * failures.</li>
 * </ol>
 *
 * <p><b>Note:</b> Until the bundled CLI supports {@code --session-mirror}, this
 * example primarily demonstrates the API shape; the store will only collect
 * entries when the CLI is also versioned to emit mirror traffic.
 *
 * <p><b>Resuming from the store:</b> pairing {@code sessionStore} with
 * {@code resume} (or {@code continueConversation}) makes the SDK load the
 * session out of the store and materialize it into a temporary
 * {@code CLAUDE_CONFIG_DIR} for the CLI subprocess. That temp directory is
 * seeded from your real config directory so the subprocess can still
 * authenticate: {@code .credentials.json} (with the refresh token redacted),
 * {@code .claude.json}, and your user {@code settings.json} /
 * {@code cowork_settings.json} — the last of which carries {@code apiKeyHelper}
 * along with your {@code env}, {@code hooks} and {@code permissions}. Plugin
 * declarations and {@code env.CLAUDE_CONFIG_DIR} are stripped from the copy,
 * since both only misbehave under a redirected config directory.
 */
public class SessionStoreExample {

    public static void main(String[] args) {
        System.out.println("=== Direct Store Usage (no CLI) ===");
        directStoreUsage();

        System.out.println("\n=== Async API Usage ===");
        asyncApiUsage();

        System.out.println("\n=== Wired into ClaudeAgentOptions ===");
        wiredIntoOptions();
    }

    /**
     * Use the async {@code *Async} variants on {@link SessionStore}. By
     * default these run on a per-task virtual thread; install a custom
     * {@link java.util.concurrent.Executor} via {@code SessionStoreExecutor.setDefault}
     * to share a pool across calls.
     */
    static void asyncApiUsage() {
        InMemorySessionStore store = new InMemorySessionStore();
        String projectKey = ClaudeSDK.projectKeyForDirectory(null);
        String sessionId = java.util.UUID.randomUUID().toString();
        SessionKey mainKey = new SessionKey(projectKey, sessionId, null);

        try {
            store.appendAsync(mainKey, List.of(
                    SessionStoreEntry.of(java.util.Map.of(
                            "type", "user",
                            "uuid", "u1",
                            "sessionId", sessionId,
                            "timestamp", "2026-04-27T00:00:00Z",
                            "message", java.util.Map.of(
                                    "content", List.of(java.util.Map.of(
                                            "type", "text",
                                            "text", "Async hello"))))))).get();

            List<SessionStoreEntry> loaded = store.loadAsync(mainKey).get();
            System.out.println("Async load returned " + (loaded != null ? loaded.size() : 0) + " entries");
        } catch (Exception e) {
            System.err.println("Async API failed: " + e.getMessage());
        }
    }

    /**
     * Use {@link SessionStore} APIs directly without involving the CLI.
     * Useful for unit tests and when porting transcripts between stores.
     */
    static void directStoreUsage() {
        InMemorySessionStore store = new InMemorySessionStore();
        String projectKey = ClaudeSDK.projectKeyForDirectory(null);
        String sessionId = java.util.UUID.randomUUID().toString();
        SessionKey mainKey = new SessionKey(projectKey, sessionId, null);

        // Append a transcript entry as if it had been mirrored from the CLI
        store.append(mainKey, List.of(
                SessionStoreEntry.of(java.util.Map.of(
                        "type", "user",
                        "uuid", "u1",
                        "sessionId", sessionId,
                        "timestamp", "2026-04-27T00:00:00Z",
                        "message", java.util.Map.of(
                                "content", List.of(java.util.Map.of(
                                        "type", "text",
                                        "text", "Hello world")))))));

        // Read the session metadata back
        List<SDKSessionInfo> sessions = ClaudeSDK.listSessionsFromStore(store, null, null, 0);
        System.out.println("Sessions in store: " + sessions.size());
        for (SDKSessionInfo info : sessions) {
            System.out.println("  " + info.sessionId() + " — " + info.summary());
        }

        // Rename via the store; appends a custom-title entry without disk I/O
        ClaudeSDK.renameSessionViaStore(store, sessionId, "My Renamed Session", null);
        SDKSessionInfo after = ClaudeSDK.getSessionInfoFromStore(store, sessionId, null);
        System.out.println("After rename: " + (after != null ? after.summary() : "(missing)"));
    }

    /**
     * Wire a {@link SessionStore} into {@link ClaudeAgentOptions} so the SDK
     * mirrors every transcript line to the store as the conversation runs.
     */
    @SuppressWarnings("null")
    static void wiredIntoOptions() {
        InMemorySessionStore store = new InMemorySessionStore();
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sessionStore(store)
                .build();

        try {
            List<Message> messages = ClaudeSDK.query("What is 2+2?", options);
            for (Message msg : messages) {
                if (msg instanceof AssistantMessage assistant) {
                    System.out.println("Assistant: " + assistant.getTextContent());
                } else if (msg instanceof ResultMessage result && !result.isError()) {
                    System.out.println("Result: " + result.result());
                } else if (msg instanceof MirrorErrorMessage err) {
                    // Non-fatal — local transcript is durable; the mirror copy
                    // is missing this batch.
                    System.err.println("Mirror error for "
                            + (err.key() != null ? err.key().sessionId() : "<unknown>")
                            + ": " + err.error());
                }
            }
        } catch (Exception e) {
            System.err.println("Query failed (likely no CLI installed): " + e.getMessage());
        }

        System.out.println("Store size after run: " + store.size());
    }

}
