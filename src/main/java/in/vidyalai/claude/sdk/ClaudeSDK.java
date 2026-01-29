package in.vidyalai.claude.sdk;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import in.vidyalai.claude.sdk.internal.QueryHandler;
import in.vidyalai.claude.sdk.internal.SdkVersion;
import in.vidyalai.claude.sdk.internal.transport.SubprocessCLITransport;
import in.vidyalai.claude.sdk.mcp.SdkMcpServer;
import in.vidyalai.claude.sdk.mcp.SdkMcpTool;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.mcp.McpSdkServerConfig;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;

/**
 * Main facade for the Claude Agent SDK.
 * Query Claude Code for one-shot or unidirectional streaming interactions.
 * 
 * This provides a way for simple, stateless queries where you don't need
 * bidirectional communication or conversation management. For interactive,
 * stateful conversations, use {@link ClaudeSDKClient} instead.
 * 
 * Key differences from ClaudeSDKClient:
 * - **Unidirectional**: Send all messages upfront, receive all responses
 * - **Stateless**: Each query is independent, no conversation state
 * - **Simple**: Fire-and-forget style, no connection management
 * - **No interrupts**: Cannot interrupt or send follow-up messages
 * 
 * When to use query():
 * - Simple one-off questions ("What is 2+2?")
 * - Batch processing of independent prompts
 * - Code generation or analysis tasks
 * - Automated scripts and CI/CD pipelines
 * - When you know all inputs upfront
 * 
 * When to use ClaudeSDKClient:
 * - Interactive conversations with follow-ups
 * - Chat applications or REPL-like interfaces
 * - When you need to send messages based on responses
 * - When you need interrupt capabilities
 * - Long-running sessions with state
 *
 * <p>
 * This class also provides static factory methods for common use cases:
 *
 * <h2>One-shot Query</h2>
 * 
 * <pre>{@code
 * var options = ClaudeAgentOptions.builder()
 *         .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
 *         .maxTurns(1)
 *         .build();
 *
 * List<Message> messages = ClaudeSDK.query("What is 2+2?", options);
 * for (Message msg : messages) {
 *     if (msg instanceof AssistantMessage assistant) {
 *         System.out.println(assistant.getTextContent());
 *     }
 * }
 * }</pre>
 *
 * <h2>Interactive Client</h2>
 * 
 * <pre>{@code
 * try (var client = ClaudeSDK.createClient(options)) {
 *     client.connect();
 *     client.sendMessage("Hello!");
 *     for (Message msg : client.receiveResponse()) {
 *         // Process messages
 *     }
 * }
 * }</pre>
 *
 * @see ClaudeSDKClient
 * @see ClaudeAgentOptions
 */
public final class ClaudeSDK {

    private static final Duration DEFAULT_INITIALIZE_TIMEOUT = Duration.ofMinutes(1);

    private ClaudeSDK() {
        // Utility class
    }

    /**
     * Executes a one-shot query and returns all messages.
     *
     * <p>
     * This is the simplest way to interact with Claude for single-turn
     * queries where you don't need interactive conversation.
     *
     * <p>
     * This method uses a unified implementation with QueryHandler,
     * ensuring consistent behavior with streaming queries and proper support
     * for hooks and SDK MCP servers.
     *
     * <pre>{@code
     * var options = ClaudeAgentOptions.builder()
     *         .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
     *         .maxTurns(1)
     *         .build();
     *
     * List<Message> messages = ClaudeSDK.query("What is 2+2?", options);
     * }</pre>
     *
     * @param prompt  the prompt to send
     * @param options the agent options
     * @return list of all messages received
     * @throws IllegalArgumentException if canUseTool is set (requires streaming
     *                                  mode)
     */
    public static List<Message> query(String prompt, ClaudeAgentOptions options) {
        return query(prompt, options, null);
    }

