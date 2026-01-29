package in.vidyalai.claude.sdk.types.permission;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Decision related to permission for tool use.
 */
public enum PermissionDecision {

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

    PermissionDecision(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static PermissionDecision fromValue(String value) {
        for (PermissionDecision pd : values()) {
            if (pd.value.equals(value)) {
                return pd;
            }
        }
        throw new IllegalArgumentException("Unknown permission decision: " + value);
    }

}
