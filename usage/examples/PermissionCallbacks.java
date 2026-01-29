package examples;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.permission.PermissionBehavior;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;
import in.vidyalai.claude.sdk.types.permission.PermissionResultAllow;
import in.vidyalai.claude.sdk.types.permission.PermissionResultDeny;
import in.vidyalai.claude.sdk.types.permission.PermissionRuleValue;
import in.vidyalai.claude.sdk.types.permission.PermissionUpdate;
import in.vidyalai.claude.sdk.types.permission.PermissionUpdateDestination;

/**
 * Example showing how to use permission callbacks for fine-grained tool
 * control.
 */
public class PermissionCallbacks {

    public static void main(String[] args) {
        // Example 1: Simple allow/deny
        System.out.println("=== Simple Allow/Deny ===");
        simpleAllowDeny();

        // Example 2: Input modification
        System.out.println("\n=== Input Modification ===");
        inputModification();

        // Example 3: Permission updates
        System.out.println("\n=== Permission Updates ===");
        permissionUpdates();
    }

    /**
     * Simple callback that allows safe tools and denies dangerous ones.
     */
    @SuppressWarnings("null")
    static void simpleAllowDeny() {
        Set<String> safeTools = Set.of("Read", "Glob", "Grep", "Bash");
        Set<String> dangerousTools = Set.of("Write", "Edit");
        List<Map<String, Object>> toolUses = new ArrayList<>();

        ClaudeAgentOptions.CanUseTool callback = (toolName, input, context) -> {
            Map<String, Object> toolInfo = new HashMap<>();
            toolInfo.put("name", toolName);
            toolInfo.put("input", input);
            toolInfo.put("suggestions", context.suggestions());
            toolUses.add(toolInfo);

            if (safeTools.contains(toolName)) {
                System.out.println("[PERMISSION] Allowing safe tool: " + toolName);
                return CompletableFuture.completedFuture(new PermissionResultAllow());
            }

            if (dangerousTools.contains(toolName)) {
                System.out.println("[PERMISSION] Denying dangerous tool: " + toolName);
                return CompletableFuture.completedFuture(
                        new PermissionResultDeny(
                                "Tool '" + toolName + "' is not allowed in safe mode",
                                false // don't interrupt
                ));
            }

            try (Scanner reader = new Scanner(System.in)) {
                System.out.println("Allow %s tool? y/n".formatted(toolName));
                String decision = reader.nextLine();
                if (decision.trim().toLowerCase().contains("y")) {
                    return CompletableFuture.completedFuture(new PermissionResultAllow());
                }
            }

            // Unknown tools - deny with interrupt
            System.out.println("[PERMISSION] Unknown tool, interrupting: " + toolName);
            return CompletableFuture.completedFuture(
                    new PermissionResultDeny(
                            "User denied perm to Unknown tool: " + toolName,
                            true // interrupt
            ));
        };

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .canUseTool(callback)
                .cwd(Path.of("/tmp"))
                .permissionMode(PermissionMode.DEFAULT)
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage("""
                    Please do the following:
                    1. List the files in the current directory
                    2. Create a simple Python hello world script at hello.py
                    3. Run the script to test it
                    """);
            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage a) {
                    System.out.println("Claude: " + a.getTextContent());
                }
            }
        }

        System.out.println("ToolUse Summary: " + toolUses);
    }

    /**
     * Callback that modifies tool input before execution.
     */
    @SuppressWarnings("null")
    static void inputModification() {
        ClaudeAgentOptions.CanUseTool callback = (toolName, input, context) -> {
            if ("Bash".equals(toolName)) {
                String command = (String) input.get("command");

                // Add timeout to all commands
                Map<String, Object> modifiedInput = new HashMap<>(input);
                modifiedInput.put("timeout", 30000); // 30 seconds

                // Redirect stderr to stdout
                if (!command.contains("2>&1")) {
                    modifiedInput.put("command", command + " 2>&1");
                }

                System.out.println("[PERMISSION] Modified Bash input:");
                System.out.println("  Original: " + command);
                System.out.println("  Modified: " + modifiedInput.get("command"));
                System.out.println("  Timeout: " + modifiedInput.get("timeout") + "ms");

                return CompletableFuture.completedFuture(
                        new PermissionResultAllow(modifiedInput, null));
            }

            if ("Write".equals(toolName)) {
                String filePath = (String) input.get("file_path");

                // Only allow writing to /tmp
                if (!filePath.startsWith("/tmp/")) {
                    System.out.println("[PERMISSION] Redirecting write to /tmp");
                    Map<String, Object> modifiedInput = new HashMap<>(input);
                    modifiedInput.put("file_path", "/tmp/" + filePath.replace("/", "_"));
                    System.out.println("[PERMISSION] New Path: " + modifiedInput.get("file_path"));
                    return CompletableFuture.completedFuture(
                            new PermissionResultAllow(modifiedInput, null));
                }
            }

            return CompletableFuture.completedFuture(new PermissionResultAllow());
        };

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .canUseTool(callback)
                .permissionMode(PermissionMode.DEFAULT)
                .maxTurns(3)
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage("Create 'hello.txt' with content 'hello'");
            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage a) {
                    System.out.println("Claude: " + truncate(a.getTextContent(), 200));
                }
            }
        }
    }

    /**
     * Callback that updates permissions for the session.
     */
    @SuppressWarnings("null")
    static void permissionUpdates() {
        ClaudeAgentOptions.CanUseTool callback = (toolName, input, context) -> {
            return CompletableFuture.supplyAsync(() -> {
                System.out.println("[PERMISSION] Evaluating: " + toolName);

                // First time seeing Bash, upgrade to bypass mode for session
                if ("Bash".equals(toolName)) {
                    List<PermissionUpdate> updates = List.of(
                            PermissionUpdate.setMode(
                                    PermissionMode.BYPASS_PERMISSIONS,
                                    PermissionUpdateDestination.SESSION));

                    System.out.println("[PERMISSION] Upgrading session to bypass mode");
                    return new PermissionResultAllow(null, updates);
                }

                // Add permission rules
                if ("Write".equals(toolName)) {
                    List<PermissionUpdate> updates = List.of(
                            PermissionUpdate.addRules(
                                    List.of(new PermissionRuleValue("Write", "allow /tmp/*")),
                                    PermissionBehavior.ALLOW,
                                    PermissionUpdateDestination.SESSION));

                    System.out.println("[PERMISSION] Adding write rule for /tmp/*");
                    return new PermissionResultAllow(null, updates);
                }

                return new PermissionResultAllow();
            });
        };

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .canUseTool(callback)
                .cwd(Path.of("/tmp"))
                .permissionMode(PermissionMode.DEFAULT)
                .maxTurns(3)
                .build();

        System.out.println("Permission updates would be applied during execution");
        System.out.println("(Demonstrating callback structure)");
        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage("Write testpu.txt with hellow world");
            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage a) {
                    System.out.println("Claude: " + truncate(a.getTextContent(), 100));
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
