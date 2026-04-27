package examples;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;

/**
 * Simple example demonstrating stderr callback for capturing CLI debug output.
 * <p>
 * The stderr callback allows you to:
 * <ul>
 * <li>Capture debug output from the CLI</li>
 * <li>Filter and process error messages</li>
 * <li>Log CLI diagnostics for troubleshooting</li>
 * </ul>
 * <p>
 * This is useful for:
 * <ul>
 * <li>Debugging issues with the Claude Code CLI</li>
 * <li>Monitoring CLI behavior</li>
 * <li>Custom logging and diagnostics</li>
 * </ul>
 */
public class StderrCallbackExample {

    public static void main(String[] args) {
        captureStderr();
        advancedStderrHandling();
    }

    /**
     * Capture stderr output from the CLI using a callback.
     */
    static void captureStderr() {
        System.out.println("=== Stderr Callback Example ===\n");

        // Collect stderr messages
        List<String> stderrMessages = new ArrayList<>();

        // Create stderr callback
        Consumer<String> stderrCallback = message -> {
            stderrMessages.add(message);

            // Optionally print specific messages
            if (message.contains("[ERROR]")) {
                System.out.println("Error detected: " + message);
            }
        };

        // Create options with a stderr callback. The callback receives any
        // stderr the CLI emits (warnings, errors). For verbose CLI debug
        // logs, pass {@code extraArgs(Map.of("debug-file", "/path/to/log"))}
        // and read that file instead — the legacy {@code --debug-to-stderr}
        // flag was removed in upstream prep for the CLI flag's deprecation.
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .stderrCallback(stderrCallback)
                .build();

        // Run a query
        System.out.println("Running query with stderr capture...");
        List<Message> messages = ClaudeSDK.query("What is 2+2?", options);

        // Process response
        for (Message msg : messages) {
            if (msg instanceof AssistantMessage assistant) {
                System.out.println("Response: " + assistant.getTextContent());
            }
        }

        // Show what we captured
        System.out.println("\nCaptured " + stderrMessages.size() + " stderr lines");
        if (!stderrMessages.isEmpty()) {
            String firstLine = stderrMessages.get(0);
            String preview = firstLine.length() > 100
                    ? firstLine.substring(0, 100) + "..."
                    : firstLine;
            System.out.println("First stderr line: " + preview);
        }

        System.out.println();
    }

    /**
     * Advanced example: Filter and process stderr messages.
     */
    static void advancedStderrHandling() {
        System.out.println("=== Advanced Stderr Handling ===\n");

        // Custom stderr processor
        Consumer<String> stderrCallback = message -> {
            // Filter by log level
            if (message.contains("[ERROR]")) {
                System.err.println("CLI Error: " + message);
            } else if (message.contains("[WARN]")) {
                System.err.println("CLI Warning: " + message);
            } else if (message.contains("[DEBUG]")) {
                // Only log debug messages if verbose mode is enabled
                if (Boolean.getBoolean("verbose")) {
                    System.out.println("CLI Debug: " + message);
                }
            }
        };

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .stderrCallback(stderrCallback)
                .build();

        List<Message> messages = ClaudeSDK.query("Tell me one line joke", options);

        for (Message msg : messages) {
            if (msg instanceof AssistantMessage assistant) {
                System.out.println("Response: " + assistant.getTextContent());
            }
        }

        System.out.println();
    }

}
