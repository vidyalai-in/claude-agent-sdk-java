package examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.config.AIModel;
import in.vidyalai.claude.sdk.types.config.AgentDefinition;
import in.vidyalai.claude.sdk.types.config.SandboxIgnoreViolations;
import in.vidyalai.claude.sdk.types.config.SandboxNetworkConfig;
import in.vidyalai.claude.sdk.types.config.SandboxSettings;
import in.vidyalai.claude.sdk.types.config.SdkBeta;
import in.vidyalai.claude.sdk.types.config.SettingSource;
import in.vidyalai.claude.sdk.types.config.SystemPromptPreset;
import in.vidyalai.claude.sdk.types.config.ToolsPreset;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookMatcher;
import in.vidyalai.claude.sdk.types.hook.HookOutput;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.UserMessage;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;
import in.vidyalai.claude.sdk.types.permission.PermissionResultAllow;

/**
 * Example showing advanced features of the Claude Agent SDK.
 */
public class AdvancedFeatures {

    public static void main(String[] args) throws IOException {
        // Example 1: File checkpointing
        System.out.println("=== File Checkpointing ===");
        fileCheckpointing();

        // Example 2: Custom agents
        System.out.println("\n=== Custom Agents ===");
        customAgents();

        // Example 3: Sandbox configuration
        System.out.println("\n=== Sandbox Configuration ===");
        sandboxConfiguration();

        // Example 4: Structured output
        System.out.println("\n=== Structured Output ===");
        structuredOutput();

        // Example 5: Beta features
        System.out.println("\n=== Beta Features ===");
        betaFeatures();

        // Example 6: Complete configuration
        System.out.println("\n=== Complete Configuration ===");
        completeConfiguration();
    }

    /**
     * Using file checkpointing to track and rewind changes.
     * 
     * @throws IOException
     */
    static void fileCheckpointing() throws IOException {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .enableFileCheckpointing(true)
                .allowedTools(List.of("Write", "Read"))
                .permissionMode(PermissionMode.ACCEPT_EDITS)
                .cwd(Path.of("/tmp"))
                .extraArgs(Map.of("replay-user-messages", ""))
                .env(Map.of("CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING", "1"))
                .maxTurns(3)
                .build();

        String checkpointId = null;
        String sessionId = null;
        try (var client = ClaudeSDK.createClient(options)) {
            // Create a file
            client.connect();
            String message = "Create a file called checkpoint_test.txt with 'Version 1' content in '/tmp' dir";
            System.out.println("SEND: " + message);
            client.sendMessage(message);

            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage a) {
                    System.out.println("Claude: " + a.getTextContent());
                }
            }

