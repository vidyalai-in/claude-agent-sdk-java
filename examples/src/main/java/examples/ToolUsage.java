package examples;

import java.nio.file.Path;
import java.util.List;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.ContentBlock;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.TextBlock;
import in.vidyalai.claude.sdk.types.message.ToolResultBlock;
import in.vidyalai.claude.sdk.types.message.ToolUseBlock;
import in.vidyalai.claude.sdk.types.message.UserMessage;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;

/**
 * Example showing how to use built-in tools with Claude.
 */
public class ToolUsage {

    public static void main(String[] args) {
        // Example 1: Reading files
        System.out.println("=== Reading Files ===");
        readFiles();

        // Example 2: Writing files
        System.out.println("\n=== Writing Files ===");
        writeFiles();

        // Example 3: Running bash commands
        System.out.println("\n=== Bash Commands ===");
        bashCommands();

        // Example 4: Multiple tools
        System.out.println("\n=== Multiple Tools ===");
        multipleTools();
    }

    /**
     * Using the Read tool to read files.
     */
    @SuppressWarnings("null")
    static void readFiles() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .allowedTools(List.of("Read"))
                .permissionMode(PermissionMode.ACCEPT_EDITS)
                .cwd(Path.of(System.getProperty("user.dir")))
                .maxTurns(3)
                .build();

        List<Message> messages = ClaudeSDK.query(
                "Read the pom.xml file and tell me the project name",
                options);

        for (Message msg : messages) {
            switch (msg) {
                case AssistantMessage assistant -> {
                    // Check for tool use
                    for (ContentBlock block : assistant.content()) {
                        if (block instanceof ToolUseBlock toolUse) {
                            System.out.println("Using tool: " + toolUse.name());
                            System.out.println("  Input: " + toolUse.input());
                        } else if (block instanceof TextBlock text) {
                            System.out.println("Claude: " + text.text());
                        }
                    }
                }
                case UserMessage user -> {
                    // Tool results come as user messages
                    for (ContentBlock block : user.contentAsBlocks()) {
                        if (block instanceof ToolResultBlock result) {
                            System.out.println("Tool result (truncated): " +
                                    truncate(String.valueOf(result.content()), 100));
                        }
                    }
                }
                case ResultMessage result -> {
                    System.out.println("Completed in " + result.numTurns() + " turns");
                }
                default -> {
                }
            }
        }
    }

    /**
     * Using the Write tool to create files.
     */
    static void writeFiles() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .allowedTools(List.of("Write"))
                .permissionMode(PermissionMode.ACCEPT_EDITS)
                .cwd(Path.of("/tmp"))
                .maxTurns(3)
                .build();

        List<Message> messages = ClaudeSDK.query(
                "Create a file called hello.txt with the content 'Hello from Claude!'",
                options);

        for (Message msg : messages) {
            if (msg instanceof AssistantMessage assistant) {
                if (assistant.hasToolUse()) {
                    System.out.println("Claude is writing a file...");
                    for (ContentBlock block : assistant.content()) {
                        if (block instanceof ToolUseBlock toolUse) {
                            System.out.println("  File: " + toolUse.input().get("file_path"));
                        }
                    }
                } else {
                    System.out.println("Claude: " + assistant.getTextContent());
                }
            }
        }
    }

    /**
     * Using the Bash tool to run commands.
     */
    static void bashCommands() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .allowedTools(List.of("Bash"))
                .permissionMode(PermissionMode.ACCEPT_EDITS)
                .maxTurns(3)
                .build();

        List<Message> messages = ClaudeSDK.query(
                "Run 'echo Hello World' and tell me what it outputs",
                options);

        for (Message msg : messages) {
            if (msg instanceof AssistantMessage assistant) {
                for (ContentBlock block : assistant.content()) {
                    if (block instanceof ToolUseBlock toolUse) {
                        System.out.println("Running command: " + toolUse.input().get("command"));
                    } else if (block instanceof TextBlock text) {
                        System.out.println("Claude: " + text.text());
                    }
                }
            }
        }
    }

    /**
     * Using multiple tools together.
     */
    static void multipleTools() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .allowedTools(List.of("Read", "Write", "Bash", "Glob"))
                .permissionMode(PermissionMode.ACCEPT_EDITS)
                .cwd(Path.of(System.getProperty("user.dir")))
                .maxTurns(10)
                .build();

        List<Message> messages = ClaudeSDK.query(
                "List all Java files in the src directory and count them",
                options);

        int toolUsageCount = 0;
        for (Message msg : messages) {
            if (msg instanceof AssistantMessage assistant) {
                for (ContentBlock block : assistant.content()) {
                    if (block instanceof ToolUseBlock toolUse) {
                        toolUsageCount++;
                        System.out.println(toolUsageCount + ". Tool: " + toolUse.name());
                    } else if (block instanceof TextBlock text) {
                        System.out.println("\nClaude's answer: " + text.text());
                    }
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
