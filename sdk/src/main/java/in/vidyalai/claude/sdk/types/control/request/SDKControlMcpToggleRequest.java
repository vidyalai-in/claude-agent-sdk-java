package in.vidyalai.claude.sdk.types.control.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Request to enable or disable an MCP server.
 *
 * <p>
 * Part of the SDK Control Protocol for bidirectional communication
 * between the SDK and CLI.
 *
 * @param serverName the name of the MCP server to toggle (camelCase per wire
 *                   protocol)
 * @param enabled    whether the server should be enabled
 */
@JsonTypeName("mcp_toggle")
public record SDKControlMcpToggleRequest(
        @JsonProperty("serverName") String serverName,
        @JsonProperty("enabled") boolean enabled) implements SDKControlRequestData {

    @Override
    public String subtype() {
        return "mcp_toggle";
    }

}
