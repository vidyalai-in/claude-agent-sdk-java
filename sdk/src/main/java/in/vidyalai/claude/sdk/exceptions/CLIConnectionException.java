package in.vidyalai.claude.sdk.exceptions;

/**
 * Raised when unable to connect to Claude Code.
 */
public class CLIConnectionException extends ClaudeSDKException {

    /**
     * Creates a new exception with the specified message.
     *
     * @param message the detail message
     */
    public CLIConnectionException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with the specified message and cause.
     *
     * @param message the detail message
     * @param cause   the cause
     */
    public CLIConnectionException(String message, Throwable cause) {
        super(message, cause);
    }

}
