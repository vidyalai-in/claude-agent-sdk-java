package in.vidyalai.claude.sdk.types.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * System prompt file configuration.
 *
 * <p>
 * Use this to specify a system prompt loaded from a file path
 * via the {@code --system-prompt-file} CLI flag.
 *
 * <pre>{@code
 * var options = ClaudeAgentOptions.builder()
 *         .systemPrompt(new SystemPromptFile("/path/to/prompt.md"))
 *         .build();
 * }</pre>
 *
 * @param path the file system path to the system prompt file
 */
public record SystemPromptFile(
        @JsonProperty("path") String path) {

    private static final String TYPE = "file";

    /**
     * Returns the type identifier for serialization.
     */
    @JsonProperty("type")
    public String type() {
        return TYPE;
    }

}
