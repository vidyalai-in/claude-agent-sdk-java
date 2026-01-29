package in.vidyalai.claude.sdk.types.control.request;

import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Request to get current MCP server connection status.
 *
 * <p>
 * Part of the SDK Control Protocol for bidirectional communication
 * between the SDK and CLI.
 */
@JsonTypeName("mcp_status")
public record SDKControlMCPStatusRequest() implements SDKControlRequestData {

    @Override
    public String subtype() {
        return "mcp_status";
    }

}
