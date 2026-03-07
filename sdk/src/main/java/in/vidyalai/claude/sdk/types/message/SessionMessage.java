package in.vidyalai.claude.sdk.types.message;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A user or assistant message from a session transcript.
 *
 * <p>
 * Returned by {@code ClaudeSDK.getSessionMessages()} for reading historical
 * session data. Fields match the SDK wire protocol types (SDKUserMessage /
 * SDKAssistantMessage).
 *
 * @param type            message type — {@code "user"} or {@code "assistant"}
 * @param uuid            unique message identifier
 * @param sessionId       ID of the session this message belongs to
 * @param message         raw Anthropic API message (role, content, etc.)
 * @param parentToolUseId always {@code null} for top-level conversation
 *                        messages (tool-use sidechain messages are filtered
 *                        out)
 */
public record SessionMessage(
        @JsonProperty("type") String type,
        @JsonProperty("uuid") String uuid,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("message") Object message,
        @JsonProperty("parent_tool_use_id") @Nullable String parentToolUseId) {
}
