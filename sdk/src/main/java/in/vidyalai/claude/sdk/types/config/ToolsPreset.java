package in.vidyalai.claude.sdk.types.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Tools preset configuration.
 *
 * <p>
 * Use this to specify a preset tools configuration instead of a list of tool
 * names.
 *
 * <pre>{@code
 * var options = ClaudeAgentOptions.builder()
 *         .tools(ToolsPreset.claudeCode())
 *         .build();
 * }</pre>
 *
 * @param preset the preset name (currently only "claude_code" is supported)
 */
public record ToolsPreset(
        @JsonProperty("preset") String preset) {

    private static final String PRESET = "claude_code";
    private static final String TYPE = "preset";

    /**
     * Creates a Claude Code tools preset.
     *
     * <p>
     * This preset includes all standard Claude Code tools.
     *
     * @return a new ToolsPreset with "claude_code" preset
     */
    public static ToolsPreset claudeCode() {
        return new ToolsPreset(PRESET);
    }

    /**
     * Returns the type identifier for serialization.
     */
    @JsonProperty("type")
    public String type() {
        return TYPE;
    }

}
