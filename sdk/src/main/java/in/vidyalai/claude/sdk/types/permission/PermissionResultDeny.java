package in.vidyalai.claude.sdk.types.permission;

import java.util.HashMap;
import java.util.Map;

/**
 * Permission result indicating the tool should be denied.
 *
 * @param message   the reason for denial
 * @param interrupt whether to interrupt the current operation
 */
public record PermissionResultDeny(
        String message,
        boolean interrupt) implements PermissionResult {

    private static final String BEHAVIOR = "behavior";
    private static final String DENY = "deny";
    private static final String MESSAGE = "message";
    private static final String INTERRUPT = "interrupt";

    /**
     * Creates a deny result with a message.
     *
     * @param message the reason for denial
     */
    public PermissionResultDeny(String message) {
        this(message, false);
    }

    /**
     * Creates a deny result with a default message.
     */
    public PermissionResultDeny() {
        this("", false);
    }

    @Override
    public String behavior() {
        return DENY;
    }

    /**
     * Converts this permission result to a Map for JSON serialization.
     *
     * @param input original input
     * 
     * @return a map representation matching the CLI control protocol format
     */
    @Override
    public Map<String, Object> toMap(Map<String, Object> input) {
        Map<String, Object> result = new HashMap<>();
        result.put(BEHAVIOR, DENY);
        result.put(MESSAGE, message);
        if (interrupt) {
            result.put(INTERRUPT, true);
        }

        return result;
    }

}
