package in.vidyalai.claude.sdk.types.hook.output;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Hook-specific output for MessageDisplay events.
 *
 * <p>
 * Display-only: replaces the text on screen without changing the stored message
 * or what the model sees.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code camelCase} for JSON
 * field names because it represents data <b>sent to the CLI</b> in hook
 * responses.
 * See {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param displayContent text displayed in place of the delta. Omit it to
 *                       display the original
 */
public record MessageDisplayHookSpecificOutput(
        @JsonProperty("displayContent") @Nullable String displayContent) implements HookSpecificOutput {

    @JsonProperty("hookEventName")
    @Override
    public String hookEventName() {
        return "MessageDisplay";
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();
        result.put(HOOK_EVENT_NAME, hookEventName());
        if (displayContent != null) {
            result.put(DISPLAY_CONTENT, displayContent);
        }
        return result;
    }

}
