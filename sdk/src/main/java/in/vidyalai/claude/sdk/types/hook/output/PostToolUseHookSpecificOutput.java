package in.vidyalai.claude.sdk.types.hook.output;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

import in.vidyalai.claude.sdk.types.hook.HookEvent;

/**
 * Hook-specific output for PostToolUse events.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code camelCase} for JSON
 * field names because it represents data <b>sent to the CLI</b> in hook
 * responses.
 * See {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param additionalContext    optional additional context for Claude
 * @param updatedToolOutput    optional replacement for the tool's output before it
 *                             reaches the model. Works for any tool, including
 *                             built-ins (Bash, Read, Edit, ...). For built-in tools
 *                             the value must match the tool's output schema (e.g.
 *                             {@code {"stdout": ..., "stderr": ..., "interrupted": ...}}
 *                             for Bash); a mismatched shape is rejected and the
 *                             original output is kept.
 * @param updatedMCPToolOutput optional replacement for the output of MCP tools only.
 *                             Prefer {@code updatedToolOutput}, which works for all tools.
 */
public record PostToolUseHookSpecificOutput(
        @JsonProperty("additionalContext") @Nullable String additionalContext,
        @JsonProperty("updatedToolOutput") @Nullable Object updatedToolOutput,
        @JsonProperty("updatedMCPToolOutput") @Nullable Object updatedMCPToolOutput) implements HookSpecificOutput {

    /**
     * Backwards-compatible constructor without {@code updatedToolOutput}.
     */
    public PostToolUseHookSpecificOutput(
            @Nullable String additionalContext,
            @Nullable Object updatedMCPToolOutput) {
        this(additionalContext, null, updatedMCPToolOutput);
    }

    @JsonProperty("hookEventName")
    @Override
    public String hookEventName() {
        return HookEvent.POST_TOOL_USE.getValue();
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();
        result.put(HOOK_EVENT_NAME, hookEventName());
        if (additionalContext != null) {
            result.put(ADDITIONAL_CONTEXT, additionalContext);
        }
        if (updatedToolOutput != null) {
            result.put(UPDATED_TOOL_OUTPUT, updatedToolOutput);
        }
        if (updatedMCPToolOutput != null) {
            result.put(UPDATED_MCP_TOOL_OUTPUT, updatedMCPToolOutput);
        }
        return result;
    }

}
