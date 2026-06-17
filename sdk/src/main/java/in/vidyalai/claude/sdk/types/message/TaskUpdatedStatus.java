package in.vidyalai.claude.sdk.types.message;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Status value reported inside a {@code task_updated} patch
 * ({@link TaskUpdatedMessage#status()}).
 *
 * <p>
 * {@link #PENDING}, {@link #RUNNING} and {@link #PAUSED} are non-terminal;
 * {@link #COMPLETED}, {@link #FAILED} and {@link #KILLED} are terminal. Note
 * {@code task_updated} reports the raw {@link #KILLED}; the CLI maps that to
 * {@code stopped} ({@link TaskNotificationStatus#STOPPED}) only when it emits a
 * {@code task_notification}. See {@link TaskUpdatedMessage#TERMINAL_TASK_STATUSES}.
 */
public enum TaskUpdatedStatus {

    PENDING("pending"),
    RUNNING("running"),
    PAUSED("paused"),
    COMPLETED("completed"),
    FAILED("failed"),
    KILLED("killed");

    private final String value;

    TaskUpdatedStatus(String value) {
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

    public static TaskUpdatedStatus fromValue(String value) {
        for (TaskUpdatedStatus s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown TaskUpdatedStatus: " + value);
    }

    /**
     * Resolves a raw status string, returning {@code null} for an unknown or
     * {@code null} value instead of throwing.
     *
     * <p>
     * Used when parsing a {@code task_updated} patch: parsing a lifecycle event
     * must never raise, so an unrecognized status is surfaced as {@code null}
     * (the full patch is still preserved on {@link TaskUpdatedMessage#patch()}).
     *
     * @param value the raw status string (may be null)
     * @return the matching enum constant, or {@code null} if absent/unknown
     */
    public static @Nullable TaskUpdatedStatus fromValueOrNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        for (TaskUpdatedStatus s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        return null;
    }

}
