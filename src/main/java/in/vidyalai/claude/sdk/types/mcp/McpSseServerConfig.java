package in.vidyalai.claude.sdk.types.mcp;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * SSE-based MCP server configuration.
 *
 * @param url     the SSE endpoint URL
 * @param headers optional HTTP headers
 */
public record McpSseServerConfig(
        @JsonProperty("url") String url,
        @JsonProperty("headers") @Nullable Map<String, String> headers) implements McpServerConfig {

    @Override
    public String type() {
        return "sse";
    }

    /**
     * Creates an SSE config with just a URL.
     */
    public McpSseServerConfig(String url) {
        this(url, null);
    }

}
