package in.vidyalai.claude.sdk.types.mcp;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Tool annotations as returned in MCP server status.
 *
 * <p>
 * Wire format uses camelCase field names (from CLI JSON output).
 *
 * @param readOnly    whether the tool is read-only (may be null)
 * @param destructive whether the tool is destructive (may be null)
 * @param openWorld   whether the tool operates in an open world (may be null)
 */
public record McpToolAnnotations(
        @JsonProperty("readOnly") @Nullable Boolean readOnly,
        @JsonProperty("destructive") @Nullable Boolean destructive,
        @JsonProperty("openWorld") @Nullable Boolean openWorld) {
}
