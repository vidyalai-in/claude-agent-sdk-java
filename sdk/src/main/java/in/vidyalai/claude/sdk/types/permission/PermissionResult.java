package in.vidyalai.claude.sdk.types.permission;

import java.util.Map;

/**
 * Sealed interface for permission callback results.
 *
 * <p>
 * Permission results are returned from permission callbacks to indicate
 * whether a tool should be allowed or denied.
 *
 * @see PermissionResultAllow
 * @see PermissionResultDeny
 */
public sealed interface PermissionResult permits PermissionResultAllow, PermissionResultDeny {

    /**
     * Returns the behavior of this permission result.
     *
     * @return "allow" or "deny"
     */
    String behavior();

    Map<String, Object> toMap(Map<String, Object> input);

}
