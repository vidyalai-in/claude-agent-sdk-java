package in.vidyalai.claude.sdk.types.hook.output;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Hook-specific output for FileChanged events.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code camelCase} for JSON
 * field names because it represents data <b>sent to the CLI</b> in hook
 * responses.
 * See {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param watchPaths absolute paths to watch for FileChanged events. Replaces
 *                   the current dynamic watch list
 */
public record FileChangedHookSpecificOutput(
        @JsonProperty("watchPaths") @Nullable List<String> watchPaths) implements HookSpecificOutput {

    @JsonProperty("hookEventName")
    @Override
    public String hookEventName() {
        return "FileChanged";
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();
        result.put(HOOK_EVENT_NAME, hookEventName());
        if (watchPaths != null) {
            result.put(WATCH_PATHS, watchPaths);
        }
        return result;
    }

}
