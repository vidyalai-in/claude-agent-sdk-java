package in.vidyalai.claude.sdk.types.message;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Stream event for partial message updates during streaming.
 *
 * <p>
 * These events are emitted when {@code includePartialMessages} is enabled
 * and contain raw Anthropic API stream events.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code snake_case} for JSON
 * field names because it represents data <b>received from the CLI</b>. See
 * {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param uuid            unique identifier for the event
 * @param sessionId       the session identifier
 * @param event           the raw Anthropic API stream event data; null only
 *                        when the CLI sent {@code event} as an explicit null
 *                        or a non-object
 * @param parentToolUseId  if within a tool use context, the tool use ID
 * @param userMessageUuid  {@code uuid} of the user message this turn is
 *                         answering — see {@link ResultMessage#userMessageUuid()}.
 *                         Set on the first top-level non-ping event after that
 *                         value is set or changes, which can be partway through
 *                         a turn; null on all others.
 * @param userMessageUuids {@code uuid} of every user message this turn has
 *                         taken in so far. Set on the same events as
 *                         {@code userMessageUuid}, except on older CLIs. A
 *                         message taken in later may be listed only on the
 *                         result — see {@link ResultMessage#userMessageUuids()}.
 */
public record StreamEvent(
        @JsonProperty("uuid") String uuid,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("event") Map<String, Object> event,
        @JsonProperty("parent_tool_use_id") @Nullable String parentToolUseId,
        @JsonProperty("user_message_uuid") @Nullable String userMessageUuid,
        @JsonProperty("user_message_uuids") @Nullable List<String> userMessageUuids) implements Message {

    /**
     * Backwards-compatible constructor without {@code userMessageUuid} and
     * {@code userMessageUuids}.
     */
    public StreamEvent(String uuid, String sessionId, Map<String, Object> event,
            @Nullable String parentToolUseId) {
        this(uuid, sessionId, event, parentToolUseId, null, null);
    }

    @Override
    public String type() {
        return "stream_event";
    }

    /**
     * Gets the event type from the raw event data.
     *
     * @return the event type string, or null if not present
     */
    @Nullable
    public String eventType() {
        return ((event != null) && (event.get("type") instanceof String s) ? s : null);
    }

}
