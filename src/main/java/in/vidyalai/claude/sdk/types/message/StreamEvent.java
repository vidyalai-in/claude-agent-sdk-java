package in.vidyalai.claude.sdk.types.message;

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
 * @param event           the raw Anthropic API stream event data
 * @param parentToolUseId if within a tool use context, the tool use ID
 */
public record StreamEvent(
        @JsonProperty("uuid") String uuid,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("event") Map<String, Object> event,
        @JsonProperty("parent_tool_use_id") @Nullable String parentToolUseId) implements Message {

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
        return ((event.get("type") instanceof String s) ? s : null);
    }

}
