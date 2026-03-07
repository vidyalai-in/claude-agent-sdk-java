package in.vidyalai.claude.sdk.types.control.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Request to reconnect a disconnected or failed MCP server.
 *
 * <p>
 * Part of the SDK Control Protocol for bidirectional communication
 * between the SDK and CLI.
 *
 * @param serverName the name of the MCP server to reconnect (camelCase per wire
 *                   protocol)
 */
@JsonTypeName("mcp_reconnect")
public record SDKControlMcpReconnectRequest(
        @JsonProperty("serverName") String serverName) implements SDKControlRequestData {

    @Override
    public String subtype() {
        return "mcp_reconnect";
    }

}
