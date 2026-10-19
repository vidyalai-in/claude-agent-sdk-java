package in.vidyalai.claude.sdk.types.hook.output;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Hook-specific output for PermissionDenied events.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code camelCase} for JSON
 * field names because it represents data <b>sent to the CLI</b> in hook
 * responses.
 * See {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param retry tell the model it may retry the denied tool call. Ignored for
 *              denials without a classifier verdict
 */
public record PermissionDeniedHookSpecificOutput(
        @JsonProperty("retry") @Nullable Boolean retry) implements HookSpecificOutput {

    @JsonProperty("hookEventName")
    @Override
    public String hookEventName() {
        return "PermissionDenied";
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();
        result.put(HOOK_EVENT_NAME, hookEventName());
        if (retry != null) {
            result.put(RETRY, retry);
        }
        return result;
    }

}
