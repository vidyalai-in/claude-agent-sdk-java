package examples;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.ContentBlock;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.TextBlock;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;

/**
 * Example demonstrating dynamic control features during a conversation.
 *
 * <p>
 * Dynamic control allows you to modify conversation parameters mid-session
 * without restarting the client. This is useful for:
 * </p>
 * <ul>
 * <li>Switching between different Claude models based on task complexity</li>
 * <li>Adjusting permission modes as trust level changes</li>
 * <li>Interrupting long-running operations</li>
 * <li>Adapting to changing requirements during multi-turn conversations</li>
 * </ul>
 *
 * <p>
 * This example demonstrates three dynamic control methods:
 * </p>
 * <ol>
 * <li><b>setPermissionMode()</b> - Change permission mode during
 * conversation</li>
 * <li><b>setModel()</b> - Switch between Claude models mid-session</li>
 * <li><b>interrupt()</b> - Cancel or stop ongoing operations</li>
 * </ol>
 *
 * @see ClaudeSDKClient#setPermissionMode(PermissionMode)
 * @see ClaudeSDKClient#setModel(String)
 * @see ClaudeSDKClient#interrupt()
 */
public class DynamicControlExample {

    public static void main(String[] args) {
        System.out.println("=== Dynamic Control Examples ===\n");

        // Example 1: Dynamic permission mode changes
        System.out.println("1. Dynamic Permission Mode Control");
        dynamicPermissionMode();

        // Example 2: Dynamic model switching
        System.out.println("\n2. Dynamic Model Switching");
        dynamicModelSwitching();

        // Example 3: Interrupt control
        System.out.println("\n3. Interrupt Control");
        interruptExample();

        System.out.println("\n=== All Examples Complete ===");
    }

    /**
     * Example 1: Dynamically change permission mode during a conversation.
     *
     * <p>
     * Demonstrates switching from restrictive to permissive mode and back, allowing
     * fine-grained control over tool execution permissions.
     * </p>
     */
    static void dynamicPermissionMode() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .permissionMode(PermissionMode.DEFAULT)
                .maxTurns(10)
                .build();

