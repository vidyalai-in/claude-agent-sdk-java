package examples;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.types.message.ConversationResetMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.MessageOrigin;
import in.vidyalai.claude.sdk.types.message.MessageOriginKind;
import in.vidyalai.claude.sdk.types.message.ResultMessage;

/**
 * Example demonstrating message provenance and conversation resets.
 *
 * <p>
 * In streaming-input mode one connection interleaves the turns your application
 * sends with turns the session injects on its own: background-task
 * notifications, fired scheduled-task prompts, MCP channel messages, messages
 * relayed from peer sessions. {@link MessageOrigin} — exposed as
 * {@code origin()} on {@link in.vidyalai.claude.sdk.types.message.UserMessage}
 * and {@link ResultMessage} — tells them apart, so you can decide whether a
 * result answers <i>your</i> prompt:
 * </p>
 *
 * <pre>{@code
 * MessageOrigin origin = result.origin();
 * if (origin == null || origin.isHuman()) {
 *     // a turn this application submitted
 * } else if (origin.kind() == MessageOriginKind.TASK_NOTIFICATION) {
 *     // follow-up turn driven by a background task
 * }
 * }</pre>
 *
 * <p>
 * This example streams three turns through one connection:
 * </p>
 * <ol>
 * <li>a user message stamped {@code "origin": {"kind": "human"}} — the CLI
 * echoes that attribution back on the turn's result (requires Claude Code
 * &gt;= 2.1.210; only the {@code human} kind is honored from an SDK host);</li>
 * <li>an unstamped user message — its result carries a null {@code origin()},
 * because the CLI did not attribute the turn. This is what prompts sent through
 * {@code query()} and {@code sendMessage()} look like;</li>
 * <li>{@code /clear}, which discards the transcript mid-session and makes the
 * CLI emit a {@link ConversationResetMessage}. A reset zeroes the running
 * totals reported on subsequent results, so snapshot them when it arrives —
 * and note that every message after it carries a <i>new</i> session id.</li>
 * </ol>
 *
 * <p>
 * Stamping {@code origin} means sending the raw message map, so turns 1 and 2
 * go through {@link ClaudeSDKClient#query(java.util.Iterator)} rather than
 * {@link ClaudeSDKClient#query(String)}. Both overloads leave the CLI's stdin
 * open, so they can be mixed freely across a session — as turn 3 does.
 * </p>
 */
public class MessageOriginExample {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Message Origin Example ===\n");

        ClaudeAgentOptions options = ClaudeAgentOptions.builder().maxTurns(1).build();

        try (ClaudeSDKClient client = ClaudeSDK.createClient(options)) {
            client.connect();

            // 1. A prompt this application explicitly attributes to a human.
            client.query(List.of(
                    userMessage("Reply with exactly: one", Map.of("kind", "human"))).iterator());
            ResultMessage stamped = drain(client, "stamped prompt");

            // 2. The same thing unstamped — the CLI does not attribute it.
            client.query(List.of(
                    userMessage("Reply with exactly: two", null)).iterator());
            ResultMessage unstamped = drain(client, "unstamped prompt");

            // 3. /clear discards the transcript without ending the connection.
            client.query("/clear");
            ResultMessage afterClear = drain(client, "/clear");

            System.out.println();
            if ((stamped != null) && (unstamped != null)) {
                System.out.printf("stamped result origin:   %s%n", format(stamped.origin()));
                System.out.printf("unstamped result origin: %s%n", format(unstamped.origin()));
            }
            if ((stamped != null) && (afterClear != null)) {
                System.out.printf("session before /clear: %s%n", stamped.sessionId());
                System.out.printf("session after  /clear: %s  (expected to differ)%n",
                        afterClear.sessionId());
            }
        }
    }

    /**
     * Drains one turn, reporting the origin of each result and reacting to a
     * conversation reset. Returns the turn's {@link ResultMessage}, or null if
     * the stream ended before one arrived.
     */
    private static ResultMessage drain(ClaudeSDKClient client, String label) {
        ResultMessage result = null;
        for (Message message : client.receiveResponse()) {
            switch (message) {
                case ConversationResetMessage reset -> {
                    // The conversation was replaced. Snapshot any totals you
                    // accumulate across the session: the CLI's counters restart
                    // from zero, and subsequent messages carry a new session id.
                    System.out.printf("[%s] conversation reset: session %s -> new conversation %s%n",
                            label, reset.sessionId(), reset.newConversationId());
                }
                case ResultMessage r -> {
                    result = r;
                    System.out.printf("[%s] result: %s%n", label, format(r.origin()));
                }
                default -> {
                    // Other message types aren't relevant to this example.
                }
            }
        }
        return result;
    }

    /** Renders an origin the way a consumer would branch on it. */
    private static String format(MessageOrigin origin) {
        if (origin == null) {
            return "unattributed (origin is null) — a turn this application submitted";
        }
        if (origin.isHuman()) {
            return "kind=human — a turn this application submitted";
        }
        if (origin.kind() == MessageOriginKind.TASK_NOTIFICATION) {
            return "kind=task-notification, subkind=" + origin.subkind()
                    + " — injected by a background task";
        }
        // kind() is null for a kind newer than this SDK version models; the
        // wire string is still readable, and it never counts as human.
        return "kind=" + origin.kindValue() + " — an injected turn";
    }

    /**
     * A streaming-input user message. {@code origin} is stamped only when
     * supplied; the CLI honors just the {@code human} kind from an SDK host.
     */
    private static Map<String, Object> userMessage(String text, Map<String, Object> origin) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "user");
        message.put("message", Map.of("role", "user", "content", text));
        message.put("parent_tool_use_id", null);
        message.put("session_id", "default");
        if (origin != null) {
            message.put("origin", origin);
        }
        return message;
    }

}
