package in.vidyalai.claude.sdk.types.mcp;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single context usage category (system prompt, tools, messages, etc.).
 *
 * @param name       category name
 * @param tokens     number of tokens used by this category
 * @param color      display color for the category
 * @param isDeferred whether this category's tools are deferred
 */
public record ContextUsageCategory(
        @JsonProperty("name") String name,
        @JsonProperty("tokens") int tokens,
        @JsonProperty("color") String color,
        @JsonProperty("isDeferred") @Nullable Boolean isDeferred) {
}
