package in.vidyalai.claude.sdk.types.message;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Hook event emitted by the CLI when {@code includeHookEvents} is enabled on
 * {@link in.vidyalai.claude.sdk.ClaudeAgentOptions}.
 *
 * <p>The CLI emits hook lifecycle events (PreToolUse, PostToolUse, Stop, etc.)
 * into the message stream. Each event is identified by {@code hookEventName}
 * and the full raw payload is available in {@link #data}.
 *
 * <p>On the wire these arrive as
 * {@code {"type": "system", "subtype": "hook_started" | "hook_response", "hook_event": "PreToolUse", ...}}.
 *
 * <p><b>Subtype values:</b>
 * <ul>
 *   <li>{@code "hook_started"} — when a hook begins executing.</li>
 *   <li>{@code "hook_response"} — when a hook completes; the payload includes
 *       {@code output}, {@code exit_code}, and {@code outcome} keys in
 *       {@link #data}.</li>
 * </ul>
 *
 * <p>This message type carries the same {@code subtype}/{@code data} surface
 * area as {@link SystemMessage}, plus the hook-specific
 * {@link #hookEventName()}, {@link #sessionId()}, and {@link #uuid()} fields.
 *
 * @param subtype       lifecycle phase ({@code "hook_started"} or
 *                      {@code "hook_response"})
 * @param data          full raw event dict from the CLI, including any
 *                      event-specific fields not modeled here
 * @param hookEventName name of the hook event (e.g. {@code "PreToolUse"},
 *                      {@code "PostToolUse"}, {@code "Stop"})
 * @param sessionId     session ID the event belongs to, if present
 * @param uuid          unique ID of the event, if present
 */
public record HookEventMessage(
        @JsonProperty("subtype") String subtype,
        @JsonProperty("data") Map<String, Object> data,
        @JsonProperty("hook_event_name") String hookEventName,
        @JsonProperty("session_id") @Nullable String sessionId,
        @JsonProperty("uuid") @Nullable String uuid) implements Message {

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
