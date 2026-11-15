package in.vidyalai.claude.sdk.types.hook;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What happens when a callback in a {@link HookMatcher} fails.
 *
 * <p>
 * A callback fails when its future completes exceptionally, when it completes
 * with {@code null}, or when it returns a value the CLI rejects, such as a
 * {@code permissionDecision} the CLI does not recognize. Unrecognized keys are
 * not a failure.
 *
 * @see HookMatcher#onFailure()
 */
public enum HookOnFailure {

    /**
     * Count a failure as no decision: the action goes ahead as if the hook
     * had not run. This is also what an unset {@code onFailure} does.
     */
    CONTINUE("continue"),

    /**
     * Block the action when a callback fails. A callback with no opinion must
     * then return an empty {@link in.vidyalai.claude.sdk.types.hook.output.HookOutput},
     * never {@code null}. Connecting to a CLI too old to honor this raises
     * {@link in.vidyalai.claude.sdk.exceptions.CLIConnectionException}.
     */
    BLOCK("block");

    private final String value;

    HookOnFailure(String value) {
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

    public static HookOnFailure fromValue(String value) {
        for (HookOnFailure v : values()) {
            if (v.value.equals(value)) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown HookOnFailure: " + value);
    }

}
