package in.vidyalai.claude.sdk.types.message;

/**
 * Result of a session fork operation.
 *
 * <p>
 * Returned by {@code ClaudeSDK.forkSession()} with the UUID of the newly
 * created forked session.
 *
 * @param sessionId UUID of the new forked session
 */
public record ForkSessionResult(String sessionId) {
}