    /**
     * Executes a one-shot query and returns all messages.
     *
     * @param prompt    the prompt to send
     * @param options   the agent options
     * @param transport custom transport implementation
     * @return list of all messages received
     * @throws IllegalArgumentException if canUseTool is set (requires streaming
     *                                  mode)
     */
    public static List<Message> query(String prompt, ClaudeAgentOptions options, Transport transport) {
        // Set entrypoint for analytics (matches Python SDK)
        System.setProperty("CLAUDE_CODE_ENTRYPOINT", "sdk-java");

        // Validate and configure options
        ClaudeAgentOptions effectiveOptions = validateAndConfigureOptions(options, false);

        if (transport == null) {
            // Create transport in non-streaming mode (prompt passed via CLI args)
            transport = new SubprocessCLITransport(prompt, false, effectiveOptions);
        }

        List<Message> messages = new ArrayList<>();
        QueryHandler queryHandler = null;
        try {
            transport.connect();

            // Extract SDK MCP servers
            Map<String, SdkMcpServer> sdkMcpServers = extractSdkMcpServers(effectiveOptions);

            // Create QueryHandler for unified control protocol handling
            // Use non-streaming mode (no initialize call needed)
            queryHandler = new QueryHandler(
                    transport,
                    false, // Non-streaming mode
                    effectiveOptions.canUseTool(),
                    effectiveOptions.hooks(),
                    sdkMcpServers,
                    DEFAULT_INITIALIZE_TIMEOUT,
                    effectiveOptions.maxMsgQSize());

            // Start reader thread
            queryHandler.start();

            // Collect all messages
            Iterator<Message> iterator = queryHandler.receiveMessages();
            while (iterator.hasNext()) {
                messages.add(iterator.next());
            }
        } finally {
            if (queryHandler != null) {
                queryHandler.close();
            }
        }

        return messages;
    }

    /**
     * Executes a one-shot query with default options.
     *
     * @param prompt the prompt to send
     * @return list of all messages received
     */
    public static List<Message> query(String prompt) {
        return query(prompt, ClaudeAgentOptions.defaults(), null);
    }

    /**
     * Executes a streaming query that sends multiple messages to Claude.
     *
     * <p>
     * This method allows sending a stream of messages to Claude, enabling
     * more complex interactions like multi-turn conversations in a single query.
     *
     * <p>
     * Each message in the iterator should have the structure:
     * 
     * <pre>{@code
     * {
     *     "type": "user",
     *     "session_id": "default",
     *     "message": {"role": "user", "content": "..."}
     * }
     * }</pre>
     *
     * <pre>{@code
     * var messages = List.of(
     *         Map.of("type", "user", "session_id", "default",
     *                 "message", Map.of("role", "user", "content", "First message")),
     *         Map.of("type", "user", "session_id", "default",
     *                 "message", Map.of("role", "user", "content", "Follow-up")));
     *
     * List<Message> responses = ClaudeSDK.query(messages.iterator(), options);
     * }</pre>
     *
     * @param messageStream an iterator of message dictionaries
     * @param options       the agent options
     * @return list of all messages received
     */
    public static List<Message> query(Iterator<Map<String, Object>> messageStream, ClaudeAgentOptions options) {
        return query(messageStream, options, null);
    }

