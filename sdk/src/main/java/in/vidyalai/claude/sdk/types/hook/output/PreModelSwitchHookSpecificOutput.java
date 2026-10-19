package in.vidyalai.claude.sdk.types.hook.output;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

import in.vidyalai.claude.sdk.types.permission.PermissionDecision;

/**
 * Hook-specific output for PreModelSwitch events.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code camelCase} for JSON
 * field names because it represents data <b>sent to the CLI</b> in hook
 * responses.
 * See {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param permissionDecision       {@link PermissionDecision#ALLOW} proceeds,
 *                                 {@link PermissionDecision#DENY} cancels the
 *                                 switch. {@link PermissionDecision#ASK} is
 *                                 treated as a refusal outside an interactive
 *                                 session. {@link PermissionDecision#DEFER} is
 *                                 not accepted
 * @param permissionDecisionReason optional reason for the permission decision
 */
public record PreModelSwitchHookSpecificOutput(
        @JsonProperty("permissionDecision") @Nullable PermissionDecision permissionDecision,
        @JsonProperty("permissionDecisionReason") @Nullable String permissionDecisionReason) implements HookSpecificOutput {

    public PreModelSwitchHookSpecificOutput {
        if (permissionDecision == PermissionDecision.DEFER) {
            throw new IllegalArgumentException(
                    "PreModelSwitch permissionDecision must be allow, deny or ask, not defer");
        }
    }

    @JsonProperty("hookEventName")
    @Override
    public String hookEventName() {
        return "PreModelSwitch";
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();
        result.put(HOOK_EVENT_NAME, hookEventName());
        if (permissionDecision != null) {
            result.put(PERMISSION_DECISION, permissionDecision.getValue());
        }
        if (permissionDecisionReason != null) {
            result.put(PERMISSION_DECISION_REASON, permissionDecisionReason);
        }
        return result;
    }

}
