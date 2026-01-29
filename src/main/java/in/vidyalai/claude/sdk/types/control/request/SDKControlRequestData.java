package in.vidyalai.claude.sdk.types.control.request;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base interface for SDK Control Protocol request data.
 *
 * <p>
 * Part of the SDK Control Protocol for bidirectional communication
 * between the SDK and CLI. All specific request types implement this interface.
 *
 * <p>
 * This is a sealed interface with the following permitted subtypes:
 * <ul>
 * <li>{@link SDKControlMCPStatusRequest} - mcp status request</li>
 * <li>{@link SDKControlInterruptRequest} - interrupt execution</li>
 * <li>{@link SDKControlPermissionRequest} - tool permission request</li>
 * <li>{@link SDKControlInitializeRequest} - initialize control protocol</li>
 * <li>{@link SDKControlSetPermissionModeRequest} - set permission mode</li>
 * <li>{@link SDKControlSetModelRequest} - set model</li>
 * <li>{@link SDKHookCallbackRequest} - invoke hook callback</li>
 * <li>{@link SDKControlMcpMessageRequest} - route MCP message</li>
 * <li>{@link SDKControlRewindFilesRequest} - rewind files to checkpoint</li>
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "subtype")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SDKControlMCPStatusRequest.class, name = "mcp_status"),
        @JsonSubTypes.Type(value = SDKControlInterruptRequest.class, name = "interrupt"),
        @JsonSubTypes.Type(value = SDKControlPermissionRequest.class, name = "can_use_tool"),
        @JsonSubTypes.Type(value = SDKControlInitializeRequest.class, name = "initialize"),
        @JsonSubTypes.Type(value = SDKControlSetPermissionModeRequest.class, name = "set_permission_mode"),
        @JsonSubTypes.Type(value = SDKControlSetModelRequest.class, name = "set_model"),
        @JsonSubTypes.Type(value = SDKHookCallbackRequest.class, name = "hook_callback"),
        @JsonSubTypes.Type(value = SDKControlMcpMessageRequest.class, name = "mcp_message"),
        @JsonSubTypes.Type(value = SDKControlRewindFilesRequest.class, name = "rewind_files")
})
public sealed interface SDKControlRequestData permits
        SDKControlMCPStatusRequest,
        SDKControlInterruptRequest,
        SDKControlPermissionRequest,
        SDKControlInitializeRequest,
        SDKControlSetPermissionModeRequest,
        SDKControlSetModelRequest,
        SDKHookCallbackRequest,
        SDKControlMcpMessageRequest,
        SDKControlRewindFilesRequest {

    /**
     * Gets the request subtype.
     *
     * @return the subtype string
     */
    String subtype();

}
