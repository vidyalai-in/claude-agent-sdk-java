package in.vidyalai.claude.sdk.types.control.request;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Request to handle an MCP message for an SDK MCP server.
 *
 * <p>
 * Part of the SDK Control Protocol for bidirectional communication
 * between the SDK and CLI. This request routes MCP JSONRPC messages
 * to in-process SDK MCP servers.
 *
 * @param serverName the name of the MCP server
 * @param message    the MCP JSONRPC message to route
 */
@JsonTypeName("mcp_message")
public record SDKControlMcpMessageRequest(
        @JsonProperty("server_name") String serverName,
        Map<String, Object> message) implements SDKControlRequestData {

    @Override
    public String subtype() {
        return "mcp_message";
    }

}
