package in.vidyalai.claude.sdk.types.hook.input;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * In-flight background task, as listed on {@link StopHookInput} and
 * {@link SubagentStopHookInput}.
 *
 * <p>
 * {@code description} and {@code command} are capped at 1000 characters; a
 * clipped value ends with an in-string {@code "… [+N chars]"} marker, so it is
 * not always the original text.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code snake_case} for JSON
 * field names because it represents data <b>received from the CLI</b>. See
 * {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param id          task identifier
 * @param type        task-type label (e.g. {@code "shell"}, {@code "subagent"},
 *                    {@code "monitor"}, {@code "workflow"}); falls back to the
 *                    raw discriminant for unknown types
 * @param status      task status
 * @param description task description
 * @param command     shell command line; only present for {@code shell} tasks
 * @param agentType   subagent type name; only present for {@code subagent}
 *                    tasks
 * @param server      MCP server name; only present for {@code monitor} / MCP
 *                    tasks
 * @param tool        MCP tool name; only present for {@code monitor} / MCP
 *                    tasks
 * @param name        workflow name; only present for {@code workflow} tasks
 */
public record BackgroundTaskSummary(
        @JsonProperty("id") String id,
        @JsonProperty("type") String type,
        @JsonProperty("status") String status,
        @JsonProperty("description") String description,
        @JsonProperty("command") @Nullable String command,
        @JsonProperty("agent_type") @Nullable String agentType,
        @JsonProperty("server") @Nullable String server,
        @JsonProperty("tool") @Nullable String tool,
        @JsonProperty("name") @Nullable String name) {

}
