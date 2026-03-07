package in.vidyalai.claude.sdk.types.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * SDK MCP server configuration as returned in status responses.
 *
 * <p>
 * This is the status-side representation of an SDK server. Unlike
 * {@link McpSdkServerConfig} (used when creating servers), this record only
 * carries the server {@code name} since the live {@code instance} is not
 * serializable.
 *
 * @param name the server name
 */
public record McpSdkServerConfigStatus(
        @JsonProperty("name") String name) implements McpServerStatusConfig {

    @Override
    public String type() {
        return "sdk";
    }

}
