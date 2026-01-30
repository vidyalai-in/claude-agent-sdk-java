package in.vidyalai.claude.sdk.types.permission;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Permission behavior for tool permission rules.
 */
public enum PermissionBehavior {

    /**
     * Allow the tool to be used.
     */
    ALLOW("allow"),

    /**
     * Deny the tool from being used.
     */
    DENY("deny"),

    /**
     * Ask the user for permission.
     */
    ASK("ask");

    private final String value;

    PermissionBehavior(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static PermissionBehavior fromValue(String value) {
        for (PermissionBehavior behavior : values()) {
            if (behavior.value.equals(value)) {
                return behavior;
            }
        }
        throw new IllegalArgumentException("Unknown permission behavior: " + value);
    }

}
