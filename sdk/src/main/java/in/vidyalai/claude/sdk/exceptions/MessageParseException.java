package in.vidyalai.claude.sdk.exceptions;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Raised when unable to parse a message from CLI output.
 */
public class MessageParseException extends ClaudeSDKException {

    @Nullable
    private final Map<String, Object> data;

    /**
     * Creates a new exception with the specified message.
     *
     * @param message the detail message
     */
    public MessageParseException(String message) {
        super(message);
        this.data = null;
    }

    /**
     * Creates a new exception with the specified message and raw data.
     *
     * @param message the detail message
     * @param data    the raw message data that failed to parse
     */
    public MessageParseException(String message, @Nullable Map<String, Object> data) {
        super(message);
        this.data = data;
    }

    /**
     * Creates a new exception with the specified message, data, and cause.
     *
     * @param message the detail message
     * @param data    the raw message data that failed to parse
     * @param cause   the cause
     */
    public MessageParseException(String message, @Nullable Map<String, Object> data, Throwable cause) {
        super(message, cause);
        this.data = data;
    }

    /**
     * Gets the raw message data that failed to parse.
     *
     * @return the raw data, or null if not available
     */
    @Nullable
    public Map<String, Object> getData() {
        return data;
    }

}
