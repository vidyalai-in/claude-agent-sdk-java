package in.vidyalai.claude.sdk.types.hook.output;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Hook-specific output for SessionStart events.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code camelCase} for JSON
 * field names because it represents data <b>sent to the CLI</b> in hook
 * responses.
 * See {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param additionalContext  optional additional context for Claude
 * @param initialUserMessage used as the first user message of the session
 * @param sessionTitle       sets the session title. Ignored when the session
 *                           starts from {@code clear} or {@code compact}
 * @param watchPaths         absolute paths to watch for FileChanged events
 *                           during this session
 * @param reloadSkills       re-scan skill and command directories after
 *                           SessionStart hooks complete, so skills the hook
 *                           installed are available in the same session
 */
public record SessionStartHookSpecificOutput(
        @JsonProperty("additionalContext") @Nullable String additionalContext,
        @JsonProperty("initialUserMessage") @Nullable String initialUserMessage,
        @JsonProperty("sessionTitle") @Nullable String sessionTitle,
        @JsonProperty("watchPaths") @Nullable List<String> watchPaths,
        @JsonProperty("reloadSkills") @Nullable Boolean reloadSkills) implements HookSpecificOutput {

    @JsonProperty("hookEventName")
    @Override
    public String hookEventName() {
        return "SessionStart";
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();
        result.put(HOOK_EVENT_NAME, hookEventName());
        if (additionalContext != null) {
            result.put(ADDITIONAL_CONTEXT, additionalContext);
        }
        if (initialUserMessage != null) {
            result.put(INITIAL_USER_MESSAGE, initialUserMessage);
        }
        if (sessionTitle != null) {
            result.put(SESSION_TITLE, sessionTitle);
        }
        if (watchPaths != null) {
            result.put(WATCH_PATHS, watchPaths);
        }
        if (reloadSkills != null) {
            result.put(RELOAD_SKILLS, reloadSkills);
        }
        return result;
    }

}