        try (ClaudeSDKClient client = new ClaudeSDKClient(options)) {
            client.connect();
            System.out.println("   Connected with permission mode: DEFAULT");

            // First query with default permissions
            System.out.println("\n   → Query 1: 'What is 2+2?' (DEFAULT mode)");
            client.query("What is 2+2? Just respond with the number.");

            for (Message msg : (Iterable<Message>) client.receiveResponse()::iterator) {
                if (msg instanceof AssistantMessage assistant) {
                    String response = extractText(assistant);
                    System.out.println("   ← Response: " + response);
                } else if (msg instanceof ResultMessage result) {
                    System.out.println("   Status: " + result.subtype() + " (Cost: $" + result.totalCostUsd() + ")");
                }
            }

            // Change to ACCEPT_EDITS mode
            System.out.println("\n   ⚙ Changing permission mode to ACCEPT_EDITS...");
            client.setPermissionMode(PermissionMode.ACCEPT_EDITS);
            System.out.println("   ✓ Permission mode changed");

            // Second query with relaxed permissions
            System.out.println("\n   → Query 2: 'What is 3+3?' (ACCEPT_EDITS mode)");
            client.query("What is 3+3? Just respond with the number.");

            for (Message msg : (Iterable<Message>) client.receiveResponse()::iterator) {
                if (msg instanceof AssistantMessage assistant) {
                    String response = extractText(assistant);
                    System.out.println("   ← Response: " + response);
                } else if (msg instanceof ResultMessage result) {
                    System.out.println("   Status: " + result.subtype() + " (Cost: $" + result.totalCostUsd() + ")");
                }
            }

            // Change back to DEFAULT mode
            System.out.println("\n   ⚙ Changing permission mode back to DEFAULT...");
            client.setPermissionMode(PermissionMode.DEFAULT);
            System.out.println("   ✓ Permission mode changed");

            // Third query back to default permissions
            System.out.println("\n   → Query 3: 'What is 5+5?' (DEFAULT mode)");
            client.query("What is 5+5? Just respond with the number.");

            for (Message msg : (Iterable<Message>) client.receiveResponse()::iterator) {
                if (msg instanceof AssistantMessage assistant) {
                    String response = extractText(assistant);
                    System.out.println("   ← Response: " + response);
                } else if (msg instanceof ResultMessage result) {
                    System.out.println("   Status: " + result.subtype() + " (Cost: $" + result.totalCostUsd() + ")");
                }
            }

            System.out.println("\n   ✓ Permission mode switching successful!");

        } catch (Exception e) {
            System.err.println("   ✗ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Example 2: Dynamically switch between Claude models during a conversation.
     *
     * <p>
     * Demonstrates switching from default model to Haiku (faster, cheaper) and
     * back,
     * allowing optimization based on task complexity.
     * </p>
     */
    static void dynamicModelSwitching() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .maxTurns(10)
                .build();

        try (ClaudeSDKClient client = new ClaudeSDKClient(options)) {
            client.connect();
            System.out.println("   Connected with default model");

            // First query with default model
            System.out.println("\n   → Query 1: 'What is 1+1?' (default model)");
            client.query("What is 1+1? Just the number.");

            for (Message msg : (Iterable<Message>) client.receiveResponse()::iterator) {
                if (msg instanceof AssistantMessage assistant) {
                    String response = extractText(assistant);
                    System.out.println("   ← Default model response: " + response);
                } else if (msg instanceof ResultMessage result) {
                    System.out.println("   Status: " + result.subtype() + " (Cost: $" + result.totalCostUsd() + ")");
                }
            }

            // Switch to Haiku model (faster, cheaper)
            System.out.println("\n   ⚙ Switching to Haiku model (claude-haiku-4-5)...");
            client.setModel("claude-haiku-4-5");
            System.out.println("   ✓ Model switched to Haiku");

            // Second query with Haiku
            System.out.println("\n   → Query 2: 'What is 2+2?' (Haiku model)");
            client.query("What is 2+2? Just the number.");

            for (Message msg : (Iterable<Message>) client.receiveResponse()::iterator) {
                if (msg instanceof AssistantMessage assistant) {
                    String response = extractText(assistant);
                    System.out.println("   ← Haiku model response: " + response);
                } else if (msg instanceof ResultMessage result) {
                    System.out.println("   Status: " + result.subtype() + " (Cost: $" + result.totalCostUsd() + ")");
                }
            }

            // Switch back to default model
            System.out.println("\n   ⚙ Switching back to default model...");
            client.setModel(null); // null means default
            System.out.println("   ✓ Model switched to default");

            // Third query back to default model
            System.out.println("\n   → Query 3: 'What is 3+3?' (default model)");
            client.query("What is 3+3? Just the number.");

            for (Message msg : (Iterable<Message>) client.receiveResponse()::iterator) {
                if (msg instanceof AssistantMessage assistant) {
                    String response = extractText(assistant);
                    System.out.println("   ← Default model response: " + response);
                } else if (msg instanceof ResultMessage result) {
                    System.out.println("   Status: " + result.subtype() + " (Cost: $" + result.totalCostUsd() + ")");
                }
            }

            System.out.println("\n   ✓ Model switching successful!");

        } catch (Exception e) {
            System.err.println("   ✗ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Example 3: Interrupt a running operation.
     *
     * <p>
     * Demonstrates sending an interrupt signal during query execution to cancel or
     * stop long-running operations. Note: interrupt timing is dependent on when the
     * model receives and processes the signal.
     * </p>
     */
    static void interruptExample() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .maxTurns(10)
                .build();

        try (ClaudeSDKClient client = new ClaudeSDKClient(options)) {
            client.connect();
            System.out.println("   Connected");

            // Start a potentially long query
            System.out.println("\n   → Starting query: 'Count from 1 to 100 slowly'");
            client.query("Count from 1 to 100 slowly, one number per line.");

            // Note: In a real scenario, you might send interrupt from another thread
            // or based on some condition. Here we demonstrate the API.
            System.out.println("   ⚙ Sending interrupt signal...");

            try {
                client.interrupt();
                System.out.println("   ✓ Interrupt sent successfully");
            } catch (Exception e) {
                System.out.println("   ⚠ Interrupt resulted in: " + e.getMessage());
            }

            // Consume any remaining messages
            System.out.println("\n   Processing messages after interrupt:");
            int messageCount = 0;
            for (Message msg : (Iterable<Message>) client.receiveResponse()::iterator) {
                messageCount++;
                if (msg instanceof AssistantMessage assistant) {
                    String response = extractText(assistant);
                    System.out.println("   ← Response: " + truncate(response, 100));
                } else if (msg instanceof ResultMessage result) {
                    System.out.println("   Status: " + result.subtype() + " (Cost: $" + result.totalCostUsd() + ")");
                    if (result.isError()) {
                        System.out.println("   Error: " + result.result());
                    }
                }
            }

            System.out.println("   Messages received after interrupt: " + messageCount);
            System.out.println("\n   ✓ Interrupt example complete!");

        } catch (Exception e) {
            System.err.println("   ✗ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Extract text content from an assistant message.
     */
    private static String extractText(AssistantMessage message) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.content()) {
            if (block instanceof TextBlock textBlock) {
                text.append(textBlock.text());
            }
        }
        return text.toString().trim();
    }

    /**
     * Truncate text to specified length.
     */
    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

}
