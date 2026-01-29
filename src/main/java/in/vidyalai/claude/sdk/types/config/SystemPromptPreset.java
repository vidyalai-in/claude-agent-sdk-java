package in.vidyalai.claude.sdk.types.config;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * System prompt preset configuration.
 *
 * <p>
 * Use this to specify a preset system prompt instead of a raw string.
 *
 * <pre>{@code
 * var options = ClaudeAgentOptions.builder()
 *         .systemPrompt(new SystemPromptPreset("claude_code", "Additional instructions..."))
 *         .build();
 * }</pre>
 *
 * @param preset the preset name (currently only "claude_code" is supported)
 * @param append optional string to append to the preset system prompt
 */
public record SystemPromptPreset(
        @JsonProperty("preset") String preset,
        @JsonProperty("append") @Nullable String append) {

    private static final String PRESET = "claude_code";
    private static final String TYPE = "preset";

    /**
     * Creates a Claude Code system prompt preset.
     *
     * @return a new SystemPromptPreset with "claude_code" preset
     */
    public static SystemPromptPreset claudeCode() {
        return new SystemPromptPreset(PRESET, null);
    }

    /**
     * Creates a Claude Code system prompt preset with additional instructions.
     *
     * @param append additional instructions to append
     * @return a new SystemPromptPreset
     */
    public static SystemPromptPreset claudeCode(String append) {
        return new SystemPromptPreset(PRESET, append);
    }

    /**
     * Returns the type identifier for serialization.
     */
    @JsonProperty("type")
    public String type() {
        return TYPE;
    }

}
