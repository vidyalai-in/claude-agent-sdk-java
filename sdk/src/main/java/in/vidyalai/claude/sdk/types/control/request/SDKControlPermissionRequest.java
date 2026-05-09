package in.vidyalai.claude.sdk.types.control.request;

import in.vidyalai.claude.sdk.types.permission.PermissionUpdate;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Request for tool permission callback.
 *
 * <p>
 * Part of the SDK Control Protocol for bidirectional communication
 * between the SDK and CLI. This request is sent when the CLI needs to
 * check if a tool can be used.
 */
@JsonTypeName("can_use_tool")
public record SDKControlPermissionRequest(
        @JsonProperty("tool_name") String toolName,
        Map<String, Object> input,
        @JsonProperty("permission_suggestions") @Nullable List<PermissionUpdate> permissionSuggestions,
        @JsonProperty("blocked_path") @Nullable String blockedPath,
        @JsonProperty("tool_use_id") @Nullable String toolUseId,
        @JsonProperty("agent_id") @Nullable String agentId,
        @JsonProperty("decision_reason") @Nullable String decisionReason,
        @JsonProperty("title") @Nullable String title,
        @JsonProperty("display_name") @Nullable String displayName,
        @JsonProperty("description") @Nullable String description) implements SDKControlRequestData {

    /**
     * Backwards-compatible constructor without the enrichment fields.
     */
    public SDKControlPermissionRequest(
            String toolName,
            Map<String, Object> input,
            @Nullable List<PermissionUpdate> permissionSuggestions,
            @Nullable String blockedPath,
            @Nullable String toolUseId,
            @Nullable String agentId) {
        this(toolName, input, permissionSuggestions, blockedPath, toolUseId, agentId,
                null, null, null, null);
    }

    @Override
    public String subtype() {
        return "can_use_tool";
    }

}
