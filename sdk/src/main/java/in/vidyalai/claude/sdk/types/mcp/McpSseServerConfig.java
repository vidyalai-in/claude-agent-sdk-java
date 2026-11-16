package in.vidyalai.claude.sdk.types.mcp;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * SSE-based MCP server configuration.
 *
 * @param url     the SSE endpoint URL
 * @param headers optional HTTP headers
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "type" })
public record McpSseServerConfig(
        @JsonProperty("url") String url,
        @JsonProperty("headers") @Nullable Map<String, String> headers) implements McpServerConfig, McpServerStatusConfig {

    @Override
    @JsonProperty("type")
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