    /**
     * Executes a streaming query that sends multiple messages to Claude.
     *
     * @param messageStream an iterator of message dictionaries
     * @param options       the agent options
     * @param transport     custom transport implementation
     * @return list of all messages received
     */
    public static List<Message> query(Iterator<Map<String, Object>> messageStream, ClaudeAgentOptions options,
            Transport transport) {
        // Set entrypoint for analytics (matches Python SDK)
        System.setProperty("CLAUDE_CODE_ENTRYPOINT", "sdk-java");

        // Validate and configure options
        ClaudeAgentOptions effectiveOptions = validateAndConfigureOptions(options, true);

        if (transport == null) {
            // Create transport in streaming mode
            transport = new SubprocessCLITransport(null, true, effectiveOptions);
        }

        List<Message> messages = new ArrayList<>();
        QueryHandler queryHandler = null;
        ExecutorService streamingExecutor = null;
        try {
            transport.connect();

            // Calculate initialize timeout
            long timeoutMs = Long.parseLong(System.getenv().getOrDefault("CLAUDE_CODE_STREAM_CLOSE_TIMEOUT", "60000"));
            Duration initializeTimeout = Duration.ofMillis(Math.max(timeoutMs, 60000));

            // Extract SDK MCP servers
            Map<String, SdkMcpServer> sdkMcpServers = extractSdkMcpServers(effectiveOptions);

            // Create QueryHandler for bidirectional control protocol
            @SuppressWarnings("resource")
            QueryHandler qh = new QueryHandler(
                    transport,
                    true,
                    effectiveOptions.canUseTool(),
                    effectiveOptions.hooks(),
                    sdkMcpServers,
                    initializeTimeout,
                    effectiveOptions.maxMsgQSize());

            // Start reader thread and initialize
            queryHandler = qh;
            queryHandler.start();
            queryHandler.initialize();

            // Create executor service for streaming input with named virtual threads
            streamingExecutor = Executors.newSingleThreadExecutor(
                    Thread.ofVirtual()
                            .name("ClaudeSDK-Streaming-", 0)
                            .factory());

            // Stream input messages in background
            streamingExecutor.submit(() -> qh.streamInput(messageStream));

            // Collect all response messages
            Iterator<Message> responseIterator = queryHandler.receiveMessages();
            while (responseIterator.hasNext()) {
                messages.add(responseIterator.next());
            }
        } finally {
            // Shutdown streaming executor
            if (streamingExecutor != null) {
                streamingExecutor.shutdown();
                try {
                    if (!streamingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        streamingExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    streamingExecutor.shutdownNow();
                }
            }

            // Close QueryHandler
            if (queryHandler != null) {
                queryHandler.close();
            }
        }

        return messages;
    }

    /**
     * Executes a streaming query with default options.
     *
     * @param messageStream an iterator of message dictionaries
     * @return list of all messages received
     */
    public static List<Message> query(Iterator<Map<String, Object>> messageStream) {
        return query(messageStream, ClaudeAgentOptions.defaults(), null);
    }

    /**
     * Validates and configures options for query execution.
     *
     * <p>
     * This method validates permission settings and automatically configures
     * the permissionPromptToolName if canUseTool callback is provided.
     *
     * @param options         the original options
     * @param isStreamingMode whether this is a streaming query
     * @return validated and configured options
     * @throws IllegalArgumentException if canUseTool is used in non-streaming mode
     *                                  or if canUseTool and
     *                                  permissionPromptToolName are both set
     */
    private static ClaudeAgentOptions validateAndConfigureOptions(
            ClaudeAgentOptions options,
            boolean isStreamingMode) {
        if (options.canUseTool() != null) {
            // canUseTool callback requires streaming mode (matches Python SDK validation)
            if (!isStreamingMode) {
                throw new IllegalArgumentException(
                        "canUseTool callback requires streaming mode. " +
                                "Use query(Iterator) or ClaudeSDKClient instead of query(String).");
            }

            // canUseTool and permissionPromptToolName are mutually exclusive
            if (options.permissionPromptToolName() != null) {
                throw new IllegalArgumentException(
                        "canUseTool callback cannot be used with permissionPromptToolName. " +
                                "Please use one or the other.");
            }

            // Automatically set permissionPromptToolName to "stdio" for control protocol
            return options.withPermissionPromptToolName("stdio");
        }

        return options;
    }

    /**
     * Extracts SDK MCP servers from options.
     */
    private static Map<String, SdkMcpServer> extractSdkMcpServers(ClaudeAgentOptions options) {
        Object mcpServers = options.mcpServers();
        if (mcpServers == null) {
            return null;
        }

        // Only handle Map<String, McpServerConfig> - other types (Path, String) are
        // handled by CLI
        if (!(mcpServers instanceof Map<?, ?> serverMap)) {
            return null;
        }

        if (serverMap.isEmpty()) {
            return null;
        }

        Map<String, SdkMcpServer> sdkServers = new HashMap<>();
        for (Map.Entry<?, ?> entry : serverMap.entrySet()) {
            if (entry.getValue() instanceof McpSdkServerConfig sdkConfig) {
                sdkServers.put((String) entry.getKey(), sdkConfig.instance());
            }
        }

        return (sdkServers.isEmpty() ? null : sdkServers);
    }

    /**
     * Executes a query and returns just the text content from assistant messages.
     *
     * <p>
     * This is a convenience method for simple use cases where you only need
     * the text response.
     *
     * @param prompt  the prompt to send
     * @param options the agent options
     * @return the combined text content from all assistant messages
     */
    public static String queryForText(String prompt, ClaudeAgentOptions options) {
        List<Message> messages = query(prompt, options);
        StringBuilder result = new StringBuilder();

        for (Message msg : messages) {
            if (msg instanceof AssistantMessage assistant) {
                String text = assistant.getTextContent();
                if (!text.isEmpty()) {
                    if (!result.isEmpty()) {
                        result.append("\n");
                    }
                    result.append(text);
                }
            }
        }

        return result.toString();
    }

    /**
     * Executes a query and returns the result message.
     *
     * @param prompt  the prompt to send
     * @param options the agent options
     * @return the result message, or null if not found
     */
    public static ResultMessage queryForResult(String prompt, ClaudeAgentOptions options) {
        List<Message> messages = query(prompt, options);

        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof ResultMessage result) {
                return result;
            }
        }

