package in.vidyalai.claude.sdk.exceptions;

/**
 * Base exception for all Claude SDK errors.
 *
 * <p>
 * This is the root of the SDK exception hierarchy. All SDK-specific
 * exceptions extend this class.
 */
public class ClaudeSDKException extends RuntimeException {

    /**
     * Creates a new exception with the specified message.
     *
     * @param message the detail message
     */
    public ClaudeSDKException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with the specified message and cause.
     *
     * @param message the detail message
     * @param cause   the cause
     */
    public ClaudeSDKException(String message, Throwable cause) {
        super(message, cause);
    }

}
