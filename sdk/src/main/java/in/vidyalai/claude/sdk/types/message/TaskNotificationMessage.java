package in.vidyalai.claude.sdk.types.message;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * System message emitted when a task completes, fails, or is stopped.
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
 * @param subtype    always "task_notification"
 * @param data       raw message data
 * @param taskId     unique task identifier
 * @param status     task completion status, or null when the CLI sent a value
 *                   this SDK version does not recognize (or none); the wire
 *                   string is on {@link #statusValue()}
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
        @JsonProperty("status") @Nullable TaskNotificationStatus status,
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
     * The {@code status} string exactly as the CLI sent it.
     *
     * <p>
     * Use it when {@link #status()} is null: the CLI reported a status this SDK
     * version does not model.
     *
     * @return the wire status, or null if it is absent or not a string
     */
    public @Nullable String statusValue() {
        return ((data.get("status") instanceof String s) ? s : null);
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
