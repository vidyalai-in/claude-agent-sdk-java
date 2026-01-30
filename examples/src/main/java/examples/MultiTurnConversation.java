package examples;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;

/**
 * Example showing multi-turn conversations with ClaudeSDKClient.
 */
public class MultiTurnConversation {

    public static void main(String[] args) {
        // Example 1: Basic multi-turn conversation
        System.out.println("=== Basic Multi-Turn ===");
        basicMultiTurn();

        // Example 2: Using try-with-resources
        System.out.println("\n=== Try-With-Resources ===");
        tryWithResources();

        // Example 3: Continue previous conversation
        System.out.println("\n=== Continue Conversation ===");
        continueConversation();

        // Example 4: Dynamic model switching
        System.out.println("\n=== Model Switching ===");
        modelSwitching();
    }

    /**
     * Basic multi-turn conversation with manual connection management.
     */
    static void basicMultiTurn() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .maxTurns(10)
                .build();
        ClaudeSDKClient client = new ClaudeSDKClient(options);
        try {
            // Connect to Claude
            client.connect();

            // First turn
            String message = "Hello! I'm learning about Java.";
            System.out.println("--- Turn 1 ---: " + message);
            client.sendMessage(message);
            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage assistant) {
                    System.out.println("Claude: " + assistant.getTextContent());
                }
            }

            // Second turn
            message = "What are some best practices for error handling?";
            System.out.println("\n--- Turn 2 ---: " + message);
            client.sendMessage(message);
            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage assistant) {
                    System.out.println("Claude: " + assistant.getTextContent());
                }
            }

            // Third turn
            message = "Can you give me a code example?";
            System.out.println("\n--- Turn 3 ---: " + message);
            client.sendMessage(message);
            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage assistant) {
                    System.out.println("Claude: " + assistant.getTextContent());
                }
            }
        } finally {
            client.close();
        }
    }

    /**
     * Using try-with-resources for automatic cleanup.
     */
    static void tryWithResources() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .maxTurns(10)
                .build();
        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();

            String message = "What is functional programming?";
            System.out.println("--- Turn 1 ---: " + message);
            client.sendMessage(message);
            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage assistant) {
                    System.out.println("Claude: " + truncate(assistant.getTextContent(), 200));
                }
            }

            message = "How does it differ from OOP?";
            System.out.println("\n--- Turn 2 ---: " + message);
            client.sendMessage(message);
            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage assistant) {
                    System.out.println("Claude: " + truncate(assistant.getTextContent(), 200));
                }
            }
        }
        // Client is automatically closed here
    }

    /**
     * Continue a previous conversation by session ID.
     */
    static void continueConversation() {
        String sessionId = null;

        // First session - capture session ID
        ClaudeAgentOptions firstOptions = ClaudeAgentOptions.builder()
                .maxTurns(5)
                .build();
        try (var client = ClaudeSDK.createClient(firstOptions)) {
            client.connect();

            String message = "Remember this: The secret code is 42.";
            System.out.println("--- Turn 1 ---: " + message);
            client.sendMessage(message);
            for (Message msg : client.receiveResponse()) {
                if (msg instanceof ResultMessage result) {
                    sessionId = result.sessionId();
                    System.out.println("Session ID: " + sessionId);
                }
            }
        }

        // Continue the conversation
        if (sessionId != null) {
            ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                    .continueConversation(true)
                    .resume(sessionId)
                    .maxTurns(5)
                    .build();

            try (var client = ClaudeSDK.createClient(options)) {
                client.connect();

                String message = "What was the secret code I told you?";
                System.out.println("\n--- Turn 2 ---: " + message);
                client.sendMessage(message);
                for (Message msg : client.receiveResponse()) {
                    if (msg instanceof AssistantMessage assistant) {
                        System.out.println("Claude remembers: " + assistant.getTextContent());
                    }
                }
            }
        }
    }

    /**
     * Switch models during a conversation.
     */
    static void modelSwitching() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .maxTurns(10)
                .build();
        try (var client = ClaudeSDK.createClient(options)) {
            // Start with default model
            client.connect();

            String message = "Hello! What model are you?";
            System.out.println("--- Turn 1 ---: " + message);
            client.sendMessage(message);
            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage assistant) {
                    System.out.println("Model info: " + assistant.model());
                }
            }

            // Switch to a different model
            client.setModel("claude-sonnet-4-5");

            message = "What model are you now?";
            System.out.println("\n--- Turn 2 ---: " + message);
            client.sendMessage(message);
            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage assistant) {
                    System.out.println("New model: " + assistant.model());
                }
            }
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null)
            return "";
        if (text.length() <= maxLength)
            return text;
        return text.substring(0, maxLength) + "...";
    }

}
