package in.vidyalai.claude.sdk.types.hook.output;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Hook-specific output for Elicitation events.
 *
 * <p>
 * Return this to accept or decline an MCP elicitation request programmatically.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code camelCase} for JSON
 * field names because it represents data <b>sent to the CLI</b> in hook
 * responses.
 * See {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param action  how to answer the elicitation request
 * @param content form field values to submit. Only used when {@code action} is
 *                {@link ElicitationAction#ACCEPT}
 */
public record ElicitationHookSpecificOutput(
        @JsonProperty("action") @Nullable ElicitationAction action,
        @JsonProperty("content") @Nullable Map<String, Object> content) implements HookSpecificOutput {

    @JsonProperty("hookEventName")
    @Override
    public String hookEventName() {
        return "Elicitation";
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();
        result.put(HOOK_EVENT_NAME, hookEventName());
        if (action != null) {
            result.put(ACTION, action.getValue());
        }
        if (content != null) {
            result.put(CONTENT, content);
        }
        return result;
    }

}
