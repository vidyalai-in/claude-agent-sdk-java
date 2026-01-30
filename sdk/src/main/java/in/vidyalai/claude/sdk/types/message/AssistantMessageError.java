package in.vidyalai.claude.sdk.types.message;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Error types for assistant messages.
 */
public enum AssistantMessageError {

    /**
     * Authentication failed.
     */
    AUTHENTICATION_FAILED("authentication_failed"),

    /**
     * Billing error occurred.
     */
    BILLING_ERROR("billing_error"),

    /**
     * Rate limit exceeded.
     */
    RATE_LIMIT("rate_limit"),

    /**
     * Invalid request.
     */
    INVALID_REQUEST("invalid_request"),

    /**
     * Server error.
     */
    SERVER_ERROR("server_error"),

    /**
     * Unknown error.
     */
    UNKNOWN("unknown");

    private final String value;

    AssistantMessageError(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static AssistantMessageError fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (AssistantMessageError error : values()) {
            if (error.value.equals(value)) {
                return error;
            }
        }
        return UNKNOWN;
    }

}
