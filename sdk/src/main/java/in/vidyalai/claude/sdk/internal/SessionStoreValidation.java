package in.vidyalai.claude.sdk.internal;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.session.SessionStore;

/**
 * Pre-flight validation for {@link ClaudeAgentOptions#sessionStore()} option
 * combinations.
 *
 * <p>Mirrors Python SDK's {@code session_store_validation.py}. Called before
 * subprocess spawn so misconfiguration fails fast instead of surfacing as a
 * confusing runtime error mid-session.
 */
public final class SessionStoreValidation {

    private SessionStoreValidation() {
    }

    /**
     * Throws {@link IllegalArgumentException} for invalid {@code sessionStore}
     * combinations.
     */
    public static void validate(ClaudeAgentOptions options) {
        SessionStore store = options.sessionStore();
        if (store == null) {
            return;
        }
        if (options.continueConversation()
                && options.resume() == null
                && !store.implementsListSessions()) {
            // When resume is explicitly set, listSessions() is provably never
            // called (resume wins over continue), so a minimal store is fine.
            throw new IllegalArgumentException(
                    "continueConversation with sessionStore requires the store to "
                            + "implement listSessions()");
        }
        if (options.enableFileCheckpointing()) {
            throw new IllegalArgumentException(
                    "sessionStore cannot be combined with enableFileCheckpointing "
                            + "(checkpoints are local-disk only and would diverge from the "
                            + "mirrored transcript)");
        }
    }

}
