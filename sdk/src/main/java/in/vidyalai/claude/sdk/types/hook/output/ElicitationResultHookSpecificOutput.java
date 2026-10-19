package in.vidyalai.claude.sdk.types.hook.output;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Hook-specific output for ElicitationResult events.
 *
 * <p>
 * Return this to override the action or content before the response is sent to
 * the MCP server.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code camelCase} for JSON
 * field names because it represents data <b>sent to the CLI</b> in hook
 * responses.
 * See {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param action  overriding action
 * @param content overriding form field values
 */
public record ElicitationResultHookSpecificOutput(
        @JsonProperty("action") @Nullable ElicitationAction action,
        @JsonProperty("content") @Nullable Map<String, Object> content) implements HookSpecificOutput {

    @JsonProperty("hookEventName")
    @Override
    public String hookEventName() {
        return "ElicitationResult";
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
