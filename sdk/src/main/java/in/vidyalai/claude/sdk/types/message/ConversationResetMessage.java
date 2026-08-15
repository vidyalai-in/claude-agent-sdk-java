package in.vidyalai.claude.sdk.types.message;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Emitted when the session's conversation is replaced without ending the
 * connection — e.g. after {@code /clear} or any other flow that discards the
 * transcript mid-session.
 *
 * <p>
 * In streaming input mode a single connection can carry many user turns, and a
 * reset clears the conversation history <i>and</i> zeroes the running totals
 * reported on subsequent {@link ResultMessage} objects (e.g.
 * {@link ResultMessage#totalCostUsd()}). If you accumulate those totals across
 * a long-lived session, snapshot them when this message arrives.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code snake_case} for JSON
 * field names because it represents data <b>received from the CLI</b>. See
 * {@link in.vidyalai.claude.sdk.types} package documentation for details.
 *
 * @param newConversationId opaque identifier for the fresh conversation, for
 *                          UIs to key an empty transcript on (and discard any
 *                          cached session title). This is <i>not</i> the
 *                          {@code sessionId} of subsequent messages — read
 *                          that from the next message.
 * @param uuid              unique identifier of this message
 * @param sessionId         ID of the session that was reset (the outgoing
 *                          session; messages after the reset carry a new
 *                          {@code sessionId})
 */
public record ConversationResetMessage(
        @JsonProperty("new_conversation_id") String newConversationId,
        @JsonProperty("uuid") String uuid,
        @JsonProperty("session_id") String sessionId) implements Message {

    @Override
    public String type() {
        return "conversation_reset";
    }

}
