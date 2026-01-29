package in.vidyalai.claude.sdk.types.permission;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Permission modes for controlling Claude's tool usage.
 */
public enum PermissionMode {

    /**
     * Default mode - CLI prompts for dangerous tools.
     */
    DEFAULT("default"),

    /**
     * Auto-accept file edits without prompting.
     */
    ACCEPT_EDITS("acceptEdits"),

    /**
     * Plan mode - Claude creates plans before execution.
     */
    PLAN("plan"),

    /**
     * Allow all tools without prompting (use with caution).
     */
    BYPASS_PERMISSIONS("bypassPermissions");

    private final String value;

    PermissionMode(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static PermissionMode fromValue(String value) {
        for (PermissionMode mode : values()) {
            if (mode.value.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown permission mode: " + value);
    }

}
