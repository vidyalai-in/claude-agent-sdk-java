package in.vidyalai.claude.sdk.types.message;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Task completion status reported in {@link TaskNotificationMessage}.
 */
public enum TaskNotificationStatus {

    COMPLETED("completed"),
    FAILED("failed"),
    STOPPED("stopped");

    private final String value;

    TaskNotificationStatus(String value) {
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

    public static TaskNotificationStatus fromValue(String value) {
        for (TaskNotificationStatus s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown TaskNotificationStatus: " + value);
    }

    /**
     * Resolves a raw status string, returning {@code null} for an unknown or
     * {@code null} value instead of throwing.
     *
     * <p>
     * Used when parsing a {@code task_notification}: a status added by a newer
     * CLI must not end the message stream. The wire string stays readable via
     * {@link TaskNotificationMessage#statusValue()}.
     *
     * @param value the raw status string (may be null)
     * @return the matching enum constant, or {@code null} if absent/unknown
     */
    public static @Nullable TaskNotificationStatus fromValueOrNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        for (TaskNotificationStatus s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        return null;
    }

}
