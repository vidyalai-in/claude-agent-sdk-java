package in.vidyalai.claude.sdk.types.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Claude AI proxy MCP server configuration.
 *
 * @param url the proxy URL
 * @param id  the proxy server identifier
 */
public record McpClaudeAIProxyServerConfig(
        @JsonProperty("url") String url,
        @JsonProperty("id") String id) implements McpServerStatusConfig {

    @Override
    public String type() {
        return "claudeai-proxy";
    }

}
