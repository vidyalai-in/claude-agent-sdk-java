package in.vidyalai.claude.sdk.types.config;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Configuration for controlling Claude's extended thinking behavior.
 *
 * <p>
 * ThinkingConfig is a sealed interface with three variants:
 * <ul>
 * <li>{@link ThinkingConfigAdaptive} - Use adaptive thinking (defaults to
 * 32,000 tokens)</li>
 * <li>{@link ThinkingConfigEnabled} - Enable thinking with a specific token
 * budget</li>
 * <li>{@link ThinkingConfigDisabled} - Disable extended thinking</li>
 * </ul>
 *
 * <p>
 * Use pattern matching to handle different config types:
 *
 * <pre>{@code
 * switch (config) {
 *     case ThinkingConfigAdaptive adaptive -> System.out.println("Adaptive thinking enabled");
 *     case ThinkingConfigEnabled enabled -> System.out.println("Budget: " + enabled.budgetTokens());
 *     case ThinkingConfigDisabled disabled -> System.out.println("Thinking disabled");
 * }
 * }</pre>
 *
 * <p>
 * This configuration takes precedence over the deprecated
 * {@code max_thinking_tokens} field.
 *
 * @see ThinkingConfigAdaptive
 * @see ThinkingConfigEnabled
 * @see ThinkingConfigDisabled
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ThinkingConfigAdaptive.class, name = "adaptive"),
        @JsonSubTypes.Type(value = ThinkingConfigEnabled.class, name = "enabled"),
        @JsonSubTypes.Type(value = ThinkingConfigDisabled.class, name = "disabled")
})
public sealed interface ThinkingConfig permits
        ThinkingConfigAdaptive,
        ThinkingConfigEnabled,
        ThinkingConfigDisabled {

    /**
     * Returns the type identifier for this thinking config.
     *
     * @return the type string ("adaptive", "enabled", or "disabled")
     */
    String type();

}
