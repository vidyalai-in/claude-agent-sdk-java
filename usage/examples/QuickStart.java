package examples;

import java.util.List;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;

/**
 * Quick start example showing basic usage of the Claude Agent SDK.
 */
public class QuickStart {

    private static final String MODEL = "claude-haiku-4-5";

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
     * Simple one-shot query using default options.
     */
    static void simpleQuery() {
        List<Message> messages = ClaudeSDK.query("What is 2 + 2?");

        for (Message msg : messages) {
            if (msg instanceof AssistantMessage assistant) {
                System.out.println("Claude says: " + assistant);
            } else if (msg instanceof ResultMessage result) {
                System.out.println("Cost: $" + result);
            }
        }
    }

    /**
     * Query with custom options.
     */
    static void queryWithOptions() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .systemPrompt("You are a helpful assistant. Be concise.")
                .maxTurns(1)
                .model(MODEL)
                .build();

        List<Message> messages = ClaudeSDK.query("Tell me a short joke", options);

        for (Message msg : messages) {
            if (msg instanceof AssistantMessage assistant) {
                System.out.println("Joke: " + assistant);
            }
        }
    }

    /**
     * Use convenience method to get just the text response.
     */
    static void queryForText() {
        String response = ClaudeSDK.queryForText(
                "What is the capital of France?",
                ClaudeAgentOptions.builder().maxTurns(1).model(MODEL).build());
        System.out.println("Answer: " + response);
    }

}
