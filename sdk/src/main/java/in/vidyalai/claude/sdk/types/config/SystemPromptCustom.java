package in.vidyalai.claude.sdk.types.config;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A custom system prompt, in the form that can also set {@code snapshot}.
 *
 * <p>
 * Equivalent to passing the prompt as a plain {@code String} (it is sent as
 * {@code --system-prompt}); use this form when you also want to control
 * {@link #snapshot()}.
 *
 * <pre>{@code
 * var options = ClaudeAgentOptions.builder()
 *         .systemPrompt(SystemPromptCustom.of("You are a terse reviewer.", false))
 *         .build();
 * }</pre>
 *
 * @param prompt   the system prompt text
 * @param snapshot same as {@link SystemPromptPreset#snapshot()}, applied to
 *                 {@code prompt}; {@code null} leaves it to the CLI
 */
public record SystemPromptCustom(
        @JsonProperty("prompt") String prompt,
        @JsonProperty("snapshot") @Nullable Boolean snapshot) {

    private static final String TYPE = "custom";

    /**
     * Creates a custom system prompt without an explicit snapshot setting.
     *
     * @param prompt the system prompt text
     */
    public SystemPromptCustom(String prompt) {
        this(prompt, null);
    }

    /**
     * Creates a custom system prompt with an explicit snapshot setting.
     *
     * @param prompt   the system prompt text
     * @param snapshot whether the session keeps the prompt it recorded on its
     *                 first request (see {@link SystemPromptPreset#snapshot()})
     * @return a new SystemPromptCustom
     */
    public static SystemPromptCustom of(String prompt, boolean snapshot) {
        return new SystemPromptCustom(prompt, snapshot);
    }

    /**
     * Returns the type identifier for serialization.
     */
    @JsonProperty("type")
    public String type() {
        return TYPE;
    }

}
