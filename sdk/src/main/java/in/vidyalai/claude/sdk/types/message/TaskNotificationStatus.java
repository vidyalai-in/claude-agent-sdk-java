package in.vidyalai.claude.sdk.types.message;

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

}
