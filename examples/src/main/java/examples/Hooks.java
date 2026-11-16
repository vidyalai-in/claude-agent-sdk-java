package examples;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookMatcher;
import in.vidyalai.claude.sdk.types.hook.HookOnFailure;
import in.vidyalai.claude.sdk.types.hook.input.PostToolUseHookInput;
import in.vidyalai.claude.sdk.types.hook.input.PreToolUseHookInput;
import in.vidyalai.claude.sdk.types.hook.input.StopHookInput;
import in.vidyalai.claude.sdk.types.hook.input.UserPromptSubmitHookInput;
import in.vidyalai.claude.sdk.types.hook.output.HookOutput;
import in.vidyalai.claude.sdk.types.hook.output.PreToolUseHookSpecificOutput;
import in.vidyalai.claude.sdk.types.hook.output.UserPromptSubmitHookSpecificOutput;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.permission.PermissionDecision;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;

/**
 * Example showing how to use hooks for custom processing at various stages.
 */
public class Hooks {

    // On Windows the model can run a shell command with PowerShell instead of
    // Bash, so the guard below covers both (as the Python e2e tests do).
    private static final List<String> SHELL_TOOLS = List.of("Bash", "PowerShell");

    public static void main(String[] args) {
        // Example 1: PreToolUse hook for blocking commands
        System.out.println("=== PreToolUse: Block Dangerous Commands ===");
        try {
            blockDangerousCommands();
        } catch (CLIConnectionException e) {
            // The guard is set to fail closed (HookOnFailure.BLOCK), so a CLI
            // that cannot honor that is refused at connect() instead of
            // running the commands unguarded.
            System.out.println("[HOOK] Guard not started: " + e.getMessage());
        }

        // Example 2: PostToolUse hook for logging
        System.out.println("\n=== PostToolUse: Logging ===");
        logToolUsage();

        // Example 3: Multiple hooks
        System.out.println("\n=== Multiple Hooks ===");
        multipleHooks();

        // Example 4: UserPromptSubmit hook
        System.out.println("\n=== UserPromptSubmit Hook ===");
        userPromptHook();
    }

