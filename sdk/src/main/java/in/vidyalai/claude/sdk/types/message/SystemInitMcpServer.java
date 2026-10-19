package in.vidyalai.claude.sdk.types.message;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An MCP server listed in {@link SystemInitData}.
 *
 * @param name   server name
 * @param status connection status
 * @param source where the server definition came from: {@code "sdk"},
 *               {@code "plugin"}, or a config scope such as {@code "user"} or
 *               {@code "project"}. Absent on CLIs that predate the field
 */
public record SystemInitMcpServer(
        @JsonProperty("name") String name,
        @JsonProperty("status") String status,
        @JsonProperty("source") @Nullable String source) {

}
