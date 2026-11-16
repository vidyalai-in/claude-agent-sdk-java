package in.vidyalai.claude.sdk.types.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Claude AI proxy MCP server configuration.
 *
 * @param url the proxy URL
 * @param id  the proxy server identifier
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "type" })
public record McpClaudeAIProxyServerConfig(
        @JsonProperty("url") String url,
        @JsonProperty("id") String id) implements McpServerStatusConfig {

    @Override
    @JsonProperty("type")
    public String type() {
        return "claudeai-proxy";
    }

}