    /**
     * PreToolUse hook to block dangerous shell commands.
     */
    static void blockDangerousCommands() {
        // Define dangerous patterns
        List<String> dangerousPatterns = List.of(
                "rm -rf",
                "sudo",
                "chmod 777",
                "> /dev/",
                "mkfs",
                "dd if=");

        HookMatcher.HookCallback blockDangerous = (input, context) -> {
            if (input instanceof PreToolUseHookInput preToolUse) {
                if (SHELL_TOOLS.contains(preToolUse.toolName())) {
                    String command = String.valueOf(preToolUse.toolInput().get("command"));

                    for (String pattern : dangerousPatterns) {
                        if (command.contains(pattern)) {
                            System.out.println("[HOOK] BLOCKED: Command contains dangerous pattern '" + pattern + "'");
                            return CompletableFuture.completedFuture(
                                    HookOutput.builder()
                                            .hookSpecificOutput(
                                                    new PreToolUseHookSpecificOutput(PermissionDecision.DENY,
                                                            "Command blocked: contains dangerous pattern '" + pattern
                                                                    + "'",
                                                            null, null))
                                            .build());
                        }
                    }
                    System.out.println("[HOOK] ALLOWED: " + command);
                }
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        // A security guard should fail closed: with HookOnFailure.BLOCK, a hook
        // that throws or completes with null blocks the command instead of
        // letting it run. That is why the "no opinion" path above returns
        // HookOutput.empty(), never null. Connecting to a CLI too old to honor
        // onFailure throws CLIConnectionException rather than fail open.
        HookMatcher shellMatcher = new HookMatcher(String.join("|", SHELL_TOOLS), List.of(blockDangerous), null,
                HookOnFailure.BLOCK);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .allowedTools(SHELL_TOOLS)
                .hooks(Map.of(HookEvent.PRE_TOOL_USE, List.of(shellMatcher)))
                .maxTurns(5)
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            // Safe command
            client.connect();
            client.sendMessage("Run: echo 'Hello World'");
            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage a) {
                    System.out.println("Claude: " + a.getTextContent());
                }
            }

            // Dangerous command (will be blocked)
            System.out.println("\nAttempting dangerous command...");
            client.sendMessage("Run: rm -rf /tmp/test");
            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage a) {
                    System.out.println("Claude: " + a.getTextContent());
                }
            }
        }
    }

    /**
     * PostToolUse hook to log all tool executions.
     */
    static void logToolUsage() {
        AtomicInteger toolCount = new AtomicInteger(0);

        HookMatcher.HookCallback logCallback = (input, context) -> {
            if (input instanceof PostToolUseHookInput postToolUse) {
                int count = toolCount.incrementAndGet();
                System.out.printf("[LOG #%d] Tool: %s%n", count, postToolUse.toolName());
                System.out.printf("         Input: %s%n", postToolUse.toolInput());
                String responseStr = postToolUse.toolResponse() != null ? String.valueOf(postToolUse.toolResponse())
                        : "";
                System.out.printf("         Response length: %d chars%n", responseStr.length());
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        // Wildcard matcher for all tools
        HookMatcher allTools = new HookMatcher("*", List.of(logCallback));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .allowedTools(List.of("Bash", "Read"))
                .permissionMode(PermissionMode.ACCEPT_EDITS)
                .hooks(Map.of(HookEvent.POST_TOOL_USE, List.of(allTools)))
                .maxTurns(5)
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage("Run 'date' and 'whoami' commands");
            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage a) {
                    System.out.println("\nClaude: " + a.getTextContent());
                }
            }
        }

        System.out.println("\nTotal tools executed: " + toolCount.get());
    }

    /**
     * Using multiple hooks together.
     */
    @SuppressWarnings("null")
    static void multipleHooks() {
        // Pre-tool use: add context
        HookMatcher.HookCallback preHook = (input, context) -> {
            if (input instanceof PreToolUseHookInput pre) {
                System.out.println("[PRE] About to run: " + pre.toolName());
                // raw() is the CLI's input exactly as sent, including any
                // field newer than this SDK's typed record.
                System.out.println("[PRE] Input fields: " + pre.raw().keySet());
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        // Post-tool use: record timing
        HookMatcher.HookCallback postHook = (input, context) -> {
            if (input instanceof PostToolUseHookInput post) {
                System.out.println("[POST] Completed: " + post.toolName());
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        // Stop hook
        HookMatcher.HookCallback stopHook = (input, context) -> {
            if (input instanceof StopHookInput stop) {
                System.out.println("[STOP] Session ending, stop_hook_active: " + stop.stopHookActive());
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .allowedTools(List.of("Bash"))
                .permissionMode(PermissionMode.ACCEPT_EDITS)
                .hooks(Map.of(
                        HookEvent.PRE_TOOL_USE, List.of(new HookMatcher("*", List.of(preHook))),
                        HookEvent.POST_TOOL_USE, List.of(new HookMatcher("*", List.of(postHook))),
                        HookEvent.STOP, List.of(new HookMatcher(null, List.of(stopHook)))))
                .maxTurns(3)
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage("Run: echo 'Testing hooks'");
            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage a) {
                    System.out.println("Claude: " + a.getTextContent());
                }
            }
        }
    }

    /**
     * UserPromptSubmit hook to modify or validate prompts.
     */
    @SuppressWarnings("null")
    static void userPromptHook() {
        HookMatcher.HookCallback promptHook = (input, context) -> {
            if (input instanceof UserPromptSubmitHookInput promptInput) {
                String prompt = promptInput.prompt();
                System.out.println("[PROMPT HOOK] Received prompt: " + prompt);

                // Check for blocked words
                List<String> blockedWords = List.of("password", "secret", "api_key");
                for (String word : blockedWords) {
                    if (prompt.toLowerCase().contains(word)) {
                        System.out.println("[PROMPT HOOK] Blocked: contains sensitive word '" + word + "'");
                        return CompletableFuture.completedFuture(
                                HookOutput.builder()
                                        .shouldContinue(false)
                                        .reason("Prompt contains sensitive information")
                                        .build());
                    }
                }

                // Add context
                return CompletableFuture.completedFuture(
                        HookOutput.builder()
                                .hookSpecificOutput(
                                        new UserPromptSubmitHookSpecificOutput("User is running in example mode"))
                                .build());
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .hooks(Map.of(
                        HookEvent.USER_PROMPT_SUBMIT, List.of(new HookMatcher(null, List.of(promptHook)))))
                .maxTurns(3)
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            // Normal prompt
            client.connect();
            client.sendMessage("What is my password");
            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage a) {
                    System.out.println("Claude: " + a.getTextContent());
                }
            }
        }
    }

}
