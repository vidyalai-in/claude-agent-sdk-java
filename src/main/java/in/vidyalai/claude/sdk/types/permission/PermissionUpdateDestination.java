package in.vidyalai.claude.sdk.types.permission;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Destination for permission updates.
 */
public enum PermissionUpdateDestination {

    /**
     * User-level settings.
     */
    USER_SETTINGS("userSettings"),

    /**
     * Project-level settings.
     */
    PROJECT_SETTINGS("projectSettings"),

    /**
     * Local settings.
     */
    LOCAL_SETTINGS("localSettings"),

    /**
     * Session-only (not persisted).
     */
    SESSION("session");

    private final String value;

    PermissionUpdateDestination(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static PermissionUpdateDestination fromValue(String value) {
        for (PermissionUpdateDestination dest : values()) {
            if (dest.value.equals(value)) {
                return dest;
            }
        }
        throw new IllegalArgumentException("Unknown permission update destination: " + value);
    }

}
