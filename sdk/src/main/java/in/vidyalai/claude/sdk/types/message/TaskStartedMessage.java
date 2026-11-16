package in.vidyalai.claude.sdk.types.message;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * System message emitted when a task starts.
 *
 * <p>
 * <b>Not a {@link SystemMessage} instance.</b> On the wire this is a
 * {@code system} message, and {@link #type()} returns {@code "system"}, but
 * Java records cannot extend one another, so it is a sibling record in the
 * sealed {@link Message} hierarchy: {@code instanceof SystemMessage} is
 * {@code false} for it. (In the Python SDK it subclasses
 * {@code SystemMessage}.) Match it by its own type, or, to handle every
 * system message alike, test {@code "system".equals(message.type())} and read
 * {@code subtype()} and {@code data()}, which hold what a
 * {@code SystemMessage} would: the subtype and the raw payload.
 *
 * @param subtype     always "task_started"
 * @param data        raw message data
 * @param taskId      unique task identifier
 * @param description human-readable task description
 * @param uuid        message UUID
 * @param sessionId   session identifier
 * @param toolUseId   tool use ID (may be null)
 * @param taskType    task type (may be null)
 */
public record TaskStartedMessage(
        @JsonProperty("subtype") String subtype,
        @JsonProperty("data") Map<String, Object> data,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("description") String description,
        @JsonProperty("uuid") String uuid,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("tool_use_id") @Nullable String toolUseId,
        @JsonProperty("task_type") @Nullable String taskType) implements Message {

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
