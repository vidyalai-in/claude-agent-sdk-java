package in.vidyalai.claude.sdk.types.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

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
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "type" })
public record McpSdkServerConfigStatus(
        @JsonProperty("name") String name) implements McpServerStatusConfig {

    @Override
    @JsonProperty("type")
    public String type() {
        return "sdk";
    }

}
