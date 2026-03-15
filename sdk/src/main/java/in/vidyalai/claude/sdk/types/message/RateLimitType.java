package in.vidyalai.claude.sdk.types.message;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Rate limit window type values emitted by the CLI.
 */
public enum RateLimitType {

    /** 5-hour rolling window. */
    FIVE_HOUR("five_hour"),

    /** 7-day rolling window. */
    SEVEN_DAY("seven_day"),

    /** 7-day rolling window for Opus models. */
    SEVEN_DAY_OPUS("seven_day_opus"),

    /** 7-day rolling window for Sonnet models. */
    SEVEN_DAY_SONNET("seven_day_sonnet"),

    /** Overage / pay-as-you-go limit. */
    OVERAGE("overage");

    private final String value;

    RateLimitType(String value) {
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

    public static RateLimitType fromValue(String value) {
        for (RateLimitType t : values()) {
            if (t.value.equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown RateLimitType: " + value);
    }

}
