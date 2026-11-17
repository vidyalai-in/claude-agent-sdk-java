package in.vidyalai.claude.sdk.types.permission;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where a permission update applies and is kept. An update with a
 * {@code null} destination is sent as {@link #SESSION}.
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
    SESSION("session"),

    /**
     * Rules that came from the command line (the CLI's {@code --allowedTools}
     * and similar flags), as the TypeScript SDK names them. The CLI can offer
     * a suggestion with this destination.
     */
    CLI_ARG("cliArg");

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
