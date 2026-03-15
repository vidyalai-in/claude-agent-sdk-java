package in.vidyalai.claude.sdk.types.message;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Rate limit status values emitted by the CLI.
 */
public enum RateLimitStatus {

    /** Within rate limits, no action needed. */
    ALLOWED("allowed"),

    /** Approaching the rate limit — warn the user. */
    ALLOWED_WARNING("allowed_warning"),

    /** Rate limit has been hit — requests will be rejected. */
    REJECTED("rejected");

    private final String value;

    RateLimitStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    public static RateLimitStatus fromValue(String value) {
        for (RateLimitStatus s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown RateLimitStatus: " + value);
    }

}
