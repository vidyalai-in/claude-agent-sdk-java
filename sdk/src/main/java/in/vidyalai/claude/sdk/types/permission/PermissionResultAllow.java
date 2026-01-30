package in.vidyalai.claude.sdk.types.permission;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Permission result indicating the tool should be allowed.
 *
 * @param updatedInput       optional modified input to use instead of the
 *                           original
 * @param updatedPermissions optional permission updates to apply
 */
public record PermissionResultAllow(
        @Nullable Map<String, Object> updatedInput,
        @Nullable List<PermissionUpdate> updatedPermissions) implements PermissionResult {

    private static final String BEHAVIOR = "behavior";
    private static final String ALLOW = "allow";
    private static final String UPD_INPUT = "updatedInput";
    private static final String UPD_PERMS = "updatedPermissions";

    /**
     * Creates a simple allow result with no modifications.
     */
    public PermissionResultAllow() {
        this(null, null);
    }

    /**
     * Creates an allow result with updated input.
     *
     * @param updatedInput the modified input to use
     */
    public PermissionResultAllow(Map<String, Object> updatedInput) {
        this(updatedInput, null);
    }

    @Override
    public String behavior() {
        return ALLOW;
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
        result.put(BEHAVIOR, ALLOW);
        result.put(UPD_INPUT, ((updatedInput != null) ? updatedInput : input));
        if (updatedPermissions != null) {
            List<Map<String, Object>> permUpdates = updatedPermissions.stream()
                    .map(PermissionUpdate::toMap)
                    .toList();
            result.put(UPD_PERMS, permUpdates);
        }

        return result;
    }

}
