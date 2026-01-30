package in.vidyalai.claude.sdk.types.permission;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Represents different types of permission updates that can be applied.
 */
public enum PermissionUpdateType {

    /**
     * Add permission rules.
     */
    ADD_RULES("addRules"),

    /**
     * Replace existing rules.
     */
    REPLACE_RULES("replaceRules"),

    /**
     * Remove rules.
     */
    REMOVE_RULES("removeRules"),

    /**
     * Set the permission mode.
     */
    SET_MODE("setMode"),

    /**
     * Add allowed directories.
     */
    ADD_DIRS("addDirectories"),

    /**
     * Remove directories.
     */
    REMOVE_DIRS("removeDirectories");

    private final String value;

    PermissionUpdateType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static PermissionUpdateType fromValue(String value) {
        for (PermissionUpdateType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown permission update type: " + value);
    }

}