            // Modify the file
            message = "Overwrite checkpoint_test.txt in /tmp dir to say 'Version 2'";
            System.out.println("SEND: " + message);
            client.sendMessage(message);
            for (Message msg : client.receiveResponse()) {
                if ((msg instanceof UserMessage user) && (user.uuid() != null)) {
                    if (checkpointId == null) {
                        checkpointId = user.uuid();
                        System.out.println("Checkpoint saved: " + checkpointId);
                    }
                } else if (msg instanceof AssistantMessage a) {
                    System.out.println("Claude: " + a.getTextContent());
                } else if (msg instanceof ResultMessage r) {
                    if (sessionId == null) {
                        sessionId = r.sessionId();
                        System.out.println("SessionId: " + sessionId);
                    }
                }
            }
        }

        System.out.println("FILE content: " + Files.readString(Path.of("/tmp/checkpoint_test.txt")));
        if ((checkpointId != null) && (sessionId != null)) {
            options = ClaudeAgentOptions.builder()
                    .enableFileCheckpointing(true)
                    .resume(sessionId)
                    .allowedTools(List.of("Write", "Read"))
                    .permissionMode(PermissionMode.ACCEPT_EDITS)
                    .cwd(Path.of("/tmp"))
                    .maxTurns(3)
                    .build();

            try (var client = ClaudeSDK.createClient(options)) {
                // Create a file
                client.connect();

                // Rewind to checkpoint
                System.out.println("\nRewinding to checkpoint: " + checkpointId);
                client.rewindFiles(checkpointId);
                System.out.println("Files rewound to Version 1 state");
            }
            System.out.println("FILE content after rewind: "
                    + Files.readString(Path.of("/tmp/checkpoint_test.txt")));
        } else {
            System.out.println("NO checkpoint session info");
        }
    }

    /**
     * Defining and using custom agents.
     */
    static void customAgents() {
        // Define specialized agents
        AgentDefinition codeReviewer = new AgentDefinition(
                "Code Review Specialist",
                "You are an expert code reviewer. Focus on:\n" +
                        "- Security vulnerabilities\n" +
                        "- Performance issues\n" +
                        "- Code style and best practices\n" +
                        "Be thorough but constructive.",
                List.of("Read", "Glob", "Grep"),
                AIModel.SONNET);

        AgentDefinition testWriter = new AgentDefinition(
                "Test Writer",
                "You write comprehensive unit tests. Follow these principles:\n" +
                        "- Test edge cases\n" +
                        "- Use descriptive test names\n" +
                        "- Mock external dependencies",
                List.of("Read", "Write", "Bash"),
                AIModel.SONNET);

        AgentDefinition documentor = new AgentDefinition(
                "Documentation Writer",
                "You write clear, concise documentation. Include:\n" +
                        "- Usage examples\n" +
                        "- API references\n" +
                        "- Common pitfalls",
                List.of("Read", "Write"),
                AIModel.HAIKU // Use faster model for docs
        );

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .agents(Map.of(
                        "code-reviewer", codeReviewer,
                        "test-writer", testWriter,
                        "documentor", documentor))
                .maxTurns(5)
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage("""
                    Use the code-reviewer agent to review the code in usage/examples/QuickStart.java
                    Use documentor agent to document it
                    """);

            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage assistant) {
                    System.out.println("Claude: " + assistant.getTextContent());
                }
            }
        }
    }

    /**
     * Configuring sandbox for secure execution.
     */
    @SuppressWarnings("unused")
    static void sandboxConfiguration() {
        // Network configuration
        SandboxNetworkConfig networkConfig = new SandboxNetworkConfig(
                List.of("/var/run/docker.sock"), // Allow specific unix sockets
                false, // Don't allow all unix sockets
                true, // Allow local binding
                8080, // HTTP proxy port
                1080 // SOCKS proxy port
        );

        // Violations to ignore
        SandboxIgnoreViolations ignoreViolations = new SandboxIgnoreViolations(
                List.of("/tmp", "/var/tmp"), // Ignore file violations for temp dirs
                List.of("localhost", "127.0.0.1") // Ignore network violations for localhost
        );

        // Full sandbox settings
        SandboxSettings sandbox = new SandboxSettings(
                true, // enabled
                true, // autoAllowBashIfSandboxed
                List.of("git", "npm", "docker"), // excludedCommands
                null, // allowUnsandboxedCommands
                networkConfig, // network
                ignoreViolations, // ignoreViolations
                false // enableWeakerNestedSandbox
        );

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sandbox(sandbox)
                .allowedTools(List.of("Bash"))
                .build();

        System.out.println("Sandbox configuration:");
        System.out.println("  Enabled: " + sandbox.enabled());
        System.out.println("  Auto-allow bash: " + sandbox.autoAllowBashIfSandboxed());
        System.out.println("  Excluded commands: " + sandbox.excludedCommands());
        System.out.println("  Network config: HTTP proxy on " + networkConfig.httpProxyPort());
    }

    /**
     * Using structured output with JSON schema.
     */
    static void structuredOutput() {
        // Define JSON schema for output
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "sentiment", Map.of(
                                "type", "string",
                                "enum", List.of("positive", "negative", "neutral"),
                                "description", "The overall sentiment"),
                        "confidence", Map.of(
                                "type", "number",
                                "minimum", 0,
                                "maximum", 1,
                                "description", "Confidence score between 0 and 1"),
                        "keywords", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "Key words or phrases")),
                "required", List.of("sentiment", "confidence"));

        Map<String, Object> outputFormat = Map.of(
                "type", "json_schema",
                "schema", schema);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .outputFormat(outputFormat)
                .maxTurns(3)
                .build();

        System.out.println("Structured output schema defined for sentiment analysis");
        System.out.println("Expected fields: sentiment (enum), confidence (0-1), keywords (array)");

        try {
            List<Message> messages = ClaudeSDK.query(
                    "Analyze the sentiment of: 'I love using this SDK, it's incredibly well designed!'",
                    options);

            for (Message msg : messages) {
                if (msg instanceof ResultMessage result) {
                    if (result.structuredOutput() != null) {
                        System.out.println("\nStructured output:");
                        System.out.println(result.structuredOutput());
                    }
                } else if (msg instanceof AssistantMessage a) {
                    System.out.println("Response: " + a.getTextContent());
                }
            }
        } catch (Exception e) {
            System.out.println("(Demo - actual execution would require running Claude)");
        }
    }

    /**
     * Using beta features.
     */
    @SuppressWarnings("unused")
    static void betaFeatures() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .betas(List.of(SdkBeta.CONTEXT_1M)) // Extended context beta
                .maxThinkingTokens(16000) // Extended thinking
                .build();

        System.out.println("Beta features enabled:");
        System.out.println("  - context-1m: Extended 1M token context window");
        System.out.println("  - Max thinking tokens: 16000");
        System.out.println("\nBeta features may change or be removed in future versions");
    }

    /**
     * Complete configuration example showing all options.
     */
    @SuppressWarnings("null")
    static void completeConfiguration() {
        // Permission callback
        ClaudeAgentOptions.CanUseTool permissionCallback = (toolName, input,
                context) -> java.util.concurrent.CompletableFuture.completedFuture(
                        new PermissionResultAllow());

        // Hook callback
        HookMatcher.HookCallback hookCallback = (input, context) -> java.util.concurrent.CompletableFuture
                .completedFuture(
                        HookOutput.empty());

        // Build complete options
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                // Model settings
                .model("claude-sonnet-4-5")
                .fallbackModel("claude-haiku-3-5")
                .maxThinkingTokens(8000)

                // System prompt
                .systemPrompt(SystemPromptPreset.claudeCode("Be concise and helpful."))

                // Tool configuration
                .tools(ToolsPreset.claudeCode())
                .allowedTools(List.of("Read", "Write", "Bash"))
                .disallowedTools(List.of("Execute"))

                // Permission settings
                .permissionMode(PermissionMode.ACCEPT_EDITS)
                .canUseTool(permissionCallback)
                .permissionPromptToolName("stdio")

                // Session settings
                .continueConversation(false)
                .forkSession(false)

                // Limits
                .maxTurns(20)
                .maxBudgetUsd(5.0)
                .maxBufferSize(4096)

                // Paths
                .cwd(Path.of(System.getProperty("user.dir")))
                .addDirs(List.of(Path.of("/shared")))

                // Hooks
                .hooks(Map.of(
                        HookEvent.PRE_TOOL_USE,
                        List.of(new HookMatcher("*", List.of(hookCallback)))))

                // Sandbox
                .sandbox(new SandboxSettings(true))

                // MCP servers (would add actual servers here)
                // .mcpServers(Map.of("server", serverConfig))

                // Environment
                .env(Map.of(
                        "CUSTOM_VAR", "value",
                        "DEBUG", "true"))

                // Extra CLI arguments
                .extraArgs(Map.of("verbose", ""))

                // User identification
                .user("example-user")

                // Beta features
                .betas(List.of(SdkBeta.CONTEXT_1M))

                // Setting sources
                .settingSources(List.of(SettingSource.USER, SettingSource.PROJECT))

                // Advanced features
                .includePartialMessages(true)
                .enableFileCheckpointing(true)

                .build();

        System.out.println("Complete configuration created with all options:");
        System.out.println("  Model: " + options.model());
        System.out.println("  Permission mode: " + options.permissionMode());
        System.out.println("  Max turns: " + options.maxTurns());
        System.out.println("  Max budget: $" + options.maxBudgetUsd());
        System.out.println("  Sandbox enabled: " + (options.sandbox() != null && options.sandbox().enabled()));
        System.out.println("  Streaming: " + options.includePartialMessages());
        System.out.println("  File checkpointing: " + options.enableFileCheckpointing());
        System.out.println("  Betas: " + options.betas());
    }

}
