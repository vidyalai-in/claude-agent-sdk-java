package in.vidyalai.claude.sdk.types.control.request;

import in.vidyalai.claude.sdk.types.permission.PermissionMode;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Request to set the permission mode.
 *
 * <p>
 * Part of the SDK Control Protocol for bidirectional communication
 * between the SDK and CLI.
 */
@JsonTypeName("set_permission_mode")
public record SDKControlSetPermissionModeRequest(
        PermissionMode mode) implements SDKControlRequestData {

    @Override
    public String subtype() {
        return "set_permission_mode";
    }

}
