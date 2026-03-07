package in.vidyalai.claude.sdk.types.message;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * System message emitted when a task completes, fails, or is stopped.
 *
 * <p>
 * Subclass of SystemMessage: existing {@code instanceof} checks and pattern matching
 * on {@code SystemMessage} continue to match. The base {@code subtype} and {@code data}
 * fields remain populated with the raw payload.
 *
 * @param subtype    always "task_notification"
 * @param data       raw message data
 * @param taskId     unique task identifier
 * @param status     task completion status
 * @param outputFile path to task output file
 * @param summary    human-readable task summary
 * @param uuid       message UUID
 * @param sessionId  session identifier
 * @param toolUseId  tool use ID (may be null)
 * @param usage      usage statistics (may be null)
 */
public record TaskNotificationMessage(
        @JsonProperty("subtype") String subtype,
        @JsonProperty("data") Map<String, Object> data,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("status") TaskNotificationStatus status,
        @JsonProperty("output_file") String outputFile,
        @JsonProperty("summary") String summary,
        @JsonProperty("uuid") String uuid,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("tool_use_id") @Nullable String toolUseId,
        @JsonProperty("usage") @Nullable TaskUsage usage) implements Message {

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
