package in.vidyalai.claude.sdk.types.message;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * System message emitted while a task is in progress.
 *
 * <p>
 * Subclass of SystemMessage: existing {@code instanceof} checks and pattern matching
 * on {@code SystemMessage} continue to match. The base {@code subtype} and {@code data}
 * fields remain populated with the raw payload.
 *
 * @param subtype      always "task_progress"
 * @param data         raw message data
 * @param taskId       unique task identifier
 * @param description  human-readable task description
 * @param usage        usage statistics for the task
 * @param uuid         message UUID
 * @param sessionId    session identifier
 * @param toolUseId    tool use ID (may be null)
 * @param lastToolName name of the last tool used (may be null)
 */
public record TaskProgressMessage(
        @JsonProperty("subtype") String subtype,
        @JsonProperty("data") Map<String, Object> data,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("description") String description,
        @JsonProperty("usage") TaskUsage usage,
        @JsonProperty("uuid") String uuid,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("tool_use_id") @Nullable String toolUseId,
        @JsonProperty("last_tool_name") @Nullable String lastToolName) implements Message {

    @Override
    public String type() {
        return "system";
    }

    /**
     * Gets a value from the data map.
     *
     * @param key the key to look up
     * @param <T> the expected type
     * @return the value, or null if not present
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) data.get(key);
    }

}