        return null;
    }

    /**
     * Creates a new interactive client with the specified options.
     *
     * <p>
     * Remember to close the client when done:
     * 
     * <pre>{@code
     * try (var client = ClaudeSDK.createClient(options)) {
     *     client.connect();
     *     // Use client...
     * }
     * }</pre>
     *
     * @param options the agent options
     * @return a new client instance
     */
    public static ClaudeSDKClient createClient(ClaudeAgentOptions options) {
        return new ClaudeSDKClient(options);
    }

    /**
     * Creates a new interactive client with default options.
     *
     * @return a new client instance
     */
    public static ClaudeSDKClient createClient() {
        return new ClaudeSDKClient();
    }

    /**
     * Creates an in-process MCP server with the specified tools.
     *
     * <p>
     * Unlike external MCP servers that run as separate processes, SDK MCP servers
     * run directly in your application's process. This provides:
     * <ul>
     * <li>Better performance (no IPC overhead)</li>
     * <li>Simpler deployment (single process)</li>
     * <li>Easier debugging (same process)</li>
     * <li>Direct access to your application's state</li>
     * </ul>
     *
     * <pre>{@code
     * SdkMcpTool<Map<String, Object>> greet = SdkMcpTool.create(
     *         "greet", "Greet a user",
     *         Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))),
     *         args -> CompletableFuture.completedFuture(ToolResult.text("Hello, " + args.get("name") + "!")));
     *
     * McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer("myserver", List.of(greet));
     *
     * var options = ClaudeAgentOptions.builder()
     *         .mcpServers(Map.of("myserver", server))
     *         .build();
     * }</pre>
     *
     * @param name  unique identifier for the server
     * @param tools list of tools to register
     * @return an McpSdkServerConfig for use with ClaudeAgentOptions
     */
    public static McpSdkServerConfig createSdkMcpServer(String name, List<SdkMcpTool<?>> tools) {
        return createSdkMcpServer(name, "1.0.0", tools);
    }

    /**
     * Creates an in-process MCP server with the specified tools and version.
     *
     * @param name    unique identifier for the server
     * @param version server version string
     * @param tools   list of tools to register
     * @return an McpSdkServerConfig for use with ClaudeAgentOptions
     */
    public static McpSdkServerConfig createSdkMcpServer(String name, String version, List<SdkMcpTool<?>> tools) {
        SdkMcpServer server = SdkMcpServer.create(name, version, tools);
        return server.toConfig();
    }

    /**
     * Creates an in-process MCP server from methods annotated with {@code @Tool}.
     *
     * <pre>{@code
     * public class MyTools {
     *     @Tool(name = "greet", description = "Greet a user")
     *     public ToolResult greet(Map<String, Object> args) {
     *         return ToolResult.text("Hello, " + args.get("name") + "!");
     *     }
     * }
     *
     * MyTools tools = new MyTools();
     * McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer("myserver", tools);
     * }</pre>
     *
     * @param name     unique identifier for the server
     * @param instance object containing @Tool annotated methods
     * @return an McpSdkServerConfig for use with ClaudeAgentOptions
     */
    public static McpSdkServerConfig createSdkMcpServer(String name, Object instance) {
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods(name, instance);
        return server.toConfig();
    }

    /**
     * Returns the SDK version.
     *
     * @return the version string
     */
    public static String getVersion() {
        return SdkVersion.VERSION;
    }

}
