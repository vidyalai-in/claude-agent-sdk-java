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
 * @param preset                  the preset name (currently only "claude_code" is supported)
 * @param append                  optional string to append to the preset system prompt
 * @param excludeDynamicSections  strip per-user dynamic sections (working directory, auto-memory,
 *                                git status) from the system prompt so it stays static and
 *                                cacheable across users. The stripped content is re-injected
 *                                into the first user message so the model still has access to it.
 */
public record SystemPromptPreset(
        @JsonProperty("preset") String preset,
        @JsonProperty("append") @Nullable String append,
        @JsonProperty("exclude_dynamic_sections") @Nullable Boolean excludeDynamicSections) {

    private static final String PRESET = "claude_code";
    private static final String TYPE = "preset";

    /**
     * Compact constructor for backwards compatibility (without excludeDynamicSections).
     */
    public SystemPromptPreset(String preset, @Nullable String append) {
        this(preset, append, null);
    }

    /**
     * Creates a Claude Code system prompt preset.
     *
     * @return a new SystemPromptPreset with "claude_code" preset
     */
    public static SystemPromptPreset claudeCode() {
        return new SystemPromptPreset(PRESET, null, null);
    }

    /**
     * Creates a Claude Code system prompt preset with additional instructions.
     *
     * @param append additional instructions to append
     * @return a new SystemPromptPreset
     */
    public static SystemPromptPreset claudeCode(String append) {
        return new SystemPromptPreset(PRESET, append, null);
    }

    /**
     * Creates a Claude Code system prompt preset with exclude_dynamic_sections.
     *
     * @param append                  additional instructions to append (may be null)
     * @param excludeDynamicSections  whether to strip per-user dynamic sections for cross-user caching
     * @return a new SystemPromptPreset
     */
    public static SystemPromptPreset claudeCode(@Nullable String append, boolean excludeDynamicSections) {
        return new SystemPromptPreset(PRESET, append, excludeDynamicSections);
    }

    /**
     * Returns the type identifier for serialization.
     */
    @JsonProperty("type")
    public String type() {
        return TYPE;
    }

}
