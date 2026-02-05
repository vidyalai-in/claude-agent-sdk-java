package in.vidyalai.claude.sdk.types.hook.output;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.permission.PermissionDecision;

/**
 * Hook-specific output for PreToolUse events.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code camelCase} for JSON
 * field names because it represents data <b>sent to the CLI</b> in hook
 * responses.
 * See {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param permissionDecision       optional permission decision (allow, deny,
 *                                 ask)
 * @param permissionDecisionReason optional reason for permission decision
 * @param updatedInput             optional modified tool input
 * @param additionalContext        optional additional context for Claude
 */
public record PreToolUseHookSpecificOutput(
        @JsonProperty("permissionDecision") @Nullable PermissionDecision permissionDecision,
        @JsonProperty("permissionDecisionReason") @Nullable String permissionDecisionReason,
        @JsonProperty("updatedInput") @Nullable Map<String, Object> updatedInput,
        @JsonProperty("additionalContext") @Nullable String additionalContext) implements HookSpecificOutput {

    @JsonProperty("hookEventName")
    @Override
    public String hookEventName() {
        return HookEvent.PRE_TOOL_USE.getValue();
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
        if (updatedInput != null) {
            result.put(UPDATED_INPUT, updatedInput);
        }
        if (additionalContext != null) {
            result.put(ADDITIONAL_CONTEXT, additionalContext);
        }
        return result;
    }

}
