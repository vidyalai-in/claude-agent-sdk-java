package in.vidyalai.claude.sdk.types.message;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Provenance of a user-role message, and — on a {@link ResultMessage} — of the
 * message that triggered that turn.
 *
 * <p>
 * In streaming input mode a single connection interleaves the turns you send
 * with turns the session injects on its own (background-task notifications,
 * scheduled-task prompts, MCP channel messages, messages relayed from peer
 * sessions, ...). {@code origin} tells them apart, e.g. to decide whether a
 * {@link ResultMessage} answers <i>your</i> prompt:
 *
 * <pre>{@code
 * MessageOrigin origin = result.origin();
 * if (origin == null || origin.kind() == MessageOriginKind.HUMAN) {
 *     // a turn this application submitted
 * } else if (origin.kind() == MessageOriginKind.TASK_NOTIFICATION) {
 *     // follow-up turn driven by a background task
 * }
 * }</pre>
 *
 * <p>
 * Only {@code kind} is always present; the remaining fields depend on the kind
 * as noted on each one, and are null otherwise. A null origin means the CLI did
 * not attribute the message: prompts you send through
 * {@link in.vidyalai.claude.sdk.ClaudeSDK#query(String)} or
 * {@link in.vidyalai.claude.sdk.ClaudeSDKClient#query(String)} arrive that way
 * unless you stamp {@code "origin": {"kind": "human"}} on the message map
 * yourself (only the {@code human} kind is honored from an SDK host). The full
 * CLI object is retained on {@link #raw()}, so keys this SDK version does not
 * model stay reachable.
 *
 * <p>
 * <b>JSON Naming Convention:</b> Unusually for CLI-received data, this type
 * uses {@code camelCase} JSON field names. The CLI passes the {@code origin}
 * object through verbatim, so the keys match the TypeScript SDK's
 * {@code SDKMessageOrigin} shape rather than the {@code snake_case} used
 * elsewhere on the message frame. See {@link in.vidyalai.claude.sdk.types}
 * package documentation for the general convention.
 *
 * @param kind            the discriminator, or null when the CLI emitted a
 *                        kind this SDK version does not model — read
 *                        {@link #kindValue()} for the wire string and treat it
 *                        as "not human"
 * @param kindValue       the verbatim wire value of {@code kind}, never null
 * @param server          {@code kind == CHANNEL}: name of the MCP server the
 *                        message arrived on
 * @param from            {@code kind == PEER} / {@code OBSERVER}: sender
 *                        address. Sender-asserted — use it for reply routing
 *                        and display, never as proof of identity.
 * @param name            {@code kind == PEER}: sender display name, already
 *                        normalized by the CLI (control characters stripped,
 *                        trimmed, length-capped)
 * @param fromSession     {@code kind == PEER}: the sender's host-openable
 *                        session id, if its host provided one. A navigation
 *                        target only.
 * @param senderTaskId    {@code kind == PEER} / {@code OBSERVER}: task id of
 *                        the in-process background subagent that sent this
 *                        message. Absent for cross-session peers.
 * @param body            {@code kind == PEER}: decoded message body with the
 *                        peer envelope stripped (byte-exact with what the
 *                        model saw). Render this instead of re-parsing the
 *                        message text.
 * @param verifiedPeerPid {@code kind == PEER}: kernel-verified pid of the
 *                        process that connected to this session's local
 *                        messaging socket (the <i>connecting</i> process — for
 *                        relayed traffic that is the relay). Absent when
 *                        unverifiable.
 * @param subkind         {@code kind == TASK_NOTIFICATION}: present when the
 *                        delivery is the fired prompt of a scheduled task or a
 *                        message sent from another of the user's sessions.
 *                        Absent for ordinary background-task notifications,
 *                        and null for a sub-kind this SDK version does not
 *                        model (read {@code raw().get("subkind")}).
 * @param raw             the full origin object from the CLI, including any
 *                        fields not modeled above
 */
public record MessageOrigin(
        @JsonProperty("kind") @Nullable MessageOriginKind kind,
        String kindValue,
        @JsonProperty("server") @Nullable String server,
        @JsonProperty("from") @Nullable String from,
        @JsonProperty("name") @Nullable String name,
        @JsonProperty("fromSession") @Nullable String fromSession,
        @JsonProperty("senderTaskId") @Nullable String senderTaskId,
        @JsonProperty("body") @Nullable String body,
        @JsonProperty("verifiedPeerPid") @Nullable Integer verifiedPeerPid,
        @JsonProperty("subkind") @Nullable TaskNotificationOriginSubkind subkind,
        Map<String, Object> raw) {

    /**
     * Whether this turn was submitted by the application or an interactive
     * user, as opposed to injected by the session.
     *
     * @return true when {@link #kind()} is {@link MessageOriginKind#HUMAN}
     */
    public boolean isHuman() {
        return kind == MessageOriginKind.HUMAN;
    }

}
