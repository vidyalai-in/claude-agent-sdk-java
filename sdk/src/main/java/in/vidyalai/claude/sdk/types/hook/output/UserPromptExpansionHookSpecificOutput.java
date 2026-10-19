package in.vidyalai.claude.sdk.types.hook.output;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Hook-specific output for UserPromptExpansion events.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code camelCase} for JSON
 * field names because it represents data <b>sent to the CLI</b> in hook
 * responses.
 * See {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param additionalContext      optional additional context for Claude
 * @param suppressOriginalPrompt when the hook output's {@code decision} is
 *                               {@code "block"}, omit the original prompt from
 *                               the block message
 */
public record UserPromptExpansionHookSpecificOutput(
        @JsonProperty("additionalContext") @Nullable String additionalContext,
        @JsonProperty("suppressOriginalPrompt") @Nullable Boolean suppressOriginalPrompt) implements HookSpecificOutput {

    @JsonProperty("hookEventName")
    @Override
    public String hookEventName() {
        return "UserPromptExpansion";
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();
        result.put(HOOK_EVENT_NAME, hookEventName());
        if (additionalContext != null) {
            result.put(ADDITIONAL_CONTEXT, additionalContext);
        }
        if (suppressOriginalPrompt != null) {
            result.put(SUPPRESS_ORIGINAL_PROMPT, suppressOriginalPrompt);
        }
        return result;
    }

}
