package in.vidyalai.claude.sdk.types.hook.output;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

import in.vidyalai.claude.sdk.types.hook.HookEvent;

/**
 * Hook-specific output for Stop events.
 *
 * <p>
 * {@code additionalContext} is non-error feedback delivered to the model; the
 * conversation continues so the model can act on it.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code camelCase} for JSON
 * field names because it represents data <b>sent to the CLI</b> in hook
 * responses.
 * See {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param additionalContext non-error feedback delivered to the model
 */
public record StopHookSpecificOutput(
        @JsonProperty("additionalContext") @Nullable String additionalContext) implements HookSpecificOutput {

    @JsonProperty("hookEventName")
    @Override
    public String hookEventName() {
        return HookEvent.STOP.getValue();
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();
        result.put(HOOK_EVENT_NAME, hookEventName());
        if (additionalContext != null) {
            result.put(ADDITIONAL_CONTEXT, additionalContext);
        }
        return result;
    }

}
