package in.vidyalai.claude.sdk.types.hook.output;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

import in.vidyalai.claude.sdk.types.hook.HookEvent;

/**
 * Hook-specific output for UserPromptSubmit events.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code camelCase} for JSON
 * field names because it represents data <b>sent to the CLI</b> in hook
 * responses.
 * See {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param additionalContext      optional additional context for Claude
 * @param sessionTitle           sets the session title
 * @param suppressOriginalPrompt when the hook output's {@code decision} is
 *                               {@code "block"}, omit the original prompt from
 *                               the block message
 */
public record UserPromptSubmitHookSpecificOutput(
        @JsonProperty("additionalContext") @Nullable String additionalContext,
        @JsonProperty("sessionTitle") @Nullable String sessionTitle,
        @JsonProperty("suppressOriginalPrompt") @Nullable Boolean suppressOriginalPrompt)
        implements HookSpecificOutput {

    /**
     * Backwards-compatible constructor without {@code sessionTitle} and
     * {@code suppressOriginalPrompt}.
     */
    public UserPromptSubmitHookSpecificOutput(@Nullable String additionalContext) {
        this(additionalContext, null, null);
    }

    @JsonProperty("hookEventName")
    @Override
    public String hookEventName() {
        return HookEvent.USER_PROMPT_SUBMIT.getValue();
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();
        result.put(HOOK_EVENT_NAME, hookEventName());
        if (additionalContext != null) {
            result.put(ADDITIONAL_CONTEXT, additionalContext);
        }
        if (sessionTitle != null) {
            result.put(SESSION_TITLE, sessionTitle);
        }
        if (suppressOriginalPrompt != null) {
            result.put(SUPPRESS_ORIGINAL_PROMPT, suppressOriginalPrompt);
        }
        return result;
    }

}
