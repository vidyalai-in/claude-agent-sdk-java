package examples;

import java.util.List;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;

/**
 * Quick start for the Claude Agent SDK for Java. It shows three one-shot query calls:
 *
 * <ol>
 *   <li>{@code ClaudeSDK.query(String)} with default options: {@code simpleQuery()}.</li>
 *   <li>{@code ClaudeSDK.query(String, ClaudeAgentOptions)}: {@code queryWithOptions()}.</li>
 *   <li>{@code ClaudeSDK.queryForText(String, ClaudeAgentOptions)}, which returns only the
 *       text: {@code queryForText()}.</li>
 * </ol>
 *
 * <p>The examples print each message with {@code +}, so the output is the full record, not just
 * its text or cost. To print only the reply text, call {@code AssistantMessage.getTextContent()}.
 * To print only the cost, call {@code ResultMessage.totalCostUsd()}.
 *
 * <h2>Prerequisites</h2>
 * <ul>
 *   <li>JDK 17 or newer.</li>
 *   <li>The Claude Code CLI, installed and authenticated. The SDK runs it as a subprocess.</li>
 * </ul>
 *
 * <h2>Running</h2>
 * <p>From the repository root:
 * <pre>{@code
 * mvn exec:java -Dexec.mainClass="examples.QuickStart" -pl examples
 * }</pre>
 *
 * <h2>Next steps</h2>
 * <ul>
 *   <li>{@code ErrorHandling.java}: handling SDK exceptions.</li>
 *   <li>{@code MultiTurnConversation.java}: interactive, multi-turn sessions.</li>
 * </ul>
 */
public class QuickStart {

    // Pinned to a small, inexpensive model to keep the example cheap. Any model alias or
    // ID that the installed Claude Code CLI accepts works here.
    private static final String MODEL = "claude-haiku-4-5";

    /**
     * Runs the three examples in order. Each call is independent, so no conversation
     * state carries over from one example to the next.
     *
     * @param args not used
     */
    public static void main(String[] args) {
        // Example 1: Simple one-shot query
        System.out.println("=== Simple Query ===");
        simpleQuery();

        // Example 2: Query with options
        System.out.println("\n=== Query with Options ===");
        queryWithOptions();

        // Example 3: Convenience method for text
        System.out.println("\n=== Query for Text ===");
        queryForText();
    }

    /**
     * Simple one-shot query with default options, via {@code ClaudeSDK.query(String)}.
     *
     * <p>The call blocks until the run finishes and returns every message it produced. This
     * method does not pin a model. A run that ends in an error throws {@code QueryFailedException}
     * instead of returning, so there is no error check here.
     */
    static void simpleQuery() {
        // Send one prompt and collect all messages from the run
        List<Message> messages = ClaudeSDK.query("What is 2 + 2?");

        for (Message msg : messages) {
            if (msg instanceof AssistantMessage assistant) {
                // Claude's reply for this turn
                System.out.println("Claude says: " + assistant);
            } else if (msg instanceof ResultMessage result) {
                // End-of-run record, which includes the cost in totalCostUsd()
                System.out.println("Cost: $" + result);
            }
        }
    }

    /**
     * Query with custom options, via {@code ClaudeSDK.query(String, ClaudeAgentOptions)}.
     *
     * <p>The options used here:
     * <ul>
     *   <li>{@code systemPrompt}: instructions that shape Claude's behavior for this run.</li>
     *   <li>{@code maxTurns(1)}: caps the run at one turn. If Claude tries to use a tool, the
     *       run may end with an error result, which {@code query} surfaces as
     *       {@code QueryFailedException}.</li>
     *   <li>{@code model}: {@code MODEL}, pinned to keep the example cheap.</li>
     * </ul>
     */
    static void queryWithOptions() {
        // Build the options once; any field not set here keeps its default
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .systemPrompt("You are a helpful assistant. Be concise.")
                .maxTurns(1)
                .model(MODEL)
                .build();

        // Run the prompt with these options
        List<Message> messages = ClaudeSDK.query("Tell me a short joke", options);

        for (Message msg : messages) {
            // Print only the assistant's replies; skip the other message types
            if (msg instanceof AssistantMessage assistant) {
                System.out.println("Joke: " + assistant);
            }
        }
    }

    /**
     * Gets only the text reply, via {@code ClaudeSDK.queryForText(String, ClaudeAgentOptions)}.
     *
     * <p>Returns the text of all assistant messages, joined with {@code "\n"}. It does not return
     * the {@code ResultMessage}, so the cost is not available here. Use
     * {@code ClaudeSDK.query(...)} for the full message list, or
     * {@code ClaudeSDK.queryForResult(...)} for the final {@code ResultMessage} (or null if there
     * is none).
     */
    static void queryForText() {
        // The method returns the text directly, so there is no message loop
        String response = ClaudeSDK.queryForText(
                "What is the capital of France?",
                ClaudeAgentOptions.builder().maxTurns(1).model(MODEL).build());
        System.out.println("Answer: " + response);
    }

}
