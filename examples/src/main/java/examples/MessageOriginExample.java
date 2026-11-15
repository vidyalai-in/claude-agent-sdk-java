package examples;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * and {@link ResultMessage} — tells you what <i>started</i> a turn.
 * </p>
 *
 * <p>
 * It does not tell you whether a result covers <i>your</i> prompt: a prompt
 * sent while an injected turn is running can be answered inside that turn, and
 * the result then carries the injected turn's origin. To match a result to a
 * prompt, send the prompt with a {@code uuid} and look for it on the result:
 * </p>
 *
 * <pre>{@code
 * List<String> uuids = result.userMessageUuids() != null ? result.userMessageUuids() : List.of();
 * if (myUuid.equals(result.userMessageUuid()) || uuids.contains(myUuid)) {
 *     // this turn took in the prompt sent with myUuid
 * }
 * }</pre>
 *
 * <p>
 * This example streams three turns through one connection:
 * </p>
 * <ol>
 * <li>a user message stamped {@code "origin": {"kind": "human"}} and a fresh
 * {@code uuid} — the CLI echoes both back on the turn's result, the uuid as
 * {@link ResultMessage#userMessageUuid()} and in
 * {@link ResultMessage#userMessageUuids()} (requires Claude Code &gt;= 2.1.210
 * for the origin; only the {@code human} kind is honored from an SDK host);</li>
 * <li>an unstamped user message — its result carries a null {@code origin()}
 * and no user-message uuid, because the CLI did not attribute the turn. This is
 * what prompts sent through {@code query()} and {@code sendMessage()} look
 * like;</li>
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

            // 1. A prompt this application explicitly attributes to a human,
            //    sent with a uuid so its result can be found. Use a new uuid for
            //    every message: the CLI can skip one whose uuid it has seen.
            String promptUuid = UUID.randomUUID().toString();
            client.query(List.of(
                    userMessage("Reply with exactly: one", Map.of("kind", "human"), promptUuid)).iterator());
            ResultMessage stamped = drain(client, "stamped prompt");

            // 2. The same thing unstamped — the CLI does not attribute it.
            client.query(List.of(
                    userMessage("Reply with exactly: two", null, null)).iterator());
            ResultMessage unstamped = drain(client, "unstamped prompt");

            // 3. /clear discards the transcript without ending the connection.
            client.query("/clear");
            ResultMessage afterClear = drain(client, "/clear");

            System.out.println();
            if ((stamped != null) && (unstamped != null)) {
                System.out.printf("stamped result origin:   %s%n", format(stamped.origin()));
                System.out.printf("unstamped result origin: %s%n", format(unstamped.origin()));
                System.out.printf("stamped result answers our prompt uuid: %s  (uuids=%s)%n",
                        answers(stamped, promptUuid), stamped.userMessageUuids());
                System.out.printf("unstamped result user message uuid:     %s%n",
                        unstamped.userMessageUuid());
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
            // The SDK targets Java 17, so this uses an instanceof chain. Message
            // is sealed, so on JDK 21+ an exhaustive pattern switch works too.
            if (message instanceof ConversationResetMessage reset) {
                // The conversation was replaced. Snapshot any totals you
                // accumulate across the session: the CLI's counters restart
                // from zero, and subsequent messages carry a new session id.
                System.out.printf("[%s] conversation reset: session %s -> new conversation %s%n",
                        label, reset.sessionId(), reset.newConversationId());
            } else if (message instanceof ResultMessage r) {
                result = r;
                System.out.printf("[%s] result: %s%n", label, format(r.origin()));
            }
            // Other message types aren't relevant to this example.
        }
        return result;
    }

    /**
     * Whether a result took in the prompt sent with {@code uuid}. Check
     * {@code isError()} and {@code terminalReason()} too before treating the
     * prompt as answered: error results carry the uuid as well.
     */
    private static boolean answers(ResultMessage result, String uuid) {
        List<String> uuids = result.userMessageUuids();
        return uuid.equals(result.userMessageUuid()) || ((uuids != null) && uuids.contains(uuid));
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
     * A streaming-input user message. {@code origin} and {@code uuid} are
     * stamped only when supplied; the CLI honors just the {@code human} origin
     * kind from an SDK host, and echoes a top-level {@code uuid} back on the
     * turn's messages as {@code userMessageUuid}.
     */
    private static Map<String, Object> userMessage(String text, Map<String, Object> origin, String uuid) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "user");
        message.put("message", Map.of("role", "user", "content", text));
        message.put("parent_tool_use_id", null);
        message.put("session_id", "default");
        if (origin != null) {
            message.put("origin", origin);
        }
        if (uuid != null) {
            message.put("uuid", uuid);
        }
        return message;
    }

}
