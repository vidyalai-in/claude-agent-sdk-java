package examples;

import java.util.Map;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.types.message.Message;

/**
 * Example demonstrating the includePartialMessages option for streaming partial
 * messages.
 *
 * This feature allows you to receive stream events that contain incremental
 * updates as Claude generates responses. This is useful for:
 * - Building real-time UIs that show text as it's being generated
 * - Monitoring tool use progress
 * - Getting early results before the full response is complete
 *
 * Note: Partial message streaming requires the CLI to support it, and the
 * messages will include StreamEvent messages interspersed with regular
 * messages.
 *
 * Usage:
 * mvn exec:java -Dexec.mainClass="examples.IncludePartialMessagesExample" -pl examples
 */
public class IncludePartialMessagesExample {

    public static void main(String[] args) {
        System.out.println("Partial Message Streaming Example");
        System.out.println("=".repeat(50));

        // Enable partial message streaming
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .includePartialMessages(true)
                .model("claude-sonnet-4-5")
                .maxTurns(2)
                .env(Map.of("MAX_THINKING_TOKENS", "8000"))
                .build();

        try (ClaudeSDKClient client = new ClaudeSDKClient(options)) {
            client.connect();

            // Send a prompt that will generate a streaming response
            String prompt = "Think of three jokes, then tell one";
            System.out.println("Prompt: " + prompt);
            System.out.println();
            System.out.println("=".repeat(50));

            client.query(prompt);

            for (Message message : client.receiveResponse()) {
                System.out.println(message);
            }
        }
    }

}
