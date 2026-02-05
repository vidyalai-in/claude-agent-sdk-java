package in.vidyalai.claude.sdk.types.hook;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Supported hook event types.
 *
 * <p>
 * Note: SessionStart, SessionEnd, and Setup hooks are not included as they fire
 * in the CLI before the SDK connection is established, so SDK callbacks for
 * them would never execute.
 */
public enum HookEvent {

    /**
     * Triggered before a tool is used.
     */
    PRE_TOOL_USE("PreToolUse"),

    /**
     * Triggered after a tool is used.
     */
    POST_TOOL_USE("PostToolUse"),

    /**
     * Triggered after a tool use fails.
     */
    POST_TOOL_USE_FAILURE("PostToolUseFailure"),

    /**
     * Triggered when the user submits a prompt.
     */
    USER_PROMPT_SUBMIT("UserPromptSubmit"),

    /**
     * Triggered when the session stops.
     */
    STOP("Stop"),

    /**
     * Triggered when a subagent stops.
     */
    SUBAGENT_STOP("SubagentStop"),

    /**
     * Triggered before context compaction.
     */
    PRE_COMPACT("PreCompact"),

    /**
     * Triggered when a notification is sent.
     */
    NOTIFICATION("Notification"),

    /**
     * Triggered when a subagent starts.
     */
    SUBAGENT_START("SubagentStart"),

    /**
     * Triggered when a permission request is made.
     */
    PERMISSION_REQUEST("PermissionRequest");

    private final String value;

    HookEvent(String value) {
        this.value = value;
    }

    /**
     * Gets the JSON value for this hook event.
     *
     * @return the string value
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Converts a string value to a HookEvent.
     *
     * @param value the string value
     * @return the corresponding enum constant
     * @throws IllegalArgumentException if the value is unknown
     */
    public static HookEvent fromValue(String value) {
        for (HookEvent event : values()) {
            if (event.value.equals(value)) {
                return event;
            }
        }
        throw new IllegalArgumentException("Unknown hook event: " + value);
    }

}
