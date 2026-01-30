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
        @JsonProperty("blocked_path") @Nullable String blockedPath) implements SDKControlRequestData {

    @Override
    public String subtype() {
        return "can_use_tool";
    }

}
