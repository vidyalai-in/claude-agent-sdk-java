package in.vidyalai.claude.sdk.types.hook.output;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Hook-specific output for WorktreeCreate events.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code camelCase} for JSON
 * field names because it represents data <b>sent to the CLI</b> in hook
 * responses.
 * See {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param worktreePath absolute path to the created worktree directory
 *                     (required)
 */
public record WorktreeCreateHookSpecificOutput(
        @JsonProperty("worktreePath") String worktreePath) implements HookSpecificOutput {

    public WorktreeCreateHookSpecificOutput {
        Objects.requireNonNull(worktreePath, "worktreePath");
    }

    @JsonProperty("hookEventName")
    @Override
    public String hookEventName() {
        return "WorktreeCreate";
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();
        result.put(HOOK_EVENT_NAME, hookEventName());
        result.put(WORKTREE_PATH, worktreePath);
        return result;
    }

}
