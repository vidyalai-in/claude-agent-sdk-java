package in.vidyalai.claude.sdk.types.message;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Values of {@link MessageOrigin#subkind()} when
 * {@link MessageOrigin#kind()} is {@link MessageOriginKind#TASK_NOTIFICATION}.
 *
 * <p>
 * Absent for ordinary background-task notifications.
 */
public enum TaskNotificationOriginSubkind {

    /** The delivery is the fired prompt of a scheduled task. */
    SCHEDULED_TRIGGER("scheduled-trigger"),

    /** The delivery is a message sent from another of the user's sessions. */
    PEER_SEND_MESSAGE("peer-send-message");

    private final String value;

    TaskNotificationOriginSubkind(String value) {
        this.value = value;
    }

    /**
     * Returns the wire value of this sub-kind.
     *
     * @return the value the CLI emits for this sub-kind
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    /**
     * Resolves a wire value to a sub-kind.
     *
     * @param value the wire value
     * @return the matching sub-kind
     * @throws IllegalArgumentException if the value is not a known sub-kind
     */
    public static TaskNotificationOriginSubkind fromValue(String value) {
        for (TaskNotificationOriginSubkind s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown TaskNotificationOriginSubkind: " + value);
    }

}
