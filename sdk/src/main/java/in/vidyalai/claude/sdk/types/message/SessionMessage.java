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
 * @param parentToolUseId for messages returned by
 *                        {@code ClaudeSDK.getSubagentMessages()} /
 *                        {@code ClaudeSDK.getSubagentMessagesFromStore()}, the
 *                        id of the Agent {@code tool_use} block in the parent
 *                        session that spawned the subagent (recovered from the
 *                        subagent's metadata; {@code null} if that metadata is
 *                        unavailable). Always {@code null} for top-level
 *                        {@code getSessionMessages()} /
 *                        {@code getSessionMessagesFromStore()} results, whose
 *                        tool-use sidechain messages are filtered out
 * @param parentAgentId   for subagent messages, the agent id of the subagent
 *                        that spawned this subagent, or {@code null} if it was
 *                        spawned by the main session (or the metadata is
 *                        unavailable). Always {@code null} for top-level
 *                        session messages
 */
public record SessionMessage(
        @JsonProperty("type") String type,
        @JsonProperty("uuid") String uuid,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("message") Object message,
        @JsonProperty("parent_tool_use_id") @Nullable String parentToolUseId,
        @JsonProperty("parent_agent_id") @Nullable String parentAgentId) {

    /**
     * Backwards-compatible constructor without {@code parentAgentId}.
     *
     * @param type            message type
     * @param uuid            unique message identifier
     * @param sessionId       ID of the session this message belongs to
     * @param message         raw Anthropic API message
     * @param parentToolUseId spawning Agent {@code tool_use} id, or null
     */
    public SessionMessage(
            String type,
            String uuid,
            String sessionId,
            Object message,
            @Nullable String parentToolUseId) {
        this(type, uuid, sessionId, message, parentToolUseId, null);
    }
}
