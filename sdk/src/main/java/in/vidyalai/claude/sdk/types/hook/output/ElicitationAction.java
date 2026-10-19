package in.vidyalai.claude.sdk.types.hook.output;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How an Elicitation or ElicitationResult hook answers an MCP elicitation
 * request.
 *
 * @see ElicitationHookSpecificOutput
 * @see ElicitationResultHookSpecificOutput
 */
public enum ElicitationAction {

    /** Accept the request, submitting the hook's form field values. */
    ACCEPT("accept"),

    /** Decline the request. */
    DECLINE("decline"),

    /** Cancel the request. */
    CANCEL("cancel");

    private final String value;

    ElicitationAction(String value) {
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

    public static ElicitationAction fromValue(String value) {
        for (ElicitationAction a : values()) {
            if (a.value.equals(value)) {
                return a;
            }
        }
        throw new IllegalArgumentException("Unknown ElicitationAction: " + value);
    }

}
